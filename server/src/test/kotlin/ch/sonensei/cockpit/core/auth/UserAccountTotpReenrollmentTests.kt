package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.domain.UserAccount
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The entity's own guard of the TOTP re-enrollment (SPEC.md §10.2): a working TOTP secret is never replaced this way. */
class UserAccountTotpReenrollmentTests {
	private fun account() = UserAccount("jane.doe", "fictitious-hash", EncryptedSecret("ORIGINALSECRET"))

	@Test
	fun `an account that does not re-enroll can neither replace nor confirm its TOTP secret this way`() {
		val account = account()

		shouldThrow<IllegalStateException> { account.startTotpReenrollment(EncryptedSecret("NEWSECRET")) }
		shouldThrow<IllegalStateException> { account.confirmTotpReenrollment() }

		account.totpSecret.reveal() shouldBe "ORIGINALSECRET"
		account.totpReenrollRequired shouldBe false
	}
}
