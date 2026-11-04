package v57.hubservice

import java.nio.file.Path

/**
 * ed25519 keychain: load-or-generate the `auth` key file and sign tokens.
 *
 * hub-server auth parity with the TS reference (hub-service/keychain.ts) and
 * Docs/Devices/protocol.md section 2:
 *
 * - The key file (`auth` in the working directory, or $HUBKEY) is the base64
 *   encoding of the 48-byte PKCS8 DER ed25519 private key followed by the
 *   44-byte SPKI DER public key (92 bytes total, 124 base64 chars).
 * - A signed token has the shape `key.<pub>.<sig>.<expiry>.<nonce>` where:
 *     pub     = base64 of the 44-byte SPKI public key (bytes 48..92)
 *     expiry  = DECIMAL epoch-seconds string of now + 30
 *     nonce   = the challenge the hub answered in `hub/info`
 *     sig     = base64 ed25519 signature over
 *               `hub1:<hub SPKI>:<nonce>:<expiry>`, for one connection to one
 *               hub. hub-server allows 30 s of clock skew and at most 60 s ahead.
 * - The hub verifies the signature against the embedded public key and
 *   treats the key as the connection identity (owner/host/group).
 *
 * Crypto backend: the JDK's built-in Ed25519 (JEP 339).
 */
object Keychain {
  /** The `v` header next to `auth`. */
  const val AUTH_VERSION = "1"

  /** Default token lifetime in seconds (protocol.md: now + 30). */
  const val DEFAULT_EXPIRES = 30

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

  /** A fresh auth token for one connection to the hub [hubKey] (its SPKI), over its [nonce]. */
  fun signHub(hubKey: String, nonce: String, expires: Int = DEFAULT_EXPIRES): String =
    token(key(), hubKey, nonce, now() + expires)

  /** An auth token with [key] over `hub1:<hubKey>:<nonce>:<expiry>`. */
  fun token(key: KeyFile, hubKey: String, nonce: String, expiry: Long): String {
    val data = expiry.toString()
    return "key.${key.publicKey}.${key.sign("hub1:$hubKey:$nonce:$data")}.$data.$nonce"
  }

  internal fun now(): Long = System.currentTimeMillis() / 1000
}
