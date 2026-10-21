package ch.sonensei.cockpit.core.backupcontrib

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.InvalidBackupException
import ch.sonensei.cockpit.backup.domain.json
import ch.sonensei.cockpit.backup.domain.secret
import ch.sonensei.cockpit.core.auth.domain.Base32
import ch.sonensei.cockpit.core.auth.domain.Totp
import ch.sonensei.cockpit.core.crypto.SecretCipher
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * `data/user.json`: the owner account (SPEC.md §10.2) with its password hash and recovery code hashes. The TOTP secret
 * is a secret: it is never written to `user.json`, only as the backup secret [SECRETS] (`data/secrets.enc.json`, with a
 * passphrase). Restored with it, the owner signs in as before. Without it, [import] stores a fresh random TOTP secret
 * and marks the account for TOTP re-enrollment (`totp_reenroll_required`): the next login (with a recovery code, or the
 * password alone when none is left) may only set up TOTP again. Sessions and Android devices are not backed up: their
 * tokens are short-lived, so the owner signs in again after a restore.
 *
 * An account that was itself waiting for TOTP re-enrollment exports no secret (its secret is not confirmed), so it waits
 * again after the restore. Log lines never contain the username, secrets or hashes.
 */
@Component
class UserBackupContributor(
	private val jdbc: JdbcTemplate,
	private val cipher: SecretCipher,
) : BackupContributor {
	private val log = LoggerFactory.getLogger(javaClass)

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
		if (writer.includesSecrets) {
			val (secret, reenroll) =
				jdbc.queryForObject("select totp_secret, totp_reenroll_required from app_user where id = ?", { rs, _ ->
					rs.getString("totp_secret") to rs.getBoolean("totp_reenroll_required")
				}, user.id)!!
			if (!reenroll) writer.secret(SECRETS, UserSecrets(totpSecret = cipher.decrypt(secret)))
		}
	}

	override fun import(reader: BackupDataReader) {
		val user = reader.json<UserBackup>(USER) ?: return
		val restored = reader.secret<UserSecrets>(SECRETS)?.totpSecret
		if (restored != null && runCatching { Base32.decode(restored) }.getOrNull()?.isNotEmpty() != true) {
			throw InvalidBackupException(listOf("The TOTP secret in the backup's secrets is not valid"))
		}
		// An account that never confirmed TOTP runs the setup again; it needs no re-enrollment.
		val reenroll = restored == null && user.totpConfirmedAt != null
		jdbc.update(
			"""insert into app_user
			(id, created_at, updated_at, version, username, password_hash, totp_secret, totp_confirmed_at, totp_reenroll_required)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			user.id,
			user.createdAt.toTimestamp(),
			user.updatedAt.toTimestamp(),
			user.version,
			user.username,
			user.passwordHash,
			cipher.encrypt(restored ?: Base32.encode(Totp.newSecret())),
			user.totpConfirmedAt.toTimestampOrNull(),
			reenroll,
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
		if (reenroll) warnTotpNotRestored(user.recoveryCodes.count { it.usedAt == null })
	}

	override fun restoreWarnings(reader: BackupDataReader): List<String> {
		val user = reader.json<UserBackup>(USER) ?: return listOf("The backup has no owner account: the setup starts again after the restore")
		if (user.totpConfirmedAt ==
			null
		) {
			return listOf("The backup's owner account has not finished the setup: it starts again after the restore")
		}
		val unused = user.recoveryCodes.count { it.usedAt == null }
		val signIn =
			if (unused == 0) {
				"sign in with the password alone (no recovery code is left) and set up TOTP again"
			} else {
				"sign in with one of the $unused unused recovery codes and set up TOTP again"
			}
		return listOf(
			if (reader.hasSecrets) {
				"The TOTP secret is restored only with the backup's passphrase: without it, $signIn"
			} else {
				"The TOTP secret is not in the backup: $signIn"
			},
		)
	}

	private fun warnTotpNotRestored(unusedRecoveryCodes: Int) {
		if (unusedRecoveryCodes == 0) {
			log.warn(
				"Restored owner account without its TOTP secret and with no unused recovery code left: " +
					"the next login needs the password alone and must set up TOTP again",
			)
		} else {
			log.warn(
				"Restored owner account without its TOTP secret: the next login needs a recovery code " +
					"({} unused) and must set up TOTP again",
				unusedRecoveryCodes,
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

		/** Name of the [UserSecrets] in the backup's secrets. */
		const val SECRETS = "user"
	}
}

/** The owner's secrets in `data/secrets.enc.json`. `toString` hides them. */
class UserSecrets(
	/** Base32, as the authenticator app has it. */
	val totpSecret: String,
) {
	override fun toString(): String = "UserSecrets(******)"
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
