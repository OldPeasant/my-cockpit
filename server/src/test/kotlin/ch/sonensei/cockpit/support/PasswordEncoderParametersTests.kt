package ch.sonensei.cockpit.support

import ch.sonensei.cockpit.core.auth.infra.SecurityConfiguration
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/**
 * Spring tests hash passwords with [FastPasswordHashing.FAST_ENCODER]; this keeps the production encoder's
 * parameters (SecurityConfiguration.passwordEncoder) and its compatibility with the fast hashes covered.
 */
class PasswordEncoderParametersTests {
	private val production = SecurityConfiguration().passwordEncoder()

	@Test
	fun `production passwords are Argon2id hashes with Spring Security's recommended parameters`() {
		val hash = production.encode("correct horse battery staple")!!

		hash shouldStartWith "\$argon2id\$v=19\$m=16384,t=2,p=1\$"
		production.matches("correct horse battery staple", hash) shouldBe true
		production.matches("wrong horse", hash) shouldBe false
	}

	@Test
	fun `the fast test encoder writes Argon2id hashes that the production encoder verifies`() {
		val hash = FastPasswordHashing.FAST_ENCODER.encode("correct horse battery staple")!!

		hash shouldStartWith "\$argon2id\$v=19\$m=8,t=1,p=1\$"
		production.matches("correct horse battery staple", hash) shouldBe true
	}
}
