package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.api.ConfirmRestoreRequest
import ch.sonensei.cockpit.backup.api.StartBackupRequest
import ch.sonensei.cockpit.core.backupcontrib.UserSecrets
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** Objects holding a backup passphrase or a backed-up secret hide it in `toString`, e.g. in logs or error messages. */
class BackupSecretHidingTests {
	@Test
	fun `the backup and restore requests hide the passphrase`() {
		val texts =
			listOf(
				StartBackupRequest(PASSPHRASE).toString(),
				ConfirmRestoreRequest(PASSPHRASE).toString(),
			)

		texts.forEach { text -> text shouldNotContain PASSPHRASE }
	}

	@Test
	fun `the user's backed-up secrets hide the TOTP secret`() {
		UserSecrets(TOTP_SECRET).toString() shouldNotContain TOTP_SECRET
	}

	private companion object {
		const val PASSPHRASE = "fictitious backup passphrase 4711"
		const val TOTP_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
	}
}
