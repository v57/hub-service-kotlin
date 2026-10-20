package v57.hubservice

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * An ed25519 key in the hub key file format (Docs/Devices/protocol.md
 * section 1): base64 of the 48-byte PKCS8 DER private key followed by the
 * 44-byte SPKI DER public key (92 bytes). Service keys (`auth`) and the
 * device key (`~/.hub/device.key`) both use it.
 */
class KeyFile private constructor(private val data: ByteArray) {
  private val privateKey: PrivateKey

  init {
    require(data.size == PRIVATE_DER_LENGTH + PUBLIC_DER_LENGTH) {
      "expected ${PRIVATE_DER_LENGTH + PUBLIC_DER_LENGTH} bytes, got ${data.size}"
    }
    privateKey = KeyFactory.getInstance("Ed25519")
      .generatePrivate(PKCS8EncodedKeySpec(data.copyOfRange(0, PRIVATE_DER_LENGTH)))
  }

  /** The key file text: base64 of the 92 bytes. */
  val text: String get() = Base64.getEncoder().encodeToString(data)

  /** The public key: base64 of the 44-byte SPKI DER. */
  val publicKey: String get() = Base64.getEncoder().encodeToString(publicDer)

  /** The 44-byte SPKI DER public key. */
  val publicDer: ByteArray get() = data.copyOfRange(PRIVATE_DER_LENGTH, PRIVATE_DER_LENGTH + PUBLIC_DER_LENGTH)

  /** Base64 ed25519 signature over the UTF-8 bytes of [text]. */
  fun sign(text: String): String {
    val signature = Signature.getInstance("Ed25519").run {
      initSign(privateKey)
      update(text.toByteArray(Charsets.UTF_8))
      sign()
    }
    return Base64.getEncoder().encodeToString(signature)
  }

  companion object {
    const val PRIVATE_DER_LENGTH = 48
    const val PUBLIC_DER_LENGTH = 44

    /** Parses the key file text (base64, surrounding whitespace ignored). */
    fun parse(text: String): KeyFile = KeyFile(Base64.getDecoder().decode(text.trim()))

    /** A fresh random key. */
    fun generate(): KeyFile {
      val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      // getEncoded(): PKCS8 DER private (48 bytes) + SPKI DER public (44 bytes).
      return KeyFile(pair.private.encoded + pair.public.encoded)
    }

    /** Reads the key file at [path]. */
    fun load(path: Path): KeyFile = try {
      parse(Files.readString(path))
    } catch (e: Exception) {
      throw IllegalArgumentException("bad key file $path: ${e.message}", e)
    }

    /**
     * Reads the key file at [path], creating it if absent. With [secure], the
     * parent directory is created 0700 and the file written 0600 to a temp
     * file renamed into place (device key rules, protocol.md section 3).
     */
    fun loadOrCreate(path: Path, secure: Boolean): KeyFile {
      if (Files.exists(path)) return load(path)
      val key = generate()
      if (secure) writeAtomic(path, key.text, secure = true) else Files.writeString(path, key.text)
      return key
    }

    /** Writes [text] to a temp file beside [path] and renames it into place. */
    internal fun writeAtomic(path: Path, text: String, secure: Boolean) {
      val parent = path.toAbsolutePath().parent
      if (!Files.exists(parent)) {
        Files.createDirectories(parent)
        if (secure) setPermissions(parent, "rwx------")
      }
      val temp = Files.createTempFile(parent, ".${path.fileName}", ".tmp")
      try {
        if (secure) setPermissions(temp, "rw-------")
        Files.writeString(temp, text)
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } finally {
        Files.deleteIfExists(temp)
      }
    }

    private fun setPermissions(path: Path, permissions: String) {
      // Not a POSIX file system (Windows): rely on the default ACLs.
      runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions)) }
    }
  }
}
