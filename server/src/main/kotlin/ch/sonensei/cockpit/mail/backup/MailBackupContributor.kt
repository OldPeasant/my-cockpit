package ch.sonensei.cockpit.mail.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.json
import ch.sonensei.cockpit.backup.domain.jsonLines
import ch.sonensei.cockpit.backup.domain.secret
import ch.sonensei.cockpit.core.backupcontrib.OAuthBackupContributor
import ch.sonensei.cockpit.core.backupcontrib.instant
import ch.sonensei.cockpit.core.backupcontrib.toTimestamp
import ch.sonensei.cockpit.core.backupcontrib.uuid
import ch.sonensei.cockpit.core.crypto.SecretCipher
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.mail.model.MailAccountType
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * Mail in backups (SPEC.md §7.1, §10.2). Mail content is not backed up: the IMAP server has it.
 *
 * - `data/mail_accounts.json`: every account's configuration with its sending identities and its folders (path, role
 *   and the owner's role override; no sync state, so the first sync after a restore is a full one). IMAP passwords are
 *   secrets: only in the backup secret [PASSWORDS] (account id → password, with a passphrase). A password account
 *   restored without it cannot log in until the owner enters the password again; OAuth accounts follow their OAuth
 *   connection ([OAuthBackupContributor]).
 * - `data/mail_message_refs.jsonl`: the cached messages that a link or a reminder points to, as refs (id, account,
 *   folder, Message-ID header) without headers or bodies, so those links survive. A ref is restored as a detached
 *   message row (no UID): the sync takes it over again when it finds the message by its Message-ID
 *   ([ch.sonensei.cockpit.mail.sync.MailHeaderStore]), and until then the views don't show it.
 *
 * Rows are read and written with JDBC rather than JPA: a restore must keep ids, `created_at`, `updated_at` and
 * `version` exactly as backed up, which JPA auditing and versioning would overwrite.
 */
