package v57.hubservice

import channel.Channel
import channel.ListenOptions
import channel.defaultJsonCodec
import channel.server.listen
import java.io.ByteArrayOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Share publication against a fake hub-net agent (Unix socket) and a fake hub. */
class NetworkTest {
  // Unix socket paths are short (104 bytes on macOS), so stay out of the deep per-user temp dir.
  private val tmp: Path = Files.createTempDirectory(Path.of("/tmp"), "hsk")
  private val agent: ServerSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
  private val agentRequests = CopyOnWriteArrayList<MutableMap<String, Any?>>()

  /** Lets a test tamper with the attestation the fake agent returns. */
  @Volatile
  private var attestationEdit: (MutableMap<String, Any?>) -> Unit = {}

  @BeforeTest
  fun startAgent() {
    agent.bind(UnixDomainSocketAddress.of(tmp.resolve("agent.sock")))
    Thread { serveAgent() }.apply { isDaemon = true }.start()
    Keychain.setKeyPath(tmp.resolve("auth").toString())
  }

  @AfterTest
  fun stopAgent() {
    agent.close()
    Keychain.setKeyPath(null)
    tmp.toFile().deleteRecursively()
  }

  @Suppress("UNCHECKED_CAST")
  private fun serveAgent() {
    while (agent.isOpen) {
      runCatching {
        agent.accept().use { connection ->
          val out = ByteArrayOutputStream()
          val buffer = ByteBuffer.allocate(8192)
          var text: String
          while (true) {
            connection.read(buffer)
            out.write(buffer.array(), 0, buffer.position())
            buffer.clear()
            text = out.toString(Charsets.UTF_8)
            val end = text.indexOf("\r\n\r\n")
            if (end >= 0) {
              val length = Regex("(?i)content-length: *(\\d+)").find(text)!!.groupValues[1].toInt()
              if (text.length - end - 4 >= length) break
            }
          }
          val path = text.split(" ")[1]
          val request = (defaultJsonCodec().decode(text.substringAfter("\r\n\r\n")) as Map<String, Any?>).toMutableMap()
          request["_path"] = path
          agentRequests += request
          val body = if (path == "/challenge") {
            """{"type":"attest_response","nonce":"nonce-1"}"""
          } else {
            val attestation = linkedMapOf(
              "version" to 1, "attestationId" to "att-1", "serviceKey" to request["serviceKey"], "key" to "agent-key",
              "shareName" to request["shareName"], "protocol" to request["protocol"], "target" to request["target"],
              "issuedAt" to Instant.now().toString(), "expiresAt" to Instant.now().plusSeconds(300).toString(),
              "signature" to "agent-signature",
            )
            attestationEdit(attestation)
            defaultJsonCodec().encode(attestation)
          }
          val bytes = body.toByteArray(Charsets.UTF_8)
          connection.write(ByteBuffer.wrap("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray()))
          connection.write(ByteBuffer.wrap(bytes))
        }
      }
    }
  }

  private fun publisher() = NetworkPublisher().apply { agentSocket = tmp.resolve("agent.sock").toString() }

  private fun publications(body: Any?): Map<String, Any?> {
    val shares = (body as Map<*, *>)["shares"] as List<*>
    return mapOf(
      "shares" to shares.mapIndexed { i, share ->
        share as Map<*, *>
        mapOf(
          "version" to 1, "resourceId" to "hnr1_${share["name"]}", "authority" to "a$i", "providerId" to "p",
          "protocol" to share["protocol"], "overlayAddress" to "100.96.0.2", "port" to 40000 + i,
        )
      },
    )
  }

  @Test
  fun publishesAttestedSharesInNameOrder() {
    val key = Keychain.key()
    val sent = CopyOnWriteArrayList<Any?>()
    val network = publisher()
    network.publish(NetworkPublisher.Share("web", "tcp", "127.0.0.1", 8080, mapOf("mode" to "hub")), key) {
      sent += it
      publications(it)
    }
    val result = network.publish(NetworkPublisher.Share("db", "tcp", "localhost", 5432), key) {
      sent += it
      publications(it)
    }

    // The agent's JSON decoder refuses unknown fields: exactly these go out.
    assertEquals(setOf("_path", "serviceKey", "shareName", "protocol", "target"), agentRequests[0].keys)
    val attest = agentRequests[1]
    assertEquals(setOf("_path", "serviceKey", "shareName", "protocol", "target", "signature"), attest.keys)
    assertEquals(key.publicKey, attest["serviceKey"])
    assertTrue(verifies(key.publicKey, "nonce-1", attest["signature"] as String))

    val wire = (sent[1] as Map<*, *>)["shares"] as List<*>
    assertEquals(listOf("db", "web"), wire.map { (it as Map<*, *>)["name"] })
    val first = wire[0] as Map<*, *>
    assertEquals(1, first["version"])
    assertEquals("att-1", (first["attestation"] as Map<*, *>)["attestationId"])
    assertEquals(2, result.size)
    assertEquals("hnr1_web", network.publication("web")?.get("resourceId"))
  }

  @Test
  fun mismatchedAttestationIsRefusedAndRolledBack() {
    attestationEdit = { it["shareName"] = "other" }
    val network = publisher()
    val error = assertFailsWith<NetworkPublisher.AgentException> {
      network.publish(NetworkPublisher.Share("web", "tcp", "127.0.0.1", 8080), Keychain.key(), ::publications)
    }
    assertEquals("attestation-mismatch", error.code)
    assertEquals(emptyList(), network.declaredShares)
  }

  @Test
  fun expiredAttestationIsRefused() {
    attestationEdit = { it["expiresAt"] = Instant.now().minusSeconds(1).toString() }
    val error = assertFailsWith<NetworkPublisher.AgentException> {
      publisher().publish(NetworkPublisher.Share("web", "tcp", "127.0.0.1", 8080), Keychain.key(), ::publications)
    }
    assertEquals("attestation-expired", error.code)
  }

  @Test
  fun missingAgentIsReported() {
    val network = NetworkPublisher().apply { agentSocket = tmp.resolve("none.sock").toString() }
    val error = assertFailsWith<NetworkPublisher.AgentException> {
      network.publish(NetworkPublisher.Share("web", "tcp", "127.0.0.1", 8080), Keychain.key(), ::publications)
    }
    assertEquals("agent-unavailable", error.code)
  }

  @Test
  fun invalidSharesAndPublicationsAreRefused() {
    assertFailsWith<IllegalArgumentException> { NetworkPublisher.validate(NetworkPublisher.Share("", "tcp", "h", 1)) }
    assertFailsWith<IllegalArgumentException> { NetworkPublisher.validate(NetworkPublisher.Share("a", "sctp", "h", 1)) }
    assertFailsWith<IllegalArgumentException> { NetworkPublisher.validate(NetworkPublisher.Share("a", "tcp", "h", 70000)) }
    assertFailsWith<IllegalArgumentException> {
      NetworkPublisher.validate(NetworkPublisher.Share("a", "tcp", "h", 1, mapOf("mode" to "keys")))
    }
    val error = assertFailsWith<IllegalStateException> {
      publisher().publish(NetworkPublisher.Share("web", "tcp", "127.0.0.1", 8080), Keychain.key()) { mapOf("shares" to emptyList<Any>()) }
    }
    assertTrue(error.message!!.contains("0 publications for 1 shares"))
  }

  @Test
  fun declaredSharesArePublishedOnConnect() {
    val replaced = CopyOnWriteArrayList<Any?>()
    val hub = Channel<Map<String, String>>()
      .post<Any?, Any?, Unit>("hub/service/update") { null }
      .post<Any?, Any?, Unit>("network/shares/replace") { req ->
        replaced += req.body
        publications(req.body)
      }
    val server = hub.listen(0, ListenOptions(state = { it }))
    val service = Service(address = "ws://127.0.0.1:${server.port()}", device = false)
    service.network.agentSocket = tmp.resolve("agent.sock").toString()
    service.share("web", "tcp", "127.0.0.1", 8080)
    try {
      service.start()
      val deadline = System.currentTimeMillis() + 10_000
      while (replaced.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
      assertEquals(1, replaced.size)
      assertEquals("hnr1_web", service.network.publication("web")?.get("resourceId"))

      assertEquals(1, service.updateShare("web", mapOf("mode" to "hub"), 9090).size)
      assertEquals(9090, service.network.declaredShares[0].port)
      assertEquals(emptyList(), service.removeShare("web"))
    } finally {
      service.stop()
      server.stop()
    }
  }
}
