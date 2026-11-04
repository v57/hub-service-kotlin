package v57.hubservice

import channel.CallResult
import channel.Channel
import channel.ConnectOptions
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * How a connection to an address travels (Docs/Devices/protocol.md section 2).
 * [REFUSED] is plain `ws://` beyond loopback, the overlay and the LAN: it isn't
 * dialed unless `allowPlaintext` says so.
 */
enum class HubTransport(val label: String) {
  ENCRYPTED("encrypted"),
  LOOPBACK("loopback"),
  OVERLAY("overlay"),
  LOCAL_NETWORK("localNetwork"),
  REFUSED("refused");

  companion object {
    private val ADDRESS = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*)://(?:[^/?#@]*@)?(\[[^\]]*\]|[^/?#:]*)""")

    /** Classifies an address (a port or a URL) before anything is dialed. */
    fun of(address: Any): HubTransport {
      if (address is Int) return LOOPBACK
      var text = address.toString().trim()
      if (text.isNotEmpty() && text.all { it.isDigit() }) return LOOPBACK
      // The client dials an address without a scheme as ws://.
      if (!text.contains("://")) text = "ws://$text"
      val match = ADDRESS.find(text) ?: return REFUSED
      val scheme = match.groupValues[1].lowercase()
      val host = match.groupValues[2].lowercase().removeSuffix(".")
      if (scheme == "wss" || scheme == "https" || scheme == "quic") return ENCRYPTED
      if (scheme != "ws" && scheme != "http") return REFUSED
      if (host.startsWith("[")) {
        val v6 = host.substring(1, host.length - 1)
        return when {
          v6 == "::1" -> LOOPBACK
          Regex("^(f[cd]|fe[89ab])").containsMatchIn(v6) -> LOCAL_NETWORK
          else -> REFUSED
        }
      }
      val v4 = ipv4(host)
      if (v4 != null) {
        val (a, b) = v4
        return when {
          a == 127 -> LOOPBACK
          a == 100 && b in 64..127 -> OVERLAY
          a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) -> LOCAL_NETWORK
          else -> REFUSED
        }
      }
      return when {
        host == "localhost" || host.endsWith(".localhost") -> LOOPBACK
        host.endsWith(".hub.invalid") -> OVERLAY
        host.endsWith(".local") -> LOCAL_NETWORK
        else -> REFUSED
      }
    }

    private fun ipv4(host: String): List<Int>? {
      val parts = host.split(".")
      if (parts.size != 4 || !parts.all { it.length in 1..3 && it.all(Char::isDigit) }) return null
      return parts.map { it.toInt() }.takeIf { bytes -> bytes.all { it <= 255 } }
    }
  }
}

/** An answer to `hub/info` with a nonce. */
data class HubInfo(val key: String?, val proof: String?, val challenge: String?) {
  /**
   * Whether the answer proves its key over [nonce]. A relayed answer passes
   * too, so it never decides which credentials a hub gets.
   */
  fun proves(nonce: String): Boolean {
    if (key == null || proof == null || challenge == null) return false
    return try {
      val public = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key)))
      Signature.getInstance("Ed25519").run {
        initVerify(public)
        update(HubProof.signedData(key, nonce).toByteArray(Charsets.UTF_8))
        verify(Base64.getDecoder().decode(proof))
      }
    } catch (e: Exception) {
      false
    }
  }
}

/**
 * Auth v2's client side (Docs/Devices/protocol.md section 2): before every
 * connect the SDK asks `hub/info` on an anonymous connection with a fresh nonce,
 * and sends its token only if the hub proved the key it claims, over that nonce.
 */
object HubProof {
  private val random = SecureRandom()

  /** The nonce for one `hub/info`: 32 fresh random bytes in base64url. */
  fun makeClientNonce(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

  /** The string a `hub/info` proof signs. */
  fun signedData(hubKey: String, nonce: String): String = "hubinfo1:$hubKey:$nonce"

  /**
   * Asks the hub at [address] for its key and a challenge, on a short anonymous
   * connection, and returns the answer only with a valid proof. Both the connect
   * and the call end at [timeoutMillis], or when the thread is interrupted.
   */
  internal fun ask(address: Any, timeoutMillis: Long): HubInfo {
    val channel = Channel<Any?>().requestTimeout(timeoutMillis)
    val options = ConnectOptions<Any?>(connectTimeoutMillis = timeoutMillis)
    val client = if (address is Int) channel.connect(address, options) else channel.connect(address.toString(), options)
    try {
      val nonce = makeClientNonce()
      val value = when (val result = client.send(Any::class.java, "hub/info", mapOf("nonce" to nonce), Unit)) {
        is CallResult.Ok -> result.value
        is CallResult.RemoteErr -> throw IllegalStateException(result.error)
      }
      val map = value as? Map<*, *>
      val info = HubInfo(map?.get("key") as? String, map?.get("proof") as? String, map?.get("challenge") as? String)
      check(info.proves(nonce)) { "the hub didn't prove its key" }
      return info
    } finally {
      runCatching { client.stop() }
    }
  }
}
