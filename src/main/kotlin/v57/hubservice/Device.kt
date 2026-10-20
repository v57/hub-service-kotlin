package v57.hubservice

import channel.defaultJsonCodec
import java.net.InetAddress
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * Device identity (Docs/Devices/protocol.md sections 1 and 3).
 *
 * The device key is one ed25519 key per OS instance, in the key file format,
 * at `$HUB_DEVICE_KEY`, `/var/lib/hub/device.key` for root, or
 * `~/.hub/device.key` (directory 0700, file 0600, created atomically). It
 * signs a short certificate for each service key it runs, sent in the
 * `device` connection header next to `auth`.
 */
object Device {
  /** Default certificate lifetime: 7 days. */
  const val CERTIFICATE_LIFETIME = 7L * 24 * 3600

  /** A certificate with less than this left is renewed at connect: 24 hours. */
  const val RENEW_BEFORE = 24L * 3600

  /** The device key location: `$HUB_DEVICE_KEY`, root's system path, or `~/.hub/device.key`. */
  fun defaultKeyPath(): Path {
    System.getenv("HUB_DEVICE_KEY")?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
    if (System.getProperty("user.name") == "root" && defaultPlatform() != "windows") {
      return Path.of("/var/lib/hub/device.key")
    }
    return Path.of(System.getProperty("user.home"), ".hub", "device.key")
  }

  /** Loads the device key at [path], creating it (0600, atomic) if absent. */
  fun loadOrCreateKey(path: Path): KeyFile = KeyFile.loadOrCreate(path, secure = true)

  /** The device id: the first 32 lowercase hex characters of SHA-256 of the SPKI DER. */
  fun id(publicKey: String): String = hex(publicKey).substring(0, 32)

  /** The safety code shown while approving: `"ddd ddd"`. */
  fun safetyCode(publicKey: String): String {
    val code = "%06d".format(hex(publicKey).substring(0, 8).toLong(16) % 1_000_000)
    return "${code.substring(0, 3)} ${code.substring(3)}"
  }

  /** The suggested device name: the computer name on macOS, else the host name without `.local`. */
  fun defaultName(): String? {
    if (defaultPlatform() == "macos") {
      runCatching {
        val process = ProcessBuilder("scutil", "--get", "ComputerName").redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        if (process.waitFor() == 0 && output.isNotEmpty()) return output
      }
    }
    val name = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotEmpty() }
      ?: System.getenv("HOSTNAME")?.takeIf { it.isNotEmpty() }
      ?: return null
    return name.removeSuffix(".local")
  }

  /** The certificate `platform` for this OS. */
  fun defaultPlatform(): String {
    val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
    return when {
      "mac" in os -> "macos"
      "linux" in os -> if ("android" in System.getProperty("java.vendor", "").lowercase(Locale.ROOT)) "android" else "linux"
      "windows" in os -> "windows"
      else -> "other"
    }
  }

  /**
   * The certificate payload, fields in protocol order with optional ones
   * omitted when null: `v, device, service, instance, deviceName, platform,
   * parent, iat, exp`.
   */
  fun certificatePayload(
    devicePublicKey: String,
    servicePublicKey: String,
    instance: String,
    deviceName: String?,
    platform: String?,
    parent: String?,
    iat: Long,
    exp: Long,
  ): Map<String, Any?> {
    val payload = linkedMapOf<String, Any?>(
      "v" to 1,
      "device" to devicePublicKey,
      "service" to servicePublicKey,
      "instance" to instance,
    )
    if (deviceName != null) payload["deviceName"] = deviceName
    if (platform != null) payload["platform"] = platform
    if (parent != null) payload["parent"] = parent
    payload["iat"] = iat
    payload["exp"] = exp
    return payload
  }

  /** Signs a certificate: `<base64url(JSON)>.<base64 signature>`, the signature covering the payload string. */
  fun certificate(deviceKey: KeyFile, payload: Map<String, Any?>): String {
    val json = defaultJsonCodec().encode(payload)
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    return "$encoded.${deviceKey.sign(encoded)}"
  }

  /** Decodes the payload of a `device` header (no signature check). */
  @Suppress("UNCHECKED_CAST")
  fun decodeCertificate(header: String): Map<String, Any?> {
    val encoded = header.substringBefore('.')
    val json = Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
    return defaultJsonCodec().decode(json) as Map<String, Any?>
  }

  private fun hex(publicKey: String): String =
    MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(publicKey))
      .joinToString("") { "%02x".format(it) }
}
