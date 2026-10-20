package ch.sonensei.cockpit.core.crypto

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.boot.context.annotation.UserConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.Base64

class MasterKeyTests {
	private val validKey = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

	@Test
	fun `a base64 32-byte key is accepted`() {
		MasterKey.decode(validKey).size shouldBe 32
		MasterKey.decode("  $validKey\n").size shouldBe 32
	}

	@Test
	fun `missing key fails with a clear message`() {
		listOf(null, "", "   ").forEach { value ->
			val error = shouldThrow<InvalidMasterKeyException> { MasterKey.decode(value) }
			error.message!! shouldContain "COCKPIT_MASTER_KEY is not set"
			error.message!! shouldContain "openssl rand -base64 32"
		}
	}

	@Test
	fun `invalid key fails without echoing the value`() {
		val notBase64 = shouldThrow<InvalidMasterKeyException> { MasterKey.decode("secret-but-not-base64!") }
		notBase64.message!! shouldContain "not valid base64"
		notBase64.message!! shouldNotContain "secret-but"

		val short = Base64.getEncoder().encodeToString(ByteArray(16) { 9 })
		val tooShort = shouldThrow<InvalidMasterKeyException> { MasterKey.decode(short) }
		tooShort.message!! shouldContain "must decode to 32 bytes, but has 16"
		tooShort.message!! shouldNotContain short
	}

	@Test
	fun `context with a valid key provides the cipher`() {
		contextRunner.withPropertyValues("cockpit.master-key=$validKey").run { context ->
			context.startupFailure shouldBe null
			val cipher = context.getBean(SecretCipher::class.java)
			cipher.decrypt(cipher.encrypt("x")) shouldBe "x"
		}
	}

	@Test
	fun `context fails to start without a key`() {
		contextRunner.run { context ->
			context.startupFailure.shouldBeInvalidKey("COCKPIT_MASTER_KEY is not set")
		}
	}

	@Test
	fun `context fails to start with an invalid key`() {
		contextRunner.withPropertyValues("cockpit.master-key=AAAA").run { context ->
			context.startupFailure.shouldBeInvalidKey("must decode to 32 bytes, but has 3")
		}
	}

	@Test
	fun `failure analyzer explains how to fix the key`() {
		val cause = InvalidMasterKeyException("COCKPIT_MASTER_KEY is not set.")
		val analysis = InvalidMasterKeyFailureAnalyzer().analyze(RuntimeException("bean creation", cause))!!

		analysis.description shouldContain "COCKPIT_MASTER_KEY is not set."
		analysis.action shouldContain "Provide COCKPIT_MASTER_KEY"
	}

	private val contextRunner =
		ApplicationContextRunner().withConfiguration(UserConfigurations.of(CryptoConfiguration::class.java))

	private fun Throwable?.rootCause(): Throwable? = generateSequence(this) { it.cause }.lastOrNull()

	private fun Throwable?.shouldBeInvalidKey(expectedMessagePart: String) {
		this.rootCause().shouldBeInstanceOf<InvalidMasterKeyException>().message!! shouldContain expectedMessagePart
	}
}
