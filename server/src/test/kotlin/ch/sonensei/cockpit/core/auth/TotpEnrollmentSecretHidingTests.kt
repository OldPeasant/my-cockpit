package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.api.ConfirmTotpEnrollmentRequest
import ch.sonensei.cockpit.core.auth.api.TotpEnrollmentResponse
import ch.sonensei.cockpit.core.auth.domain.TotpEnrollment
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** The objects of a TOTP re-enrollment (task backup-secrets) hide the new secret and the code in `toString`. */
class TotpEnrollmentSecretHidingTests {
	@Test
	fun `the response and the service result hide the new secret and its otpauth URI`() {
		val texts =
			listOf(
				TotpEnrollmentResponse(SECRET, URI).toString(),
				TotpEnrollment(SECRET, URI).toString(),
			)

		texts.forEach { text ->
			text shouldNotContain SECRET
			text shouldNotContain "otpauth"
		}
	}

	@Test
	fun `the confirm request hides the TOTP code`() {
		ConfirmTotpEnrollmentRequest(TOTP_CODE).toString() shouldNotContain TOTP_CODE
	}

	private companion object {
		const val SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
		const val URI = "otpauth://totp/Cockpit:jane.doe?secret=$SECRET&issuer=Cockpit"
		const val TOTP_CODE = "493817"
	}
}
