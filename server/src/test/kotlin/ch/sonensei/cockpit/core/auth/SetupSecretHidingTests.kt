package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.api.SetupRequest
import ch.sonensei.cockpit.core.auth.api.SetupResponse
import ch.sonensei.cockpit.core.auth.domain.SetupEnrollment
import ch.sonensei.cockpit.core.auth.domain.UserAccount
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant

/** Objects holding setup secrets must not reveal them through `toString` (e.g. in logs or error messages). */
class SetupSecretHidingTests {
	@Test
	fun `setup request hides the password but keeps the username`() {
		val text = SetupRequest("jane.doe", PASSWORD).toString()

		text shouldNotContain PASSWORD
		text shouldContain "jane.doe"
	}

	@Test
	fun `setup response and enrollment hide the TOTP secret, URI and recovery codes`() {
		val texts =
			listOf(
				SetupResponse(SECRET, URI, CODES).toString(),
				SetupEnrollment(SECRET, URI, CODES).toString(),
			)

		texts.forEach { text ->
			text shouldNotContain SECRET
			text shouldNotContain "otpauth"
			CODES.forEach { text shouldNotContain it }
		}
	}

	@Test
	fun `account and recovery code hide the password hash, TOTP secret and code hashes`() {
		val account = UserAccount("jane.doe", PASSWORD_HASH, EncryptedSecret(SECRET))
		account.addRecoveryCode(CODE_HASH)

		listOf(account.toString(), account.recoveryCodes.single().toString()).forEach { text ->
			text shouldNotContain PASSWORD_HASH
			text shouldNotContain SECRET
			text shouldNotContain CODE_HASH
		}
	}

	@Test
	fun `an account becomes active once, when its TOTP is confirmed`() {
		val account = UserAccount("jane.doe", PASSWORD_HASH, EncryptedSecret(SECRET))
		account.isActive shouldBe false

		account.confirmTotp(Instant.parse("2026-10-20T10:00:00Z"))

		account.isActive shouldBe true
		account.totpConfirmedAt shouldBe Instant.parse("2026-10-20T10:00:00Z")
		shouldThrow<IllegalStateException> { account.confirmTotp(Instant.parse("2026-10-20T10:01:00Z")) }
		account.totpConfirmedAt shouldBe Instant.parse("2026-10-20T10:00:00Z")
	}

	private companion object {
		const val PASSWORD = "correct-horse-battery-fictitious"
		const val PASSWORD_HASH = "\$argon2id\$v=19\$m=16384,t=2,p=1\$ZmljdGl0aW91cw\$ZmljdGl0aW91cy1oYXNo"
		const val CODE_HASH = "\$argon2id\$v=19\$m=16384,t=2,p=1\$Y29kZQ\$ZmljdGl0aW91cy1jb2Rl"
		const val SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
		const val URI = "otpauth://totp/My%20Cockpit:jane.doe?secret=$SECRET"
		val CODES = listOf("abcde-fghjk", "mnpqr-stuvw")
	}
}
