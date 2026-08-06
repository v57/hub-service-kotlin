package v57.hubservice

import java.io.File
import java.nio.file.Files
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeychainTest {

  @AfterTest
  fun resetKeychain() {
    // Leave the default keychain (cwd `auth`) untouched for other tests.
    Keychain.setKeyPath(null)
  }

  private fun tempAuth(content: String? = null): String {
    val dir = Files.createTempDirectory("hubservice-keychain")
    if (content != null) Files.writeString(dir.resolve("auth"), content)
    return dir.resolve("auth").toString()
  }

  private fun verifyToken(token: String): Pair<String, Boolean> {
    val parts = token.split(".")
    val pubDer = Base64.getDecoder().decode(parts[1])
    val pubKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(pubDer))
    val ok = Signature.getInstance("Ed25519").run {
      initVerify(pubKey)
      update(parts[3].toByteArray(Charsets.UTF_8))
      verify(Base64.getDecoder().decode(parts[2]))
    }
    return parts[1] to ok
  }

  @Test
  fun tokenShape() {
    Keychain.setKeyPath(tempAuth())
    val token = Keychain.sign()
    val parts = token.split(".")
    assertEquals(4, parts.size, "key.<pub>.<sig>.<expiry>")
    assertEquals("key", parts[0])
    assertTrue(parts[3].all { it.isDigit() }, "expiry is a decimal epoch string: ${parts[3]}")
    assertEquals(Keychain.publicKey(), parts[1], "pub part is the keychain public key")
  }

  @Test
  fun signatureVerifiesAgainstPublicKey() {
    Keychain.setKeyPath(tempAuth())
    val token = Keychain.sign(expires = 10)
    val (_, ok) = verifyToken(token)
    assertTrue(ok, "signature over the expiry string must verify against the pub key")
  }

  @Test
  fun authFileRoundTrip() {
    val path = tempAuth()
    Keychain.setKeyPath(path)
    val pub = Keychain.publicKey() // generates and writes the file
    assertTrue(File(path).exists(), "auth file written on first use")
    val raw = Base64.getDecoder().decode(File(path).readText().trim())
    assertEquals(92, raw.size, "48-byte PKCS8 private + 44-byte SPKI public")
    val embedded = Base64.getEncoder().encodeToString(raw.copyOfRange(48, 92))
    assertEquals(embedded, pub, "public key is bytes 48..92 of the file")
    val token = Keychain.sign()
    val (tokenPub, ok) = verifyToken(token)
    assertEquals(embedded, tokenPub)
    assertTrue(ok, "reloaded key signs tokens that verify against the file's pub key")
  }

  @Test
  fun loadsTsTestKey() {
    // TS-generated key from the hub-pro support contract (owner test key).
    val tsAuth = "MC4CAQAwBQYDK2VwBCIEINguo0cc0FACJ0oRV3V/+ZqIyj04ON5wdMJGH4a/do8CMCowBQYDK2VwAyEAHZSDpmbLRjLrOyNMugsStFqK5EYcNluUekE4di+FalQ="
    val tsPub = "MCowBQYDK2VwAyEAHZSDpmbLRjLrOyNMugsStFqK5EYcNluUekE4di+FalQ="
    Keychain.setKeyPath(tempAuth(tsAuth))
    assertEquals(tsPub, Keychain.publicKey(), "reproduces the TS public key")
    val token = Keychain.sign()
    val (tokenPub, ok) = verifyToken(token)
    assertEquals(tsPub, tokenPub)
    assertTrue(ok, "token signed by the TS test key")
  }

  @Test
  fun regeneratesAfterDelete() {
    val path = tempAuth()
    Keychain.setKeyPath(path)
    Keychain.publicKey() // generates
    assertTrue(File(path).delete())
    Keychain.setKeyPath(path) // force reload
    val pub = Keychain.publicKey()
    assertTrue(pub.isNotEmpty())
    assertTrue(File(path).exists(), "auth file regenerated when missing")
  }
}
