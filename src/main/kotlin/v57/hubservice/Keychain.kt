package v57.hubservice

import java.nio.file.Path

/**
 * ed25519 keychain: load-or-generate the `auth` key file and sign tokens.
 *
 * Hub-pro auth parity with the TS reference (hub-service/keychain.ts) and
 * Docs/Devices/protocol.md section 2:
 *
 * - The key file (`auth` in the working directory, or $HUBKEY) is the base64
 *   encoding of the 48-byte PKCS8 DER ed25519 private key followed by the
 *   44-byte SPKI DER public key (92 bytes total, 124 base64 chars).
 * - A signed token has the shape `key.<pub>.<sig>.<expiry>` where:
 *     pub     = base64 of the 44-byte SPKI public key (bytes 48..92)
 *     expiry  = DECIMAL epoch-seconds string of now + 60
 *     sig     = base64 ed25519 signature over `<expiry>` (header `v: 0`) or
 *               over `hub1:<hub SPKI>:<expiry>` (header `v: 1`, bound to
 *               one hub). hub-pro allows 30 s of clock skew and at most
 *               300 s ahead.
 * - The hub verifies the signature against the embedded public key and
 *   treats the key as the connection identity (owner/host/group).
 *
 * Crypto backend: the JDK's built-in Ed25519 (JEP 339).
 */
object Keychain {
  /** Default token lifetime in seconds (protocol.md: now + 60). */
  const val DEFAULT_EXPIRES = 60

  @Volatile
  private var keyPath: String? = null

  @Volatile
  private var cached: KeyFile? = null

  /** Override the key file location (default: `auth` in the cwd, or $HUBKEY). */
  fun setKeyPath(path: String?) {
    keyPath = path
    cached = null
  }

  /** The key file location in use. Hub key pins (`hubs.json`) live beside it. */
  fun keyPath(): String = keyPath ?: System.getenv("HUBKEY")?.takeIf { it.isNotEmpty() } ?: "auth"

  /** The service key, loaded or generated on first use. */
  @Synchronized
  fun key(): KeyFile = cached ?: KeyFile.loadOrCreate(Path.of(keyPath()), secure = false).also { cached = it }

  /** The service's public key: base64 of the 44-byte SPKI DER public key. */
  fun publicKey(): String = key().publicKey

  /** A fresh v0 auth token: `key.<pub>.<sig>.<expiry>`. */
  fun sign(expires: Int = DEFAULT_EXPIRES): String = token(key(), null, now() + expires)

  /** A fresh v1 auth token bound to [hubKey] (the hub's SPKI). */
  fun signHub(hubKey: String, expires: Int = DEFAULT_EXPIRES): String = token(key(), hubKey, now() + expires)

  /**
   * An auth token with [key] expiring at [expiry]: v1 (over
   * `hub1:<hubKey>:<expiry>`) when [hubKey] is set, else v0 (over `<expiry>`).
   */
  fun token(key: KeyFile, hubKey: String?, expiry: Long): String {
    val data = expiry.toString()
    val signed = if (hubKey != null) "hub1:$hubKey:$data" else data
    return "key.${key.publicKey}.${key.sign(signed)}.$data"
  }

  internal fun now(): Long = System.currentTimeMillis() / 1000
}
