package v57.hubservice

import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * ed25519 keychain: load-or-generate the `auth` key file and sign tokens.
 *
 * Hub-pro auth parity with the TS reference (hub-service/keychain.ts):
 *
 * - The key file (`auth` in the working directory, matching the TS keychain)
 *   is the base64 encoding of the 48-byte PKCS8 DER ed25519 private key
 *   followed by the 44-byte SPKI DER public key (92 bytes total, 124 base64
 *   chars).
 * - A signed token has the shape `key.<pub>.<sig>.<expiry>` where:
 *     pub     = base64 of the 44-byte SPKI public key (bytes 48..92)
 *     sig     = base64 ed25519 signature over the expiry string
 *     expiry  = DECIMAL epoch-seconds string of now + 10 (TS parity;
 *               hub-pro reads it as base-36, so the decimal form never
 *               trips the expiry check)
 * - The hub verifies the signature against the embedded public key and
 *   treats the key as the connection identity (owner/host/group).
 *
 * Crypto backend: the JDK's built-in Ed25519 (JEP 339) — KeyPairGenerator,
 * KeyFactory, and Signature all support the "Ed25519" algorithm.
 */
object Keychain {
  // TS keychain layout: PKCS8 DER private key (48 bytes for ed25519)
  // followed by SPKI DER public key (44 bytes for ed25519).
  private const val PRIVATE_DER_LENGTH = 48
  private const val PUBLIC_DER_LENGTH = 44

  @Volatile
  private var keyPath: String? = null

  @Volatile
  private var cached: Pair<PrivateKey, String>? = null

  /** Override the key file location (default: `auth` in the cwd, or $HUBKEY). */
  fun setKeyPath(path: String?) {
    keyPath = path
    cached = null
  }

  fun keyPath(): String = keyPath ?: System.getenv("HUBKEY") ?: "auth"

  /** The service's public key: base64 of the 44-byte SPKI DER public key. */
  fun publicKey(): String = load().second

  /** A fresh auth token: `key.<pub>.<sig>.<expiry>`. */
  fun sign(expires: Int = 10): String {
    val (privateKey, pubB64) = load()
    val expiry = (System.currentTimeMillis() / 1000 + expires).toString()
    val signature = Signature.getInstance("Ed25519").run {
      initSign(privateKey)
      update(expiry.toByteArray(Charsets.UTF_8))
      sign()
    }
    return "key.$pubB64.${Base64.getEncoder().encodeToString(signature)}.$expiry"
  }

  private fun load(): Pair<PrivateKey, String> {
    cached?.let { return it }
    val file = File(keyPath())
    val bytes = if (file.exists()) {
      val raw = Base64.getDecoder().decode(file.readText().trim())
      require(raw.size >= PRIVATE_DER_LENGTH + PUBLIC_DER_LENGTH) {
        "bad auth file $file: expected ${PRIVATE_DER_LENGTH + PUBLIC_DER_LENGTH} bytes, got ${raw.size}"
      }
      raw
    } else {
      generate(file)
    }
    val privateKey = KeyFactory.getInstance("Ed25519")
      .generatePrivate(PKCS8EncodedKeySpec(bytes.copyOfRange(0, PRIVATE_DER_LENGTH)))
    val pubB64 = Base64.getEncoder()
      .encodeToString(bytes.copyOfRange(PRIVATE_DER_LENGTH, PRIVATE_DER_LENGTH + PUBLIC_DER_LENGTH))
    Pair(privateKey, pubB64).also { cached = it }
    return cached!!
  }

  private fun generate(file: File): ByteArray {
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    // getEncoded(): PKCS8 DER private (48 bytes) + SPKI DER public (44 bytes).
    val bytes = pair.private.encoded + pair.public.encoded
    file.writeText(Base64.getEncoder().encodeToString(bytes))
    return bytes
  }
}
