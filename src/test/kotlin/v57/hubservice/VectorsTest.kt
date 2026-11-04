package v57.hubservice

import channel.defaultJsonCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The device layer test vectors (Docs/Devices/vectors.json, copied to
 * src/test/resources/device-vectors.json). Ed25519 is deterministic, so the
 * exact strings must match.
 */
class VectorsTest {
  private val vectors = defaultJsonCodec().decode(
    VectorsTest::class.java.getResource("/device-vectors.json")!!.readText(),
  ) as Map<*, *>

  private fun at(vararg path: String): Map<*, *> =
    path.fold(vectors as Any?) { value, key -> (value as Map<*, *>)[key] } as Map<*, *>

  private fun key(name: String) = KeyFile.parse(at("keys", name)["keyFile"] as String)

  private fun publicKey(name: String) = at("keys", name)["publicKey"] as String

  @Test
  fun keyFilesGiveThePublicKeys() {
    for (name in listOf("hub", "service", "device")) assertEquals(publicKey(name), key(name).publicKey, name)
  }

  @Test
  fun deviceIdAndSafetyCode() {
    val device = at("keys", "device")
    assertEquals(device["deviceId"], Device.id(publicKey("device")))
    assertEquals(device["safetyCode"], Device.safetyCode(publicKey("device")))
  }

  @Test
  fun authTokenMatches() {
    val expiry = (at("auth")["expiry"] as Number).toLong()
    val nonce = at("auth")["nonce"] as String
    val service = key("service")
    val hubKey = publicKey("hub")

    val v1 = at("auth", "v1")
    assertEquals("hub1:$hubKey:$nonce:$expiry", v1["signedData"])
    assertEquals(v1["signature"], service.sign(v1["signedData"] as String))
    assertEquals(v1["header"], Keychain.token(service, hubKey, nonce, expiry))
  }

  @Test
  fun hubInfoProof() {
    val vector = at("hubInfo")
    val hubKey = publicKey("hub")
    val nonce = vector["nonce"] as String
    val proof = vector["proof"] as String
    assertEquals(vector["signedData"], HubProof.signedData(hubKey, nonce))
    assertTrue(HubInfo(hubKey, proof, "c").proves(nonce))
    assertFalse(HubInfo(hubKey, proof, "c").proves("another-nonce-another-nonce"))
    assertFalse(HubInfo(publicKey("service"), proof, "c").proves(nonce))
    assertFalse(HubInfo(hubKey, null, "c").proves(nonce))
    assertFalse(HubInfo(hubKey, proof, null).proves(nonce))
    assertFalse(HubInfo(hubKey, "not base64!", "c").proves(nonce))
    assertFalse(HubInfo("not a key", proof, "c").proves(nonce))
  }

  @Test
  fun transports() {
    @Suppress("UNCHECKED_CAST")
    val cases = vectors["transport"] as List<Map<String, Any?>>
    assertTrue(cases.isNotEmpty())
    for (case in cases) {
      assertEquals(case["transport"], HubTransport.of(case["url"] as String).label, case["url"] as String)
    }
    assertEquals(HubTransport.LOOPBACK, HubTransport.of(1997))
    assertEquals(HubTransport.LOOPBACK, HubTransport.of("1997"))
    assertEquals(HubTransport.REFUSED, HubTransport.of("hub:1997"))
  }

  @Test
  fun certificate() = checkCertificate(at("certificate"), "s3-local/Backup Disk", "Mac Studio", "macos", null)

  @Test
  fun childCertificateWithParent() = checkCertificate(at("childCertificate"), "builder", "builder", "linux", "Mac Studio")

  private fun checkCertificate(vector: Map<*, *>, instance: String, deviceName: String, platform: String, parent: String?) {
    val device = key("device")
    val payload = Device.certificatePayload(
      device.publicKey, key("service").publicKey, instance, deviceName, platform, parent, 1792500000L, 1793104800L,
    )
    assertEquals(vector["json"], defaultJsonCodec().encode(payload))
    val header = Device.certificate(device, payload)
    assertEquals(vector["header"], header)
    assertEquals("${vector["payload"]}.${vector["signature"]}", header)
    assertEquals(vector["signature"], device.sign(vector["payload"] as String))
    assertEquals(defaultJsonCodec().decode(vector["json"] as String), Device.decodeCertificate(header))
  }

  @Test
  fun callerContext() {
    val context = at("caller", "context")
    val caller = Caller.fromContext(context)!!
    assertEquals(publicKey("service"), caller.key)
    assertEquals(at("keys", "device")["deviceId"], caller.deviceId)
    assertEquals("Mac Studio", caller.deviceName)
    assertEquals(listOf("owners"), caller.groups)
    assertTrue(caller.owner)
    assertEquals(publicKey("hub"), Caller.hubKey(context))
  }
}
