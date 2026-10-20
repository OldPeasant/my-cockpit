package ch.sonensei.cockpit.core.backupcontrib

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.json
import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.crypto.SecretCipher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * `data/user.json`: the owner account (SPEC.md §10.2) with its password hash and recovery code hashes. The TOTP secret
 * is a secret: it is never written in plain text, only through the backup secrets mechanism (`data/secrets.enc.json`,
 * with a passphrase). Without it, [import] stores a fresh random TOTP secret, so the owner signs in with a recovery code
 * and enrolls TOTP again. Sessions and Android devices are not backed up: their tokens are short-lived, so the owner
 * signs in again after a restore.
 */
@Component
class UserBackupContributor(
	private val jdbc: JdbcTemplate,
	private val cipher: SecretCipher,
) : BackupContributor {
	override val id = ID

	override fun export(writer: BackupDataWriter) {
		val user =
			jdbc
				.query("select * from app_user order by id") { rs, _ ->
					UserBackup(
						id = rs.uuid("id"),
						createdAt = rs.instant("created_at"),
						updatedAt = rs.instant("updated_at"),
						version = rs.getLong("version"),
						username = rs.getString("username"),
						passwordHash = rs.getString("password_hash"),
						totpConfirmedAt = rs.instantOrNull("totp_confirmed_at"),
						recoveryCodes = emptyList(),
					)
				}.singleOrNull() ?: return
		writer.json(USER, user.copy(recoveryCodes = recoveryCodes(user.id)))
	}

	override fun import(reader: BackupDataReader) {
		val user = reader.json<UserBackup>(USER) ?: return
		jdbc.update(
			"""insert into app_user (id, created_at, updated_at, version, username, password_hash, totp_secret, totp_confirmed_at)
			values (?, ?, ?, ?, ?, ?, ?, ?)""",
			user.id,
			user.createdAt.toTimestamp(),
			user.updatedAt.toTimestamp(),
			user.version,
			user.username,
			user.passwordHash,
			cipher.encrypt(Base32.encode(Totp.newSecret())),
			user.totpConfirmedAt.toTimestampOrNull(),
		)
		user.recoveryCodes.forEach { code ->
			jdbc.update(
				"insert into recovery_code (id, created_at, updated_at, version, user_id, code_hash, used_at) values (?, ?, ?, ?, ?, ?, ?)",
				code.id,
				code.createdAt.toTimestamp(),
				code.updatedAt.toTimestamp(),
				code.version,
				user.id,
				code.codeHash,
				code.usedAt.toTimestampOrNull(),
			)
		}
	}

	private fun recoveryCodes(userId: UUID): List<RecoveryCodeBackup> =
		jdbc.query("select * from recovery_code where user_id = ? order by id", { rs, _ ->
			RecoveryCodeBackup(
				id = rs.uuid("id"),
				createdAt = rs.instant("created_at"),
				updatedAt = rs.instant("updated_at"),
				version = rs.getLong("version"),
				codeHash = rs.getString("code_hash"),
				usedAt = rs.instantOrNull("used_at"),
			)
		}, userId)

	companion object {
		const val ID = "user"
		const val USER = "user"
	}
}

/** The owner account in `data/user.json`. Hashes only: no TOTP secret (see [UserBackupContributor]). */
data class UserBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val username: String,
	/** Argon2id hash, always included (SPEC.md §10.2). */
	val passwordHash: String,
	/** When TOTP enrollment was confirmed; `null` while setup was pending. */
	val totpConfirmedAt: Instant?,
	val recoveryCodes: List<RecoveryCodeBackup>,
)

data class RecoveryCodeBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	/** Argon2id hash of the normalized code. */
	val codeHash: String,
	val usedAt: Instant?,
)
