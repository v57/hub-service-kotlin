package v57.hubservice

import channel.CallResult
import channel.Channel
import channel.ListenOptions
import channel.Sender
import channel.server.listen
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** End to end against a fake hub: hub/info pinning, the v1 reconnect, the device header and caller identity. */
class FakeHubTest {
  private val tmp: Path = Files.createTempDirectory("hubservice-fakehub")
  private var service: Service? = null

  @AfterTest
  fun reset() {
    service?.stop()
    Keychain.setKeyPath(null)
  }

  private fun await(condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (!condition()) {
      if (System.currentTimeMillis() > deadline) throw AssertionError("timed out")
      Thread.sleep(10)
    }
  }

  @Test
  fun pinsHubKeyReconnectsWithV1AndSeesTheCaller() {
    val connects = CopyOnWriteArrayList<Map<String, String>>()
    val updates = CopyOnWriteArrayList<Map<String, String>>()
    val latest = AtomicReference<Sender<Map<String, String>>>()
    val hub = Channel<Map<String, String>>()
      .post<Unit, Any?, Unit>("hub/info") {
        mapOf("key" to HUB_KEY, "protocol" to 1, "pro" to true, "mode" to "default-allow")
      }
      .post<Any?, Any?, Unit>("hub/service/update") { req ->
        updates += req.state
        null
      }
    val server = hub.listen(
      0,
      ListenOptions(
        state = { headers -> headers.toMap().also { connects += it } },
        onConnect = { _, sender -> latest.set(sender) },
      ),
    )
    try {
      Keychain.setKeyPath(tmp.resolve("auth").toString())
      val address = "ws://127.0.0.1:${server.port()}"
      val s = Service(
        address = address,
        name = "Echo",
        deviceName = "Mac Studio",
        deviceKeyPath = tmp.resolve("device.key").toString(),
      )
      service = s
      s.post("echo/caller") { _, request ->
        val caller = assertNotNull(request.caller)
        mapOf(
          "key" to caller.key, "device" to caller.deviceName, "owner" to caller.owner,
          "hubKey" to request.hubKey, "service" to request.context["service"],
        )
      }
      s.start()

      // First connect v0 → hub/info → pin → reconnect with v1, then the update.
      await { updates.isNotEmpty() }
      assertTrue(connects.size >= 2, "expected a v0 connect then a v1 connect, got $connects")
      assertEquals("0", connects[0]["v"])
      val v1 = updates.last()
      assertEquals("1", v1["v"])
      val auth = v1.getValue("auth").split(".")
      assertTrue(verifies(auth[1], "hub1:$HUB_KEY:${auth[3]}", auth[2]))
      assertEquals("{\"$address\":\"$HUB_KEY\"}", Files.readString(tmp.resolve("hubs.json")))
      assertEquals(HUB_KEY, s.hubKey)

      val payload = Device.decodeCertificate(assertNotNull(v1["device"]))
      assertEquals(auth[1], payload["service"])
      assertEquals("Echo", payload["instance"])

      val context = mapOf(
        "service" to "s1",
        "hub" to mapOf(
          "caller" to mapOf(
            "key" to auth[1],
            "device" to mapOf("id" to Device.id(payload["device"] as String), "name" to "Mac Studio"),
            "groups" to listOf("owners"),
            "owner" to true,
          ),
          "hubKey" to HUB_KEY,
        ),
      )
      val result = latest.get().send(Any::class.java, "echo/caller", null, context)
      assertEquals(
        mapOf("key" to auth[1], "device" to "Mac Studio", "owner" to true, "hubKey" to HUB_KEY, "service" to "s1"),
        (result as? CallResult.Ok)?.value ?: result.toString(),
      )
    } finally {
      server.stop()
    }
  }
}
