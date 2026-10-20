package v57.hubservice

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeviceTest {
  private val tmp: Path = Files.createTempDirectory("hubservice-device")

  @AfterTest
  fun reset() {
    Keychain.setKeyPath(null)
  }

  private fun service(
    name: String? = null,
    instance: String? = null,
    deviceName: String? = "box",
    platform: String? = null,
    parent: String? = null,
    device: Boolean = true,
    deviceKey: KeyFile? = null,
  ): Service {
    Keychain.setKeyPath(tmp.resolve("auth").toString())
    return Service(
      address = 4100, name = name, instance = instance, deviceName = deviceName, platform = platform,
      parent = parent, device = device, deviceKey = deviceKey,
      deviceKeyPath = tmp.resolve("dev/device.key").toString(),
    )
  }

  @Test
  fun createsDeviceKeyWithPrivateModes() {
    val path = tmp.resolve("hub/device.key")
    val key = Device.loadOrCreateKey(path)
    assertEquals(124, Files.readString(path).length)
    assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path.parent)))
    assertEquals(key.publicKey, Device.loadOrCreateKey(path).publicKey)
    Files.list(path.parent).use { assertEquals(1, it.count(), "no temp file left behind") }
  }

  @Test
  fun connectHeadersCarryASignedCertificate() {
    val service = service(name = "Echo", deviceName = "Mac Studio", platform = "macos")
    val headers = service.authHeaderMap()
    val header = headers.getValue("device")
    val parts = header.split(".")
    assertEquals(2, parts.size)
    val payload = Device.decodeCertificate(header)
    val deviceKey = assertNotNull(service.currentDeviceKey).publicKey
    assertTrue(verifies(deviceKey, parts[0], parts[1]), "signature covers the payload string")
    assertEquals(1, (payload["v"] as Number).toInt())
    assertEquals(deviceKey, payload["device"])
    assertEquals(Keychain.publicKey(), payload["service"])
    assertEquals(headers.getValue("auth").split(".")[1], payload["service"])
    assertEquals("Echo", payload["instance"])
    assertEquals("Mac Studio", payload["deviceName"])
    assertEquals("macos", payload["platform"])
    assertFalse("parent" in payload)
    val iat = (payload["iat"] as Number).toLong()
    val exp = (payload["exp"] as Number).toLong()
    assertEquals(Device.CERTIFICATE_LIFETIME, exp - iat)
    assertTrue(abs(iat - Keychain.now()) <= 2)
    // The raw header lines carry it too.
    assertTrue(service.authHeaders().contains("\r\ndevice: $header"))
  }

  @Test
  fun certificateIsReusedUntilRenewal() {
    val service = service()
    assertSame(service.authHeaderMap()["device"], service.authHeaderMap()["device"])
  }

  @Test
  fun instanceDefaults() {
    assertEquals("s3-local/Backup Disk", service(name = "Backup", instance = "s3-local/Backup Disk").instance())
    assertEquals("Backup", service(name = "Backup").instance())
    val byPath = service()
    assertEquals("service", byPath.instance())
    byPath.post("s3/read") { it }
    byPath.post("other/x") { it }
    assertEquals("s3", byPath.instance())
  }

  @Test
  fun parentIsDeclared() {
    val service = service(deviceName = "builder", platform = "linux", parent = "Mac Studio")
    assertEquals("Mac Studio", Device.decodeCertificate(service.authHeaderMap().getValue("device"))["parent"])
  }

  @Test
  fun deviceModeCanBeTurnedOff() {
    assertNull(service(device = false).authHeaderMap()["device"])
    assertFalse(Files.exists(tmp.resolve("dev/device.key")))
  }

  @Test
  fun callerProvidedKeyIsUsed() {
    val key = KeyFile.generate()
    val header = service(deviceKey = key).authHeaderMap().getValue("device")
    assertEquals(key.publicKey, Device.decodeCertificate(header)["device"])
    assertFalse(Files.exists(tmp.resolve("dev/device.key")))
  }

  @Test
  fun unreadableDeviceKeyTurnsDeviceModeOff() {
    val path = tmp.resolve("bad.key")
    Files.writeString(path, "garbage")
    Keychain.setKeyPath(tmp.resolve("auth").toString())
    val headers = Service(deviceKeyPath = path.toString()).authHeaderMap()
    assertNull(headers["device"])
    assertNotNull(headers["auth"])
  }
}
