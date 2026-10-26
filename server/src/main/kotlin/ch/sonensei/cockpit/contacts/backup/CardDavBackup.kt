package ch.sonensei.cockpit.contacts.backup

import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.json
import ch.sonensei.cockpit.backup.domain.jsonLines
import ch.sonensei.cockpit.backup.domain.secret
import ch.sonensei.cockpit.core.backupcontrib.uuid
import ch.sonensei.cockpit.core.crypto.SecretCipher
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The CardDAV accounts of the contacts in backups (part of [ContactsBackupContributor], after the contacts):
 *
 * - `data/contacts_carddav_accounts.json`: every account's configuration (name, URL, user name, the address book
 *   found). The password is a secret: only in the backup secret [PASSWORDS] (account id → password, with a
 *   passphrase); an account restored without it syncs only once its password is entered again (by adding it anew).
 * - `data/contacts_carddav_links.jsonl`: which card each contact is (tombstones of purged contacts included), so a
 *   restored account goes on syncing the same cards instead of importing them as new contacts.
 *
 * The sync token and status are not backed up: the first run after a restore lists every card and compares ETags.
 * Neither file is written when there is no account.
 */
@Component
class CardDavBackup(
	private val jdbc: JdbcTemplate,
	private val cipher: SecretCipher,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	fun export(writer: BackupDataWriter) {
		val passwords = sortedMapOf<String, String>()
		val accounts =
			jdbc.query("select * from contacts_carddav_account order by id") { rs, _ ->
				val account =
					CardDavAccountBackup(
						id = rs.uuid("id"),
						name = rs.getString("name"),
						url = rs.getString("url"),
						username = rs.getString("username"),
						addressbookUrl = rs.getString("addressbook_url"),
					)
				if (writer.includesSecrets) rs.getString("password")?.let { passwords[account.id.toString()] = cipher.decrypt(it) }
				account
			}
		if (accounts.isEmpty()) return
		writer.json(ACCOUNTS, accounts)
		if (passwords.isNotEmpty()) writer.secret(PASSWORDS, passwords)
		val links =
			jdbc.query("select * from contacts_carddav_link order by id") { rs, _ ->
				CardDavLinkBackup(
					id = rs.uuid("id"),
					contactId = rs.getObject("contact_id", UUID::class.java),
					accountId = rs.uuid("account_id"),
					href = rs.getString("href"),
					etag = rs.getString("etag"),
					syncedVersion = rs.getLong("synced_version"),
				)
			}
		writer.jsonLines(LINKS, links.asSequence())
	}

	/** Restores the accounts and links; the contacts must be restored already. */
	fun import(reader: BackupDataReader) {
		val accounts = reader.json<List<CardDavAccountBackup>>(ACCOUNTS) ?: return
		val passwords = reader.secret<Map<String, String>>(PASSWORDS).orEmpty()
		accounts.forEach { account ->
			val password = passwords[account.id.toString()]
			jdbc.update(
				"insert into contacts_carddav_account (id, name, url, username, password, addressbook_url) values (?, ?, ?, ?, ?, ?)",
				account.id,
				account.name,
				account.url,
				account.username,
				password?.let(cipher::encrypt),
				account.addressbookUrl,
			)
		}
		reader.jsonLines<CardDavLinkBackup>(LINKS) { link ->
			jdbc.update(
				"""insert into contacts_carddav_link (id, contact_id, account_id, href, etag, synced_version)
				values (?, ?, ?, ?, ?, ?)""",
				link.id,
				link.contactId,
				link.accountId,
				link.href,
				link.etag,
				link.syncedVersion,
			)
		}
		val withoutPassword = accounts.count { passwords[it.id.toString()] == null }
		if (withoutPassword > 0) log.info("Restored {} CardDAV account(s) without a password", withoutPassword)
	}

	fun restoreWarnings(reader: BackupDataReader): List<String> {
		val count = reader.json<List<CardDavAccountBackup>>(ACCOUNTS)?.size ?: 0
		if (count == 0) return emptyList()
		val accounts = if (count == 1) "1 CardDAV contacts account" else "$count CardDAV contacts accounts"
		return listOf(
			if (reader.hasSecrets) {
				"Without the backup's passphrase, $accounts must be added again if they need a password (it is in the secrets)"
			} else {
				"The backup has no CardDAV passwords: $accounts must be added again if they need one"
			},
		)
	}

	companion object {
		const val ACCOUNTS = "contacts_carddav_accounts"
		const val LINKS = "contacts_carddav_links"

		/** Name of the account passwords (account id → password) in the backup's secrets. */
		const val PASSWORDS = "contacts_carddav_passwords"
	}
}

/** One account in `data/contacts_carddav_accounts.json`; its password is in the backup's secrets only. */
data class CardDavAccountBackup(
	val id: UUID,
	val name: String,
	val url: String,
	val username: String?,
	val addressbookUrl: String?,
)

/** One line of `data/contacts_carddav_links.jsonl`; [contactId] is `null` for the tombstone of a purged contact. */
data class CardDavLinkBackup(
	val id: UUID,
	val contactId: UUID?,
	val accountId: UUID,
	val href: String,
	val etag: String?,
	val syncedVersion: Long,
)
