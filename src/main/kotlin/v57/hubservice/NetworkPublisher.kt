package v57.hubservice

import channel.defaultJsonCodec
import java.io.ByteArrayOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.time.Instant
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hub Network share publication, ported from the TS SDK
 * (`hub-service/network.ts`). A share makes a local TCP/UDP port reachable
 * through Hub Network: for each share the local hub-net agent attests that
 * this service owns the port (POST `/challenge`, sign the nonce with the
 * service key, POST `/attest` over the agent's Unix socket), then the
 * complete set goes to the hub with `network/shares/replace`, which answers
 * one publication per share in request order. The declared set is
 * republished after every reconnect.
 */
class NetworkPublisher {
  /** A share: a local target published under a stable name with an access policy. */
  data class Share(val name: String, val protocol: String, val host: String, val port: Int, val policy: Map<String, Any?>? = null) {
    /** The share as the hub expects it (without attestation). */
    fun toMap(): LinkedHashMap<String, Any?> {
      val map = linkedMapOf<String, Any?>(
        "name" to name,
        "protocol" to protocol,
        "target" to linkedMapOf("host" to host, "port" to port),
      )
      if (policy != null) map["policy"] = policy
      return map
    }
  }

  /**
   * A local-agent failure; [code] is `agent-unavailable`, `agent-timeout`,
   * `agent-rejected`, `invalid-agent-response`, `attestation-mismatch` or
   * `attestation-expired`.
   */
  class AgentException(val code: String, message: String) : RuntimeException(message)

  private val shares = TreeMap<String, Share>()
  private var publicationsByShare = linkedMapOf<String, Map<String, Any?>>()

  /** The agent socket; default `$HUB_NET_AGENT_SOCKET`, else the platform's. */
  @Volatile
  var agentSocket: String = defaultAgentSocket()

  @Volatile
  var agentTimeoutMs: Long = 5000

  /** Whether any share is declared. */
  val hasShares: Boolean @Synchronized get() = shares.isNotEmpty()

  /** The declared shares, sorted by name. */
  val declaredShares: List<Share> @Synchronized get() = shares.values.toList()

  /** The current publications, in share-name order. */
  val publications: List<Map<String, Any?>> @Synchronized get() = publicationsByShare.values.toList()

  /** The publication of [shareName], or null. */
  @Synchronized
  fun publication(shareName: String): Map<String, Any?>? = publicationsByShare[shareName]

  /** Declares a share, published on the next connect and after every reconnect. */
  @Synchronized
  fun declare(share: Share) {
    validate(share)
    shares[share.name] = share
  }

  /** Publishes [share] with the declared set; rolls the declaration back on failure. */
  @Synchronized
  fun publish(share: Share, serviceKey: KeyFile, send: (Any?) -> Any?): List<Map<String, Any?>> {
    validate(share)
    val previous = shares.put(share.name, share)
    return try {
      synchronize(serviceKey, send)
    } catch (e: RuntimeException) {
      if (previous != null) shares[share.name] = previous else shares.remove(share.name)
      throw e
    }
  }

  /** Removes [shareName] and republishes the rest; restores it on failure. */
  @Synchronized
  fun remove(shareName: String, serviceKey: KeyFile, send: (Any?) -> Any?): List<Map<String, Any?>> {
    val removed = shares.remove(shareName) ?: throw IllegalArgumentException("Share '$shareName' not found")
    return try {
      synchronize(serviceKey, send)
    } catch (e: RuntimeException) {
      shares[shareName] = removed
      throw e
    }
  }

  /** Publishes the whole declared set again (after a reconnect). */
  @Synchronized
  fun republishAll(serviceKey: KeyFile, send: (Any?) -> Any?): List<Map<String, Any?>> = synchronize(serviceKey, send)

  /** Asks the local agent to attest [share]: `/challenge` returns a nonce, which the service key signs for `/attest`. */
  fun requestAttestation(share: Share, serviceKey: KeyFile): Map<String, Any?> {
    validate(share)
    val body = linkedMapOf<String, Any?>(
      "serviceKey" to serviceKey.publicKey,
      "shareName" to share.name,
      "protocol" to share.protocol,
      "target" to share.toMap()["target"],
    )
    val nonce = agentPost("/challenge", body, "challenge")["nonce"] as? String
    if (nonce.isNullOrEmpty()) throw AgentException("invalid-agent-response", "Hub Network agent returned an invalid challenge")
    body["signature"] = serviceKey.sign(nonce)
    val attestation = agentPost("/attest", body, "attestation")
    validateAttestation(attestation, share, serviceKey.publicKey)
    return attestation
  }

  private fun synchronize(serviceKey: KeyFile, send: (Any?) -> Any?): List<Map<String, Any?>> {
    val declared = shares.values.toList()
    val wire = declared.map { share ->
      share.toMap().apply {
        put("version", WIRE_VERSION)
        put("attestation", requestAttestation(share, serviceKey))
      }
    }
    val response = send(mapOf("shares" to wire))
    val list = (response as? Map<*, *>)?.get("shares") as? List<*>
      ?: throw IllegalStateException("Hub returned an invalid shares/replace response")
    if (list.size != declared.size) {
      throw IllegalStateException("Hub returned ${list.size} publications for ${declared.size} shares")
    }
    // Protocol v1 publications carry no share name: the hub keeps request order.
    val correlated = linkedMapOf<String, Map<String, Any?>>()
    val resourceIds = HashSet<String>()
    declared.forEachIndexed { index, share ->
      val publication = validatePublication(list[index], share, index)
      if (!resourceIds.add(publication["resourceId"] as String)) {
        throw IllegalStateException("Hub returned duplicate resourceId at share index $index")
      }
      correlated[share.name] = publication
    }
    publicationsByShare = correlated
    return correlated.values.toList()
  }

  @Suppress("UNCHECKED_CAST")
  private fun agentPost(path: String, body: Any?, operation: String): Map<String, Any?> {
    val socket = agentSocket
    val payload = defaultJsonCodec().encode(body).toByteArray(Charsets.UTF_8)
    val request = "POST $path HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n" +
      "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
    val timedOut = AtomicBoolean()
    val raw = try {
      SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
        val watchdog = Thread.ofVirtual().start {
          try {
            Thread.sleep(agentTimeoutMs)
            timedOut.set(true)
            channel.close()
          } catch (_: Exception) {
          }
        }
        try {
          channel.connect(UnixDomainSocketAddress.of(socket))
          channel.write(ByteBuffer.wrap(request.toByteArray(Charsets.US_ASCII)))
          channel.write(ByteBuffer.wrap(payload))
          val out = ByteArrayOutputStream()
          val buffer = ByteBuffer.allocate(8192)
          while (channel.read(buffer) >= 0) {
            out.write(buffer.array(), 0, buffer.position())
            buffer.clear()
          }
          out.toByteArray()
        } finally {
          watchdog.interrupt()
        }
      }
    } catch (e: Exception) {
      if (timedOut.get()) throw AgentException("agent-timeout", "Hub Network agent timed out after $agentTimeoutMs ms")
      throw AgentException("agent-unavailable", "Hub Network agent is unavailable at $socket")
    }
    val text = raw.toString(Charsets.UTF_8)
    val headerEnd = text.indexOf("\r\n\r\n")
    if (headerEnd < 0) throw AgentException("invalid-agent-response", "Hub Network agent returned an invalid response")
    val head = text.substring(0, headerEnd).split("\r\n")
    val code = head[0].split(" ").getOrNull(1)?.toIntOrNull() ?: 0
    var responseBody = text.substring(headerEnd + 4)
    if (head.any { it.lowercase(Locale.ROOT).startsWith("transfer-encoding:") && "chunked" in it.lowercase(Locale.ROOT) }) {
      responseBody = dechunk(responseBody)
    }
    if (code !in 200..299) {
      val detail = responseBody.trim()
      throw AgentException("agent-rejected", "Hub Network agent rejected $operation ($code)" + if (detail.isEmpty()) "" else ": $detail")
    }
    return try {
      defaultJsonCodec().decode(responseBody) as Map<String, Any?>
    } catch (e: Exception) {
      throw AgentException("invalid-agent-response", "Hub Network agent returned invalid JSON")
    }
  }

  companion object {
    /** Hub Network wire version. */
    const val WIRE_VERSION = 1
    private const val MAX_SHARE_NAME_LENGTH = 255

    /** `$HUB_NET_AGENT_SOCKET`, else the platform's hub-net agent socket. */
    fun defaultAgentSocket(): String {
      System.getenv("HUB_NET_AGENT_SOCKET")?.takeIf { it.isNotEmpty() }?.let { return it }
      val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
      return if ("linux" in os) "/run/hub-net/agent.sock" else "/var/run/hub-net/agent.sock"
    }

    /** Validates a share like the TS SDK: name, protocol, target and policy. */
    fun validate(share: Share) {
      requireText(share.name, "share name", MAX_SHARE_NAME_LENGTH)
      require(share.protocol == "tcp" || share.protocol == "udp") { "Invalid Hub Network protocol" }
      requireText(share.host, "target host")
      require(share.port in 1..65_535) { "Invalid Hub Network target port" }
      val policy = share.policy ?: return
      when (policy["mode"]) {
        "hub", "private", "disabled" -> {}
        "group" -> requireText(policy["group"], "policy group")
        "keys" -> {
          val keys = policy["keys"] as? List<*>
          require(!keys.isNullOrEmpty()) { "Invalid Hub Network policy keys" }
          keys.forEach { requireText(it, "policy key") }
        }
        else -> throw IllegalArgumentException("Invalid Hub Network policy mode")
      }
    }

    private fun requireText(value: Any?, field: String, maxLength: Int = Int.MAX_VALUE) {
      require(value is String && value.isNotBlank() && value.length <= maxLength && value.none { it < ' ' || it == '\u007f' }) {
        "Invalid Hub Network $field"
      }
    }

    @Suppress("UNCHECKED_CAST")
    private fun validatePublication(value: Any?, share: Share, index: Int): Map<String, Any?> {
      val map = value as? Map<String, Any?> ?: throw IllegalStateException("Invalid Hub publication at share index $index")
      if ((map["version"] as? Number)?.toInt() != WIRE_VERSION) {
        throw IllegalStateException("Invalid Hub publication version at share index $index")
      }
      val id = map["resourceId"] as? String
      if (id == null || !id.startsWith("hnr1_") || id.length == 5) {
        throw IllegalStateException("Invalid Hub publication resourceId at share index $index")
      }
      for (field in listOf("authority", "providerId", "overlayAddress")) {
        if ((map[field] as? String).isNullOrBlank()) {
          throw IllegalStateException("Invalid Hub publication $field at share index $index")
        }
      }
      if (map["protocol"] != share.protocol) throw IllegalStateException("Hub publication protocol mismatch at share index $index")
      val port = map["port"] as? Number
      if (port == null || port.toDouble() != port.toInt().toDouble() || port.toInt() !in 1..65_535) {
        throw IllegalStateException("Invalid Hub publication port at share index $index")
      }
      return map
    }

    private fun validateAttestation(attestation: Map<String, Any?>, share: Share, serviceKey: String) {
      val target = attestation["target"] as? Map<*, *> ?: emptyMap<String, Any?>()
      if ((attestation["version"] as? Number)?.toInt() != WIRE_VERSION ||
        attestation["serviceKey"] != serviceKey ||
        attestation["shareName"] != share.name ||
        attestation["protocol"] != share.protocol ||
        target["host"] != share.host ||
        (target["port"] as? Number)?.toInt() != share.port
      ) {
        throw AgentException("attestation-mismatch", "Local-agent attestation does not match the share")
      }
      val (issuedAt, expiresAt) = try {
        Instant.parse(attestation["issuedAt"].toString()) to Instant.parse(attestation["expiresAt"].toString())
      } catch (e: Exception) {
        throw AgentException("attestation-expired", "Local-agent attestation is expired")
      }
      val now = Instant.now()
      if (!expiresAt.isAfter(issuedAt) || issuedAt.isAfter(now.plusSeconds(30)) || !expiresAt.isAfter(now)) {
        throw AgentException("attestation-expired", "Local-agent attestation is expired")
      }
    }

    private fun dechunk(body: String): String {
      val out = StringBuilder()
      var index = 0
      while (index < body.length) {
        val lineEnd = body.indexOf("\r\n", index)
        if (lineEnd < 0) break
        val size = body.substring(index, lineEnd).substringBefore(';').trim().toInt(16)
        if (size == 0) break
        out.append(body, lineEnd + 2, lineEnd + 2 + size)
        index = lineEnd + 2 + size + 2
      }
      return out.toString()
    }
  }
}
