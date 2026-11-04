package v57.hubservice

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

const val HUB_KEY = "MCowBQYDK2VwAyEAiojj3XQJ8ZX9UtstPLpdcspnCb8dlBIb83SIAbQPb1w="

fun verifies(publicKey: String, data: String, signature: String): Boolean {
  val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
  return Signature.getInstance("Ed25519").run {
    initVerify(key)
    update(data.toByteArray(Charsets.UTF_8))
    verify(Base64.getDecoder().decode(signature))
  }
}

fun await(condition: () -> Boolean) {
  val deadline = System.currentTimeMillis() + 10_000
  while (!condition()) {
    if (System.currentTimeMillis() > deadline) throw AssertionError("timed out")
    Thread.sleep(10)
  }
}

class HubAuthTest {
  private val tmp: Path = Files.createTempDirectory("hubservice-auth")
  private var service: Service? = null
  private var hub: FakeHub? = null

  @AfterTest
  fun reset() {
    service?.stop()
    hub?.stop()
    Keychain.setKeyPath(null)
  }

  private fun useTempKey() = Keychain.setKeyPath(tmp.resolve("auth").toString())

  private fun started(
    hub: FakeHub,
    sign: ((String, String) -> String)? = null,
    device: Boolean = false,
    name: String? = null,
  ): Service {
    useTempKey()
    this.hub = hub
    return Service(
      address = hub.address, sign = sign, device = device, name = name,
      deviceName = "Mac Studio", deviceKeyPath = tmp.resolve("device.key").toString(),
    ).also {
      service = it
      it.start()
    }
  }

  @Test
  fun signHubSignsItsChallenge() {
    useTempKey()
    val parts = Keychain.signHub(HUB_KEY, "nonce-1").split(".")
    assertEquals(5, parts.size)
    assertEquals("nonce-1", parts[4])
    assertTrue(verifies(parts[1], "hub1:$HUB_KEY:nonce-1:${parts[3]}", parts[2]))
  }

