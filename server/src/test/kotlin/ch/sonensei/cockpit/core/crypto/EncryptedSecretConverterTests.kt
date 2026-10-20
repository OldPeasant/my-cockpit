package ch.sonensei.cockpit.core.crypto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class EncryptedSecretConverterTests {
	private val cipher = SecretCipher(ByteArray(32) { (it * 3).toByte() })
	private val converter = EncryptedSecretConverter(cipher)

	@Test
	fun `column value is ciphertext that converts back to the secret`() {
		val column = converter.convertToDatabaseColumn(EncryptedSecret("caldav-password-fictitious"))!!

		column shouldNotContain "caldav-password"
		cipher.decrypt(column) shouldBe "caldav-password-fictitious"
		converter.convertToEntityAttribute(column) shouldBe EncryptedSecret("caldav-password-fictitious")
	}

	@Test
	fun `the same secret is stored differently each time`() {
		val secret = EncryptedSecret("caldav-password-fictitious")

		converter.convertToDatabaseColumn(secret) shouldNotBe converter.convertToDatabaseColumn(secret)
	}

	@Test
	fun `null converts to null in both directions`() {
		converter.convertToDatabaseColumn(null) shouldBe null
		converter.convertToEntityAttribute(null) shouldBe null
	}

	@Test
	fun `column written with another master key is rejected`() {
		val foreign = EncryptedSecretConverter(SecretCipher(ByteArray(32) { 1 }))
		val column = foreign.convertToDatabaseColumn(EncryptedSecret("caldav-password-fictitious"))

		val error = shouldThrow<SecretDecryptionException> { converter.convertToEntityAttribute(column) }
		error.message!! shouldNotContain "caldav-password"
	}
}
