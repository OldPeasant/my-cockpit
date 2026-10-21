package ch.sonensei.cockpit.core.backupcontrib

import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupFormat
import ch.sonensei.cockpit.core.crypto.SecretCipher
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.InputStream
import java.time.Instant
import java.util.UUID

/**
 * [UserBackupContributor.restoreWarnings] (SPEC.md §10.1 validation report): what the owner is told about the account
 * in the backup before confirming a restore. It reads the backup only, never the database.
 */
class UserRestoreWarningsTests {
	// Strict mocks: restoreWarnings reads the backup only, any database or cipher call fails the test.
	private val contributor = UserBackupContributor(mockk<JdbcTemplate>(), mockk<SecretCipher>())

	private val mapper = BackupFormat.canonical(JsonMapper.builder().addModule(KotlinModule.Builder().build()).build())

	private inner class Reader(
		private val user: UserBackup?,
	) : BackupDataReader {
		override val formatVersion = BackupFormat.VERSION

		override fun has(name: String) = name == UserBackupContributor.USER && user != null

		// Through JSON, like the zip reader: the contributor gets what it would read from data/user.json.
		override fun <T : Any> json(
			name: String,
			type: TypeReference<T>,
		): T? = if (name == UserBackupContributor.USER && user != null) mapper.convertValue(user, type) else null

		override fun <T : Any> jsonLines(
			name: String,
			type: TypeReference<T>,
			consumer: (T) -> Unit,
		) = Unit

		override fun file(path: String): InputStream? = null

		override fun files(): List<String> = emptyList()
	}

	private val at = Instant.parse("2026-03-01T08:00:00Z")

	private fun code(usedAt: Instant?) = RecoveryCodeBackup(UUID.randomUUID(), at, at, 0, "\$argon2id\$fictitious-hash", usedAt)

	private fun user(
		totpConfirmedAt: Instant?,
		vararg codes: RecoveryCodeBackup,
	) = UserBackup(UUID.randomUUID(), at, at, 1, "jdoe", "\$argon2id\$fictitious-password-hash", totpConfirmedAt, codes.toList())

	private fun warnings(user: UserBackup?) = contributor.restoreWarnings(Reader(user))

	@Test
	fun `a backup without owner account warns that the setup starts again`() {
		warnings(null) shouldContainExactly listOf("The backup has no owner account: the setup starts again after the restore")
	}

	@Test
	fun `an owner who never confirmed TOTP warns that the setup starts again`() {
		warnings(user(null, code(null))) shouldContainExactly
			listOf("The backup's owner account has not finished the setup: it starts again after the restore")
	}

	@Test
	fun `a confirmed owner is told how many unused recovery codes are left`() {
		warnings(user(at, code(null), code(at), code(null))) shouldContainExactly
			listOf("The TOTP secret is not in the backup: sign in with one of the 2 unused recovery codes and set up TOTP again")
	}

	@Test
	fun `a confirmed owner without unused recovery codes is told to sign in with the password alone`() {
		listOf(user(at), user(at, code(at), code(at))).forEach { user ->
			warnings(user) shouldContainExactly
				listOf(
					"The TOTP secret is not in the backup: sign in with the password alone (no recovery code is left) and set up TOTP again",
				)
		}
	}

	@Test
	fun `the warnings name neither the owner nor any hash`() {
		val all = warnings(user(at, code(null))) + warnings(user(null)) + warnings(user(at))

		all.forEach { warning ->
			warning shouldNotContain "jdoe"
			warning shouldNotContain "argon2id"
		}
	}
}
