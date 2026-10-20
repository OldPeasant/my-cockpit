package ch.sonensei.cockpit.core.crypto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.Base64

class SecretCipherTests {
	private val key = ByteArray(32) { it.toByte() }
	private val cipher = SecretCipher(key)

	@Test
	fun `round trip returns the plaintext`() {
		listOf("imap-password-fictitious", "", "pässwörd ✓ 🔑", "x".repeat(10_000)).forEach { secret ->
			cipher.decrypt(cipher.encrypt(secret)) shouldBe secret
		}
	}

	@Test
	fun `ciphertext uses the versioned format and does not contain the plaintext`() {
		val encrypted = cipher.encrypt("refresh-token-fictitious")
		val bytes = Base64.getDecoder().decode(encrypted)

		bytes[0] shouldBe SecretCipher.VERSION_1
		bytes.size shouldBe 1 + SecretCipher.NONCE_BYTES + "refresh-token-fictitious".length + SecretCipher.TAG_BITS / 8
		encrypted shouldNotContain "refresh-token"
		String(bytes, Charsets.ISO_8859_1) shouldNotContain "refresh-token"
	}

	@Test
	fun `each encryption uses a fresh random nonce`() {
		val first = Base64.getDecoder().decode(cipher.encrypt("same"))
		val second = Base64.getDecoder().decode(cipher.encrypt("same"))

		first.copyOfRange(1, 13).toList() shouldNotBe second.copyOfRange(1, 13).toList()
		first.toList() shouldNotBe second.toList()
	}

	@Test
	fun `tampered ciphertext is rejected`() {
		val bytes = Base64.getDecoder().decode(cipher.encrypt("imap-password-fictitious"))
		// Flip one bit in the nonce, the ciphertext body and the tag.
		listOf(1, 1 + SecretCipher.NONCE_BYTES, bytes.size - 1).forEach { index ->
			val tampered = bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
			shouldThrow<SecretDecryptionException> {
				cipher.decrypt(Base64.getEncoder().encodeToString(tampered))
			}
		}
	}

	@Test
	fun `wrong key is rejected`() {
		val encrypted = cipher.encrypt("imap-password-fictitious")
		val other = SecretCipher(ByteArray(32) { (it + 1).toByte() })

		val error = shouldThrow<SecretDecryptionException> { other.decrypt(encrypted) }
		error.message!! shouldNotContain "imap-password"
	}

	@Test
	fun `unknown version, truncated and non-base64 input are rejected`() {
		val bytes = Base64.getDecoder().decode(cipher.encrypt("x"))
		val otherVersion = bytes.copyOf().also { it[0] = 2 }

		shouldThrow<SecretDecryptionException> { cipher.decrypt(Base64.getEncoder().encodeToString(otherVersion)) }
		shouldThrow<SecretDecryptionException> { cipher.decrypt(Base64.getEncoder().encodeToString(bytes.copyOf(20))) }
		shouldThrow<SecretDecryptionException> { cipher.decrypt("not base64 !") }
		shouldThrow<SecretDecryptionException> { cipher.decrypt("") }
	}

	@Test
	fun `key must be 32 bytes`() {
		shouldThrow<IllegalArgumentException> { SecretCipher(ByteArray(16)) }
	}
}
