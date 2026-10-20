package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.api.LoginRequest
import ch.sonensei.cockpit.core.auth.domain.AuthSession
import ch.sonensei.cockpit.core.auth.domain.CockpitPrincipal
import ch.sonensei.cockpit.core.auth.domain.LoginCredentials
import ch.sonensei.cockpit.core.auth.domain.RecoveryCode
import ch.sonensei.cockpit.core.auth.domain.SessionGrant
import ch.sonensei.cockpit.core.auth.domain.UserAccount
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Login objects holding secrets must not reveal them through `toString` (logs, error messages), plus the small domain
 * rules of sessions and recovery codes.
 */
class LoginSecretHidingTests {
	@Test
	fun `login request and credentials hide the password and both second factors`() {
		val texts =
			listOf(
				LoginRequest(USERNAME, PASSWORD, TOTP_CODE, RECOVERY_CODE, rememberDevice = true).toString(),
				LoginCredentials(USERNAME, PASSWORD, TOTP_CODE, RECOVERY_CODE, rememberDevice = true).toString(),
			)

		texts.forEach { text ->
			text shouldContain USERNAME
			text shouldNotContain PASSWORD
			text shouldNotContain TOTP_CODE
			text shouldNotContain RECOVERY_CODE
		}
	}

	@Test
	fun `a session grant hides its token`() {
		val text = SessionGrant(TOKEN, rememberDevice = false, CockpitPrincipal(UUID.randomUUID(), USERNAME)).toString()

		text shouldNotContain TOKEN
		text shouldContain USERNAME
	}

	@Test
	fun `a recovery code can be consumed only once`() {
		val code = RecoveryCode(account(), "fictitious-hash")
		code.isUsed shouldBe false

		code.markUsed(NOW)

		code.isUsed shouldBe true
		code.usedAt shouldBe NOW
		shouldThrow<IllegalStateException> { code.markUsed(NOW.plusSeconds(1)) }
		code.usedAt shouldBe NOW
	}

	@Test
	fun `a session is expired from its expiry instant on`() {
		val session = AuthSession(account(), "fictitious-hash", rememberDevice = true, NOW, NOW.plus(Duration.ofDays(30)))

		session.isExpiredAt(NOW.plus(Duration.ofDays(30)).minusNanos(1)) shouldBe false
		session.isExpiredAt(NOW.plus(Duration.ofDays(30))) shouldBe true
		session.idleLifetime shouldBe AuthSession.REMEMBERED_LIFETIME
		AuthSession.idleLifetime(rememberDevice = false) shouldBe AuthSession.BROWSER_SESSION_IDLE
	}

	private fun account() = UserAccount(USERNAME, "fictitious-password-hash", EncryptedSecret("fictitious-totp-secret"))

	private companion object {
		const val USERNAME = "jane.doe"
		const val PASSWORD = "correct-horse-battery-staple"
		const val TOTP_CODE = "493817"
		const val RECOVERY_CODE = "qwert-zxcvb"
		const val TOKEN = "fictitious-session-token-value"
		val NOW: Instant = Instant.parse("2026-10-20T09:00:00Z")
	}
}
