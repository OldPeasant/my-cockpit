package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.core.auth.api.RecoveryCodesResponse
import ch.sonensei.cockpit.core.auth.api.RegenerateRecoveryCodesRequest
import ch.sonensei.cockpit.core.auth.domain.RecoveryCodeResult
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** The objects of a recovery code regeneration (task p0-web-devices) hide the TOTP code and the new codes in `toString`. */
class RecoveryCodeSecretHidingTests {
	@Test
	fun `the request hides the TOTP code`() {
		RegenerateRecoveryCodesRequest(TOTP_CODE).toString() shouldNotContain TOTP_CODE
	}

	@Test
	fun `the response and the service result hide the new codes`() {
		val texts =
			listOf(
				RecoveryCodesResponse(CODES).toString(),
				RecoveryCodeResult.Regenerated(CODES).toString(),
			)

		texts.forEach { text -> CODES.forEach { code -> text shouldNotContain code } }
	}

	private companion object {
		const val TOTP_CODE = "493817"
		val CODES = listOf("qwert-zuiop", "asdfg-hjkln")
	}
}
