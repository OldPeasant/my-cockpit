package ch.sonensei.cockpit.core.crypto

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class EncryptedSecretTests {
	private val secret = EncryptedSecret("imap-password-fictitious")

	@Test
	fun `toString never reveals the value`() {
		secret.toString() shouldBe "EncryptedSecret(******)"
		"$secret" shouldNotContain "imap-password"
		Holder("me@example.test", secret).toString() shouldNotContain "imap-password"
	}

	@Test
	fun `Jackson serialization never reveals the value`() {
		val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

		mapper.writeValueAsString(secret) shouldBe "\"******\""
		val json = mapper.writeValueAsString(Holder("me@example.test", secret))
		json shouldBe """{"user":"me@example.test","password":"******"}"""
	}

	@Test
	fun `reveal returns the value and equality is by value`() {
		secret.reveal() shouldBe "imap-password-fictitious"
		secret shouldBe EncryptedSecret("imap-password-fictitious")
		secret.hashCode() shouldBe EncryptedSecret("imap-password-fictitious").hashCode()
		secret shouldNotBe EncryptedSecret("other")
	}

	data class Holder(
		val user: String,
		val password: EncryptedSecret,
	)
}
