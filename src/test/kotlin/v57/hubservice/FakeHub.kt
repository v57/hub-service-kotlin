package v57.hubservice

import channel.Channel
import channel.ListenOptions
import channel.Sender
import channel.Server
import channel.defaultJsonCodec
import channel.server.listen
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A channel server standing in for hub-server: it answers `hub/info` as told and
 * records every connection's headers. Its tokens are checked by the tests. The
 * channel-kotlin server is pumped by whoever waits in the process, here the service.
 */
class FakeHub(
  answer: (nonce: String, challenge: String) -> Any? = proving("hub"),
  setup: (Channel<Map<String, String>>) -> Unit = {},
) {
  val connects = CopyOnWriteArrayList<Map<String, String>>()
  val updates = CopyOnWriteArrayList<Map<String, String>>()
  val nonces = CopyOnWriteArrayList<String>()
  val latest = AtomicReference<Sender<Map<String, String>>>()
  private val asked = AtomicInteger()
  private val server: Server<Map<String, String>>

  init {
    val hub = Channel<Map<String, String>>()
      .post<Any?, Any?, Unit>("hub/info") { req ->
        val nonce = nonce(req.body)
        nonces += nonce
        answer(nonce, "challenge-${asked.incrementAndGet()}")
      }
      .post<Any?, Any?, Unit>("hub/service/update") { req ->
        updates += req.state
        null
      }
    setup(hub)
    server = hub.listen(
      0,
      ListenOptions(
        state = { headers -> headers.toMap().also { connects += it } },
        onConnect = { _, sender -> latest.set(sender) },
      ),
    )
  }

  val address: String get() = "ws://127.0.0.1:${server.port()}"

  /** The connections that carried credentials. */
  fun credentialsSent(): List<Map<String, String>> = connects.filter { "auth" in it || "device" in it }

  fun stop() = server.stop()

  companion object {
    private val vectors = defaultJsonCodec().decode(
      FakeHub::class.java.getResource("/device-vectors.json")!!.readText(),
    ) as Map<*, *>

    /** The vectors' key [name]. */
    fun key(name: String): KeyFile =
      KeyFile.parse(((vectors["keys"] as Map<*, *>)[name] as Map<*, *>)["keyFile"] as String)

    /** A hub proving the vectors' key [name]. */
    fun proving(name: String): (String, String) -> Any? {
      val key = key(name)
      return { nonce, challenge -> proof(key, key.publicKey, nonce, challenge) }
    }

    /** A `hub/info` answer claiming [claimed], signed with [key]. */
    fun proof(key: KeyFile, claimed: String, nonce: String, challenge: String): Map<String, Any?> =
      mapOf(
        "key" to claimed, "protocol" to 2, "challenge" to challenge,
        "proof" to key.sign(HubProof.signedData(claimed, nonce)),
      )

    /** The nonce in a `hub/info` body. */
    fun nonce(body: Any?): String = (body as? Map<*, *>)?.get("nonce") as? String ?: ""
  }
}
