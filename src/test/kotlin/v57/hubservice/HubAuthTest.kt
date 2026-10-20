package v57.hubservice

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

class HubAuthTest {
  private val tmp: Path = Files.createTempDirectory("hubservice-auth")

  @AfterTest
  fun reset() {
    Keychain.setKeyPath(null)
  }

  private fun useTempKey() = Keychain.setKeyPath(tmp.resolve("auth").toString())

  @Test
  fun signHubSignsHubBoundString() {
    useTempKey()
    val parts = Keychain.signHub(HUB_KEY).split(".")
    assertEquals(4, parts.size)
    assertTrue(verifies(parts[1], "hub1:$HUB_KEY:${parts[3]}", parts[2]))
    assertFalse(verifies(parts[1], parts[3], parts[2]), "a v1 token must not verify as v0")
  }

  @Test
  fun connectsWithV0UntilAHubKeyIsKnown() {
    useTempKey()
    val service = Service(address = 4100, device = false)
    assertNull(service.hubKey)
    assertEquals("0", service.authHeaderMap()["v"])
  }

  @Test
  fun hubKeyOptionSignsV1() {
    useTempKey()
    val headers = Service(address = 4100, hubKey = HUB_KEY, device = false).authHeaderMap()
    assertEquals("1", headers["v"])
    val parts = headers.getValue("auth").split(".")
    assertTrue(verifies(parts[1], "hub1:$HUB_KEY:${parts[3]}", parts[2]))
  }

  @Test
  fun pinnedKeyBesideKeyFileIsUsedAndForgotten() {
    useTempKey()
    HubPins(tmp.resolve("hubs.json")).put("ws://localhost:4100", HUB_KEY)
    assertEquals("{\"ws://localhost:4100\":\"$HUB_KEY\"}", Files.readString(tmp.resolve("hubs.json")))

    val service = Service(address = 4100, device = false)
    assertEquals(HUB_KEY, service.hubKey)
    assertEquals("1", service.authHeaderMap()["v"])
    assertNull(Service(address = "ws://other:1997", device = false).hubKey)

    service.forgetHubKey()
    assertNull(service.hubKey)
    assertEquals("0", service.authHeaderMap()["v"])
    assertEquals("{}", Files.readString(tmp.resolve("hubs.json")))
  }

  @Test
  fun addressesNormalizeLikeChannelTs() {
    assertEquals("ws://localhost:1997", Service.normalizeAddress(1997))
    assertEquals("ws://localhost:1997", Service.normalizeAddress("1997"))
    assertEquals("wss://hub.example.com/path", Service.normalizeAddress("WSS://Hub.Example.COM/path/"))
    assertEquals("ws://127.0.0.1:1997", Service.normalizeAddress("ws://127.0.0.1:1997/"))
  }

  @Test
  fun customSignStaysV0() {
    val headers = Service(hubKey = HUB_KEY, sign = { "key.custom" }).authHeaderMap()
    assertEquals("key.custom", headers["auth"])
    assertEquals("0", headers["v"])
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