  @Test
  fun sendsATokenOnlyAfterProofAndPinsTheKey() {
    val hub = FakeHub()
    val s = started(hub, device = true, name = "Echo")
    await { hub.updates.isNotEmpty() }

    // hub/info went on an anonymous connection before the service's own.
    assertEquals(2, hub.connects.size)
    assertFalse("auth" in hub.connects[0])
    assertFalse("device" in hub.connects[0])
    assertFalse("v" in hub.connects[0])
    val headers = hub.connects[1]
    assertEquals("1", headers["v"])
    assertTrue("device" in headers)
    val auth = headers.getValue("auth").split(".")
    assertEquals("challenge-1", auth[4])
    assertTrue(verifies(auth[1], "hub1:$HUB_KEY:challenge-1:${auth[3]}", auth[2]))

    assertEquals(HUB_KEY, s.hubKey)
    assertEquals("{\"${hub.address}\":\"$HUB_KEY\"}", Files.readString(tmp.resolve("hubs.json")))
    assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(hub.nonces[0]), hub.nonces[0])
  }

  @Test
  fun callsMadeRightAfterStartWaitForTheConnection() {
    val hub = FakeHub(setup = { it.post<Any?, Any?, Unit>("ping") { "pong" } })
    val s = started(hub)
    assertEquals("pong", s.send("ping"))
  }

  @Test
  fun everyConnectGetsItsOwnNonceAndChallenge() {
    val hub = FakeHub()
    val first = started(hub)
    await { hub.updates.size == 1 }
    first.stop()
    val second = started(hub)
    await { hub.updates.size == 2 }
    second.stop()
    assertEquals(2, hub.nonces.size)
    assertNotEquals(hub.nonces[0], hub.nonces[1])
    val one = hub.updates[0].getValue("auth").split(".")
    val two = hub.updates[1].getValue("auth").split(".")
    assertEquals(5, one.size)
    assertNotEquals(one[4], two[4])
    assertEquals(HUB_KEY, HubPins(tmp.resolve("hubs.json")).get(hub.address))
  }

  @Test
  fun reconnectsAskAgain() {
    val hub = FakeHub()
    started(hub)
    await { hub.updates.size == 1 }
    hub.latest.get().stop()
    await { hub.updates.size == 2 }
    assertEquals(2, hub.nonces.size)
    assertNotEquals(hub.updates[0]["auth"], hub.updates[1]["auth"])
  }

  private fun getsNothing(answer: (String, String) -> Any?) {
    val hub = FakeHub(answer)
    val s = started(hub)
    await { hub.nonces.isNotEmpty() }
    Thread.sleep(1500)
    assertEquals(emptyList(), hub.credentialsSent())
    assertEquals(emptyList(), hub.updates.toList())
    assertNull(s.hubKey)
    assertFalse(Files.exists(tmp.resolve("hubs.json")))
    assertTrue(s.lastError!!.contains("didn't prove"), s.lastError)
  }

  @Test
  fun aHubWithoutProofGetsNothing() =
    getsNothing { _, challenge -> mapOf("key" to HUB_KEY, "protocol" to 2, "challenge" to challenge) }

  @Test
  fun aHubClaimingTheRealKeyButSigningWithItsOwnGetsNothing() {
    val own = FakeHub.key("service")
    getsNothing { nonce, challenge -> FakeHub.proof(own, HUB_KEY, nonce, challenge) }
  }

  @Test
  fun aHubProvingAnotherKeyThanThePinGetsNothing() {
    val hub = FakeHub(FakeHub.proving("service"))
    useTempKey()
    HubPins(tmp.resolve("hubs.json")).put(hub.address, HUB_KEY)
    val s = started(hub)
    await { hub.nonces.isNotEmpty() }
    Thread.sleep(1500)
    assertEquals(emptyList(), hub.credentialsSent())
    assertEquals(HUB_KEY, s.hubKey)
    assertEquals(HUB_KEY, HubPins(tmp.resolve("hubs.json")).get(hub.address))
    assertTrue(s.lastError!!.contains("not the pinned"), s.lastError)
  }

  @Test
  fun stopEndsAWaitingHandshakeAndItsCalls() {
    val hub = FakeHub({ _, _ -> mapOf("key" to HUB_KEY) })
    val s = started(hub)
    val call = CompletableFuture.supplyAsync {
      try {
        s.send("ping")
      } catch (error: IllegalStateException) {
        error.message
      }
    }
    await { hub.nonces.isNotEmpty() }
    val stoppedAt = System.currentTimeMillis()
    s.stop()
    assertEquals("Service not started", call.get(1, TimeUnit.SECONDS))
    assertTrue(System.currentTimeMillis() - stoppedAt < 1000)
  }

  @Test
  fun aPublicPlainAddressIsNotDialed() {
    useTempKey()
    val s = Service(address = "ws://hub.example.com:1997", device = false).also { service = it }
    s.start()
    assertTrue(assertFailsWith<IllegalStateException> { s.send("x") }.message!!.contains("unencrypted"))
    assertTrue(assertFailsWith<IllegalStateException> { s.values("x") }.message!!.contains("unencrypted"))
  }

  @Test
  fun allowPlaintextDialsARefusedAddress() {
    useTempKey()
    // The option lets a refused address through, to a name that goes nowhere.
    val s = Service(address = "ws://hub.invalid:1", device = false, allowPlaintext = true).also { service = it }
    s.start()
    await { s.lastError != null }
    assertFalse(s.lastError!!.contains("unencrypted"), s.lastError)
  }

  @Test
  fun pinnedKeyBesideKeyFileIsUsedAndForgotten() {
    useTempKey()
    HubPins(tmp.resolve("hubs.json")).put("ws://localhost:4100", HUB_KEY)
    assertEquals("{\"ws://localhost:4100\":\"$HUB_KEY\"}", Files.readString(tmp.resolve("hubs.json")))

    val service = Service(address = 4100, device = false)
    assertEquals(HUB_KEY, service.hubKey)
    assertNull(Service(address = "ws://other:1997", device = false).hubKey)

    service.forgetHubKey()
    assertNull(service.hubKey)
    assertEquals("{}", Files.readString(tmp.resolve("hubs.json")))
  }

  @Test
  fun hubKeyOptionIsThePin() {
    useTempKey()
    val service = Service(address = 4100, hubKey = HUB_KEY, device = false)
    assertEquals(HUB_KEY, service.hubKey)
    service.forgetHubKey()
    assertNull(service.hubKey)
  }

  @Test
  fun addressesNormalizeLikeChannelTs() {
    assertEquals("ws://localhost:1997", Service.normalizeAddress(1997))
    assertEquals("ws://localhost:1997", Service.normalizeAddress("1997"))
    assertEquals("wss://hub.example.com/path", Service.normalizeAddress("WSS://Hub.Example.COM/path/"))
    assertEquals("ws://127.0.0.1:1997", Service.normalizeAddress("ws://127.0.0.1:1997/"))
  }

  @Test
  fun customSignGetsTheProvenKeyAndTheChallenge() {
    val hub = FakeHub()
    started(hub, sign = { key, challenge -> "key.custom.$key.$challenge" }, device = true)
    await { hub.updates.isNotEmpty() }
    val headers = hub.updates[0]
    assertEquals("key.custom.$HUB_KEY.challenge-1", headers["auth"])
    assertEquals("1", headers["v"])
    assertNull(headers["device"])
  }

  @Test
  fun customSignDoesNotRunWithoutProof() {
    val signed = AtomicInteger()
    val hub = FakeHub({ _, challenge -> mapOf("key" to HUB_KEY, "challenge" to challenge) })
    started(hub, sign = { _, _ ->
      signed.incrementAndGet()
      "key.custom"
    })
    await { hub.nonces.isNotEmpty() }
    Thread.sleep(300)
    assertEquals(0, signed.get())
    assertEquals(emptyList(), hub.credentialsSent())
  }

  @Test
  fun corruptPinFileCountsAsEmpty() {
    Files.writeString(tmp.resolve("hubs.json"), "not json")
    val pins = HubPins(tmp.resolve("hubs.json"))
    assertNull(pins.get("ws://localhost:1997"))
    pins.put("ws://localhost:1997", HUB_KEY)
    assertEquals(HUB_KEY, pins.get("ws://localhost:1997"))
  }
}