@Component
class MailBackupContributor(
	private val jdbc: JdbcTemplate,
	private val cipher: SecretCipher,
) : BackupContributor {
	private val log = LoggerFactory.getLogger(javaClass)

	override val id = ID

	// OAuth accounts refer to an OAuth connection.
	override val dependsOn = setOf(OAuthBackupContributor.ID)

	override fun export(writer: BackupDataWriter) {
		val passwords = sortedMapOf<String, String>()
		val identities = jdbc.query("select * from mail_identity order by position, id") { rs, _ -> identity(rs) }.groupBy { it.accountId }
		val folders = jdbc.query("select * from mail_folder order by path, id") { rs, _ -> folder(rs) }.groupBy { it.accountId }
		val accounts =
			jdbc.query("select * from mail_account order by id") { rs, _ ->
				val id = rs.uuid("id")
				if (writer.includesSecrets) rs.getString("password")?.let { passwords[id.toString()] = cipher.decrypt(it) }
				account(rs, id, identities[id].orEmpty().map { it.backup }, folders[id].orEmpty().map { it.backup })
			}
		if (accounts.isEmpty()) return
		writer.json(ACCOUNTS, accounts)
		if (passwords.isNotEmpty()) writer.secret(PASSWORDS, passwords)
		val refs = messageRefs()
		if (refs.isNotEmpty()) writer.jsonLines(MESSAGE_REFS, refs.asSequence())
	}

	override fun import(reader: BackupDataReader) {
		val accounts = reader.json<List<MailAccountBackup>>(ACCOUNTS) ?: return
		val passwords = reader.secret<Map<String, String>>(PASSWORDS).orEmpty()
		var withoutPassword = 0
		accounts.forEach { account ->
			val password = if (account.type == MailAccountType.IMAP_SMTP.name) passwords[account.id.toString()] else null
			if (account.type == MailAccountType.IMAP_SMTP.name && password == null) withoutPassword++
			insertAccount(account, password)
			account.identities.forEach { insertIdentity(account.id, it) }
			account.folders.forEach { insertFolder(account.id, it) }
		}
		reader.jsonLines<MailMessageRefBackup>(MESSAGE_REFS) { ref ->
			jdbc.update(
				"""insert into mail_message (id, created_at, updated_at, version, account_id, folder_id, message_id_header)
				values (?, ?, ?, ?, ?, ?, ?)""",
				ref.id,
				ref.createdAt.toTimestamp(),
				ref.updatedAt.toTimestamp(),
				ref.version,
				ref.accountId,
				ref.folderId,
				ref.messageIdHeader,
			)
		}
		if (withoutPassword > 0) log.warn("Restored {} IMAP mail accounts without their password: it must be entered again", withoutPassword)
	}

	override fun restoreWarnings(reader: BackupDataReader): List<String> {
		val accounts = reader.json<List<MailAccountBackup>>(ACCOUNTS).orEmpty()
		if (accounts.isEmpty()) return emptyList()
		val warnings = mutableListOf("The emails of ${count(accounts.size, "mail account")} are fetched again with the next sync")
		val withPassword = accounts.count { it.type == MailAccountType.IMAP_SMTP.name }
		if (withPassword > 0) {
			val imap = count(withPassword, "IMAP mail account")
			warnings +=
				if (reader.hasSecrets) {
					"Without the backup's passphrase, the passwords of $imap must be entered again"
				} else {
					"The backup has no mail passwords: the passwords of $imap must be entered again"
				}
		}
		if (reader.has(MESSAGE_REFS)) {
			warnings += "Links and reminders to emails older than the sync window come back only once those emails are loaded again"
		}
		return warnings
	}

	/** Messages that a link (either end) or a reminder points to, in id order. */
	private fun messageRefs(): List<MailMessageRefBackup> =
		jdbc.query(
			"""select m.id, m.created_at, m.updated_at, m.version, m.account_id, m.folder_id, m.message_id_header
			from mail_message m
			where exists (
			    select 1 from link l
			    where (l.source_type = ? and l.source_id = m.id) or (l.target_type = ? and l.target_id = m.id)
			) or exists (select 1 from reminder r where r.item_type = ? and r.item_id = m.id)
			order by m.id""",
			{ rs, _ ->
				MailMessageRefBackup(
					id = rs.uuid("id"),
					createdAt = rs.instant("created_at"),
					updatedAt = rs.instant("updated_at"),
					version = rs.getLong("version"),
					accountId = rs.uuid("account_id"),
					folderId = rs.getObject("folder_id", UUID::class.java),
					messageIdHeader = rs.getString("message_id_header"),
				)
			},
			ItemType.EMAIL.name,
			ItemType.EMAIL.name,
			ItemType.EMAIL.name,
		)

	private fun account(
		rs: ResultSet,
		id: UUID,
		identities: List<MailIdentityBackup>,
		folders: List<MailFolderBackup>,
	) = MailAccountBackup(
		id = id,
		createdAt = rs.instant("created_at"),
		updatedAt = rs.instant("updated_at"),
		version = rs.getLong("version"),
		type = rs.getString("type"),
		displayName = rs.getString("display_name"),
		emailAddress = rs.getString("email_address"),
		imapHost = rs.getString("imap_host"),
		imapPort = rs.getInt("imap_port"),
		imapSecurity = rs.getString("imap_security"),
		smtpHost = rs.getString("smtp_host"),
		smtpPort = rs.getInt("smtp_port"),
		smtpSecurity = rs.getString("smtp_security"),
		username = rs.getString("username"),
		oauthConnectionId = rs.getObject("oauth_connection_id", UUID::class.java),
		signatureMd = rs.getString("signature_md"),
		notifyNewMail = rs.getBoolean("notify_new_mail"),
		syncWindowMonths = rs.getInt("sync_window_months"),
		identities = identities,
		folders = folders,
	)

	private class Owned<T>(
		val accountId: UUID,
		val backup: T,
	)

	private fun identity(rs: ResultSet) =
		Owned(
			rs.uuid("account_id"),
			MailIdentityBackup(
				id = rs.uuid("id"),
				createdAt = rs.instant("created_at"),
				updatedAt = rs.instant("updated_at"),
				version = rs.getLong("version"),
				name = rs.getString("name"),
				email = rs.getString("email"),
				replyTo = rs.getString("reply_to"),
				signatureMd = rs.getString("signature_md"),
				isDefault = rs.getBoolean("is_default"),
				position = rs.getInt("position"),
			),
		)

	private fun folder(rs: ResultSet) =
		Owned(
			rs.uuid("account_id"),
			MailFolderBackup(
				id = rs.uuid("id"),
				createdAt = rs.instant("created_at"),
				updatedAt = rs.instant("updated_at"),
				version = rs.getLong("version"),
				path = rs.getString("path"),
				name = rs.getString("name"),
				delimiter = rs.getString("delimiter"),
				role = rs.getString("role"),
				roleOverridden = rs.getBoolean("role_overridden"),
				selectable = rs.getBoolean("selectable"),
			),
		)

	private fun insertAccount(
		account: MailAccountBackup,
		password: String?,
	) {
		jdbc.update(
			"""insert into mail_account
			(id, created_at, updated_at, version, type, display_name, email_address, imap_host, imap_port, imap_security, smtp_host,
			smtp_port, smtp_security, username, password, oauth_connection_id, signature_md, notify_new_mail, sync_window_months)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			account.id,
			account.createdAt.toTimestamp(),
			account.updatedAt.toTimestamp(),
			account.version,
			account.type,
			account.displayName,
			account.emailAddress,
			account.imapHost,
			account.imapPort,
			account.imapSecurity,
			account.smtpHost,
			account.smtpPort,
			account.smtpSecurity,
			account.username,
			password?.let(cipher::encrypt),
			account.oauthConnectionId,
			account.signatureMd,
			account.notifyNewMail,
			account.syncWindowMonths,
		)
	}

	private fun insertIdentity(
		accountId: UUID,
		identity: MailIdentityBackup,
	) {
		jdbc.update(
			"""insert into mail_identity
			(id, created_at, updated_at, version, account_id, name, email, reply_to, signature_md, is_default, position)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			identity.id,
			identity.createdAt.toTimestamp(),
			identity.updatedAt.toTimestamp(),
			identity.version,
			accountId,
			identity.name,
			identity.email,
			identity.replyTo,
			identity.signatureMd,
			identity.isDefault,
			identity.position,
		)
	}

	private fun insertFolder(
		accountId: UUID,
		folder: MailFolderBackup,
	) {
		jdbc.update(
			"""insert into mail_folder
			(id, created_at, updated_at, version, account_id, path, name, delimiter, role, role_overridden, selectable)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			folder.id,
			folder.createdAt.toTimestamp(),
			folder.updatedAt.toTimestamp(),
			folder.version,
			accountId,
			folder.path,
			folder.name,
			folder.delimiter,
			folder.role,
			folder.roleOverridden,
			folder.selectable,
		)
	}

	private fun count(
		n: Int,
		noun: String,
	) = if (n == 1) "1 $noun" else "$n ${noun}s"

	companion object {
		const val ID = "mail"
		const val ACCOUNTS = "mail_accounts"
		const val MESSAGE_REFS = "mail_message_refs"

		/** Name of the IMAP passwords (account id → password) in the backup's secrets. */
		const val PASSWORDS = "mail_passwords"
	}
}

/** One account in `data/mail_accounts.json`: its configuration, identities and folders; no password, no sync state. */
data class MailAccountBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val type: String,
	val displayName: String,
	val emailAddress: String,
	val imapHost: String,
	val imapPort: Int,
	val imapSecurity: String,
	val smtpHost: String,
	val smtpPort: Int,
	val smtpSecurity: String,
	val username: String,
	val oauthConnectionId: UUID?,
	val signatureMd: String?,
	val notifyNewMail: Boolean,
	val syncWindowMonths: Int,
	val identities: List<MailIdentityBackup>,
	val folders: List<MailFolderBackup>,
)

data class MailIdentityBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val name: String,
	val email: String,
	val replyTo: String?,
	val signatureMd: String?,
	val isDefault: Boolean,
	val position: Int,
)

/** A folder without its sync state (UIDVALIDITY, UIDNEXT, HIGHESTMODSEQ, last sync): the sync fetches it again. */
data class MailFolderBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val path: String,
	val name: String,
	val delimiter: String?,
	val role: String?,
	val roleOverridden: Boolean,
	val selectable: Boolean,
)

/** One line of `data/mail_message_refs.jsonl`: a linked message as a ref, without headers or content. */
data class MailMessageRefBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val accountId: UUID,
	val folderId: UUID?,
	val messageIdHeader: String?,
)
