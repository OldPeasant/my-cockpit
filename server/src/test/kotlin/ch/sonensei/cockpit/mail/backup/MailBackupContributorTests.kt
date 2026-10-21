package ch.sonensei.cockpit.mail.backup

import ch.sonensei.cockpit.backup.RestoreTestDatabases
import ch.sonensei.cockpit.backup.Zips
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.backup.domain.secret
import ch.sonensei.cockpit.backup.infra.ZipBackupReader
import ch.sonensei.cockpit.core.crypto.SecretCipher
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailMessageLocator
import ch.sonensei.cockpit.mail.sync.MailHeaderStore
import ch.sonensei.cockpit.mail.sync.RemoteFlags
import ch.sonensei.cockpit.mail.sync.RemoteMessage
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes

/**
 * [MailBackupContributor] (SPEC.md §7.1, §10.2): `data/mail_accounts.json` with identities and folders but no
 * passwords and no folder sync state, IMAP passwords only in the encrypted secrets (`mail_passwords`),
 * `data/mail_message_refs.jsonl` only for messages a link or a reminder points to and without content, and the restore:
 * rows with their ids, times and versions, passwords re-encrypted, refs detached until the header sync takes them over.
 *
 * Rows are inserted with JDBC so ids, times and versions are under the test's control. Runs against a database of its
 * own, because every test empties all tables.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension::class)
class MailBackupContributorTests(
	@Autowired private val backups: BackupService,
	@Autowired private val contributor: MailBackupContributor,
	@Autowired private val cipher: SecretCipher,
	@Autowired private val store: MailHeaderStore,
	@Autowired private val locator: MailMessageLocator,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactions: TransactionTemplate,
) {
	@TempDir lateinit var work: Path

	@BeforeEach
	fun wipe() = RestoreTestDatabases.wipe(jdbc, dataDir)

	// --- data/mail_accounts.json ------------------------------------------------------------------------------------

	@Test
	fun `accounts json holds every account's config with identities by position and folders by path, without password`() {
		seed()

		val accounts = export().json("data/mail_accounts.json")

		accounts.map { it["id"].asString() } shouldContainExactly listOf(IMAP, GOOGLE).map { it.toString() }
		val imap = accounts[0]
		imap["type"].asString() shouldBe "IMAP_SMTP"
		imap["displayName"].asString() shouldBe "Privat «Zürich»"
		imap["emailAddress"].asString() shouldBe "owner@example.test"
		imap["imapHost"].asString() shouldBe "imap.example.test"
		imap["imapPort"].asInt() shouldBe 993
		imap["imapSecurity"].asString() shouldBe "SSL_TLS"
		imap["smtpHost"].asString() shouldBe "smtp.example.test"
		imap["smtpPort"].asInt() shouldBe 587
		imap["smtpSecurity"].asString() shouldBe "STARTTLS"
		imap["username"].asString() shouldBe "owner"
		imap["signatureMd"].asString() shouldBe "--\n**Owner**"
		imap["notifyNewMail"].asBoolean() shouldBe false
		imap["syncWindowMonths"].asInt() shouldBe 6
		imap["version"].asLong() shouldBe 3
		imap["createdAt"].asString() shouldBe T0.toString()
		imap["updatedAt"].asString() shouldBe T1.toString()
		imap["oauthConnectionId"].isNull shouldBe true
		imap.has("password") shouldBe false

		imap["identities"].values().map { it["email"].asString() } shouldContainExactly
			listOf("owner@example.test", "verein@example.test")
		val verein = imap["identities"][1]
		verein["id"].asString() shouldBe id(21).toString()
		verein["name"].asString() shouldBe "Owner (Verein)"
		verein["replyTo"].asString() shouldBe "board@example.test"
		verein["signatureMd"].asString() shouldBe ""
		verein["position"].asInt() shouldBe 1
		imap["identities"][0]["replyTo"].isNull shouldBe true

		imap["folders"].values().map { it["path"].asString() } shouldContainExactly listOf("INBOX", "INBOX/Archiv 2025", "[Gmail]")
		val archive = imap["folders"][1]
		archive["role"].asString() shouldBe "ARCHIVE"
		archive["roleOverridden"].asBoolean() shouldBe true
		archive["name"].asString() shouldBe "Archiv 2025"
		archive["delimiter"].asString() shouldBe "/"
		imap["folders"][0]["role"].asString() shouldBe "INBOX"
		imap["folders"][0]["roleOverridden"].asBoolean() shouldBe false
		imap["folders"][2]["selectable"].asBoolean() shouldBe false
		imap["folders"][2]["role"].isNull shouldBe true

		val google = accounts[1]
		google["type"].asString() shouldBe "GOOGLE"
		google["oauthConnectionId"].asString() shouldBe CONNECTION.toString()
		google["identities"].size() shouldBe 0
		google["folders"].values().map { it["path"].asString() } shouldContainExactly listOf("[Gmail]/All Mail")
	}

	@Test
	fun `folder sync state (UIDVALIDITY, UIDNEXT, HIGHESTMODSEQ, last sync) is not exported`() {
		seed()

		val exported = export()

		exported.json("data/mail_accounts.json").flatMap { it["folders"].values() }.forEach { folder ->
			withClue(folder["path"].asString()) {
				listOf("uidValidity", "uidvalidity", "uidNext", "uidnext", "highestModSeq", "highestmodseq", "lastSyncedAt")
					.forEach { folder.has(it) shouldBe false }
			}
		}
		val text = exported.texts().getValue("data/mail_accounts.json")
		listOf("424242", "1717", "987654", "2026-10-01T08:00:00Z").forEach { text shouldNotContain it }
	}

	@Test
	fun `without a passphrase no password is written anywhere and there are no secrets`() {
		seed()
		val stored = storedPassword(IMAP).shouldNotBeNull()

		val exported = export()

		exported.entryNames.filter { it.startsWith("data/secrets") }.shouldBeEmpty()
		exported.texts().forEach { (name, text) ->
			withClue(name) {
				text shouldNotContain PASSWORD
				text shouldNotContain stored
			}
		}
	}

	@Test
	fun `with a passphrase the IMAP password is only in the encrypted secrets, keyed by account id`() {
		seed()
		val stored = storedPassword(IMAP).shouldNotBeNull()

		val exported = export(PASSPHRASE)

		exported.entryNames shouldContain "data/secrets.enc.json"
		exported.texts().forEach { (name, text) ->
			withClue(name) {
				text shouldNotContain PASSWORD
				text shouldNotContain stored
			}
		}
		exported.json("data/mail_accounts.json").forEach { it.has("password") shouldBe false }
		ZipBackupReader.open(exported.path, backups.canonicalJson, PASSPHRASE).use {
			it.secret<Map<String, String>>(MailBackupContributor.PASSWORDS)
		} shouldBe mapOf(IMAP.toString() to PASSWORD)
	}

	@Test
	fun `an install without mail accounts writes no mail data`() {
		val exported = export(PASSPHRASE)

		exported.entryNames.filter { it.startsWith("data/mail") }.shouldBeEmpty()
		val passwords =
			ZipBackupReader.open(exported.path, backups.canonicalJson, PASSPHRASE).use {
				it.secret<Map<String, String>>(MailBackupContributor.PASSWORDS)
			}
		passwords.shouldBeNull()
	}

	@Test
	fun `accounts without links or reminders to their messages write no message refs`() {
		seed(links = false)

		val exported = export()

		exported.entryNames shouldContain "data/mail_accounts.json"
		exported.entryNames.filter { it == "data/mail_message_refs.jsonl" }.shouldBeEmpty()
	}

	// --- data/mail_message_refs.jsonl -------------------------------------------------------------------------------

	@Test
	fun `message refs are only the messages a link (either end) or a reminder points to, once each, in id order`() {
		seed()

		val refs = export().lines("data/mail_message_refs.jsonl")

		refs.map { it["id"].asString() } shouldContainExactly listOf(LINKED, DETACHED, REMINDED).map { it.toString() }
		val linked = refs[0]
		linked["accountId"].asString() shouldBe IMAP.toString()
		linked["folderId"].asString() shouldBe INBOX.toString()
		linked["messageIdHeader"].asString() shouldBe "<invoice-17@example.test>"
		linked["version"].asLong() shouldBe 5
		linked["createdAt"].asString() shouldBe T0.toString()
		linked["updatedAt"].asString() shouldBe T1.toString()
		refs[1]["folderId"].isNull shouldBe true
		refs[1]["messageIdHeader"].isNull shouldBe true
		refs[2]["accountId"].asString() shouldBe GOOGLE.toString()
	}

	@Test
	fun `message refs carry no subject, addresses, snippet, body, UID or flags`() {
		seed()

		val exported = export()

		exported.lines("data/mail_message_refs.jsonl").forEach { ref ->
			ref.propertyNames().toSet() shouldBe
				setOf("id", "createdAt", "updatedAt", "version", "accountId", "folderId", "messageIdHeader")
		}
		exported.texts().filterKeys { it.startsWith("data/") }.forEach { (name, text) ->
			withClue(name) {
				listOf(
					"Rechnung Oktober",
					"billing@example.test",
					"Billing Dept",
					"recipient@example.test",
					"Snippet of the invoice",
					"Body of the invoice",
					"<p>HTML body</p>",
					"Lost message",
					"Weekly newsletter",
					"<newsletter@example.test>",
				).forEach { text shouldNotContain it }
			}
		}
	}

	@Test
	fun `links and reminders of other item types with a message's id do not export it`() {
		seed(links = false)
		link("TASK", id(90), "NOTE", UNLINKED)
		link("TASK", UNLINKED, "NOTE", id(91))
		jdbc.update(
			"insert into reminder (id, created_at, updated_at, version, item_type, item_id, remind_at, channel) values (?, ?, ?, 0, 'TASK', ?, ?, 'NOTIFICATION')",
			id(95),
			ts(T0),
			ts(T0),
			UNLINKED,
			ts(T2),
		)

		export().entryNames.filter { it == "data/mail_message_refs.jsonl" }.shouldBeEmpty()
	}

	// --- import -----------------------------------------------------------------------------------------------------

	@Test
	fun `import restores accounts, identities and folders with their ids, times and versions, folders without sync state`() {
		seed()
		val accounts = rows("select to_jsonb(x) - 'password' from mail_account x order by id")
		val identities = rows("select to_jsonb(x) from mail_identity x order by id")
		val folders = rows("select to_jsonb(x) - 'uidvalidity' - 'uidnext' - 'highestmodseq' - 'last_synced_at' from mail_folder x order by id")
		val zip = export()
		wipe()

		import(zip.path)

		rows("select to_jsonb(x) - 'password' from mail_account x order by id") shouldBe accounts
		rows("select to_jsonb(x) from mail_identity x order by id") shouldBe identities
		rows("select to_jsonb(x) - 'uidvalidity' - 'uidnext' - 'highestmodseq' - 'last_synced_at' from mail_folder x order by id") shouldBe
			folders
		jdbc
			.queryForList(
				"select count(*) from mail_folder where uidvalidity is not null or uidnext is not null or highestmodseq is not null or last_synced_at is not null",
				Int::class.java,
			).single() shouldBe 0
	}

	@Test
	fun `import restores refs as detached rows without content, and unreferenced messages not at all`() {
		seed()
		val zip = export()
		wipe()

		import(zip.path)

		val rows = jdbc.queryForList("select * from mail_message order by id").associateBy { it["id"] as UUID }
		rows.keys shouldBe setOf(LINKED, DETACHED, REMINDED)
		rows.values.forEach {
			it["uid"].shouldBeNull()
			it["modseq"].shouldBeNull()
			it["subject"].shouldBeNull()
			it["from_addresses"].shouldBeNull()
			it["snippet"].shouldBeNull()
			it["thread_key"].shouldBeNull()
		}
		val linked = rows.getValue(LINKED)
		linked["account_id"] shouldBe IMAP
		linked["folder_id"] shouldBe INBOX
		linked["message_id_header"] shouldBe "<invoice-17@example.test>"
		linked["version"] shouldBe 5L
		(linked["created_at"] as Timestamp).toInstant() shouldBe T0
		(linked["updated_at"] as Timestamp).toInstant() shouldBe T1
		rows.getValue(DETACHED)["folder_id"].shouldBeNull()
		jdbc.queryForObject("select count(*) from mail_body", Int::class.java) shouldBe 0
	}

	@Test
	fun `with the passphrase the IMAP password is restored and encrypted again, OAuth accounts keep none`(output: CapturedOutput) {
		seed()
		val stored = storedPassword(IMAP).shouldNotBeNull()
		val zip = export(PASSPHRASE)
		wipe()

		import(zip.path, PASSPHRASE)

		val restored = storedPassword(IMAP).shouldNotBeNull()
		restored shouldNotBe PASSWORD
		restored shouldNotBe stored
		cipher.decrypt(restored) shouldBe PASSWORD
		storedPassword(GOOGLE).shouldBeNull()
		jdbc.queryForObject("select oauth_connection_id from mail_account where id = ?", UUID::class.java, GOOGLE) shouldBe CONNECTION
		output.out shouldNotContain "without their password"
		output.out shouldNotContain PASSWORD
	}

	@Test
	fun `without the passphrase the IMAP account is restored without its password, and the log says so`(output: CapturedOutput) {
		seed()
		val withSecrets = export(PASSPHRASE)
		val withoutSecrets = export()

		wipe()
		import(withSecrets.path)
		storedPassword(IMAP).shouldBeNull()
		jdbc.queryForObject("select count(*) from mail_account", Int::class.java) shouldBe 2

		wipe()
		import(withoutSecrets.path)
		storedPassword(IMAP).shouldBeNull()
		jdbc.queryForObject("select username from mail_account where id = ?", String::class.java, IMAP) shouldBe "owner"

		output.out shouldContain "Restored 1 IMAP mail accounts without their password"
		output.out shouldNotContain PASSWORD
	}

	@Test
	fun `a backup without mail data restores no mail rows and has no restore warnings`() {
		val empty = export()

		import(empty.path)

		warnings(empty).shouldBeEmpty()
		listOf("mail_account", "mail_identity", "mail_folder", "mail_message").forEach { table ->
			withClue(table) { jdbc.queryForObject("select count(*) from $table", Int::class.java) shouldBe 0 }
		}
	}

	// --- restore warnings -------------------------------------------------------------------------------------------

	@Test
	fun `restore warnings say that mail is fetched again and that IMAP passwords must be entered again`() {
		seed()

		warnings(export(PASSPHRASE)) shouldContainExactly
			listOf(
				"The emails of 2 mail accounts are fetched again with the next sync",
				"Without the backup's passphrase, the passwords of 1 IMAP mail account must be entered again",
			)
		warnings(export()) shouldContainExactly
			listOf(
				"The emails of 2 mail accounts are fetched again with the next sync",
				"The backup has no mail passwords: the passwords of 1 IMAP mail account must be entered again",
			)
	}

	@Test
	fun `restore warnings for OAuth accounts only mention the sync, and count accounts in the plural`() {
		account(id(5), "GOOGLE", connection = id(80))
		account(id(6), "MICROSOFT", connection = id(81))

		warnings(export()) shouldContainExactly listOf("The emails of 2 mail accounts are fetched again with the next sync")
		val oauthOnly = export(PASSPHRASE)
		warnings(oauthOnly) shouldContainExactly listOf("The emails of 2 mail accounts are fetched again with the next sync")
		val passwords =
			ZipBackupReader.open(oauthOnly.path, backups.canonicalJson, PASSPHRASE).use {
				it.secret<Map<String, String>>(MailBackupContributor.PASSWORDS)
			}
		passwords.shouldBeNull()

		jdbc.update("delete from mail_account where id = ?", id(6))
		account(id(7), "IMAP_SMTP", password = PASSWORD)
		// An IMAP account whose password was never entered: nothing in the secrets, still counted.
		account(id(8), "IMAP_SMTP", password = null)
		warnings(export()) shouldContainExactly
			listOf(
				"The emails of 3 mail accounts are fetched again with the next sync",
				"The backup has no mail passwords: the passwords of 2 IMAP mail accounts must be entered again",
			)
		val withSecrets = export(PASSPHRASE)
		ZipBackupReader.open(withSecrets.path, backups.canonicalJson, PASSPHRASE).use {
			it.secret<Map<String, String>>(MailBackupContributor.PASSWORDS)
		} shouldBe mapOf(id(7).toString() to PASSWORD)
		wipe()
		import(withSecrets.path, PASSPHRASE)
		cipher.decrypt(storedPassword(id(7)).shouldNotBeNull()) shouldBe PASSWORD
		storedPassword(id(8)).shouldBeNull()
	}

	// --- restore, then sync -----------------------------------------------------------------------------------------

	@Test
	fun `the header sync takes a restored ref over by its Message-ID, so the link points to the message again`() {
		seed()
		val zip = export(PASSPHRASE)
		wipe()
		import(zip.path, PASSPHRASE)
		locator.current(LINKED).shouldBeNull()

		val counts = store.upsert(IMAP, INBOX, listOf(remote(23, "<invoice-17@example.test>"), remote(24, "<other@example.test>")))

		counts.updated shouldBe 1
		counts.created shouldBe 1
		val row = jdbc.queryForMap("select uid, folder_id, subject from mail_message where id = ?", LINKED)
		row["uid"] shouldBe 23L
		row["folder_id"] shouldBe INBOX
		row["subject"] shouldBe "Rechnung Oktober (again)"
		locator.current(LINKED).shouldNotBeNull().id shouldBe LINKED
		rows("select id::text from mail_message where message_id_header = '<invoice-17@example.test>'") shouldBe listOf(LINKED.toString())
	}

	@Test
	fun `a ref found in another folder of its account is taken over there too`() {
		seed()
		val zip = export()
		wipe()
		import(zip.path)

		store.upsert(GOOGLE, ALL_MAIL, listOf(remote(5, "<follow-up@work.example.test>"))).updated shouldBe 1

		jdbc.queryForObject("select uid from mail_message where id = ?", Long::class.java, REMINDED) shouldBe 5L
	}

	// --- helpers ----------------------------------------------------------------------------------------------------

	private fun warnings(exported: Exported): List<String> =
		ZipBackupReader.open(exported.path, backups.canonicalJson).use { contributor.restoreWarnings(it) }

	private class Exported(
		val path: Path,
		private val json: tools.jackson.databind.json.JsonMapper,
	) {
		private val entries: Map<String, ByteArray> = Zips.read(path.readBytes())

		val entryNames: Set<String> get() = entries.keys

		fun texts(): Map<String, String> = entries.mapValues { String(it.value, Charsets.UTF_8) }

		fun json(name: String): List<JsonNode> = json.readTree(entries.getValue(name)).values().toList()

		fun lines(name: String): List<JsonNode> =
			String(entries.getValue(name), Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { json.readTree(it) }
	}

	private fun export(passphrase: String? = null): Exported {
		val target = Files.createTempFile(work, "backup-", ".zip")
		target.outputStream().use { backups.export(it, passphrase) }
		return Exported(target, backups.canonicalJson)
	}

	/** Runs only the mail contributor's import, as restore does (one transaction, into an empty install). */
	private fun import(
		zip: Path,
		passphrase: String? = null,
	) = ZipBackupReader.open(zip, backups.canonicalJson, passphrase).use { reader ->
		transactions.executeWithoutResult { contributor.import(reader) }
	}

	private fun rows(sql: String): List<String?> = jdbc.queryForList(sql, String::class.java)

	private fun storedPassword(account: UUID): String? =
		jdbc.queryForObject("select password from mail_account where id = ?", String::class.java, account)

	/**
	 * An IMAP account with a password, two identities and three folders (one with sync state, one with an overridden
	 * role, one not selectable); a Google account with one folder; messages: [LINKED] (from a task), [DETACHED] (links
	 * to a note, no folder, no Message-ID), [REMINDED] (a reminder) and [UNLINKED]. With [links] false, nothing points
	 * to a message.
	 */
	private fun seed(links: Boolean = true) {
		account(IMAP, "IMAP_SMTP", password = PASSWORD, version = 3)
		account(GOOGLE, "GOOGLE", connection = CONNECTION)
		identity(id(20), IMAP, "Owner", "owner@example.test", position = 0, isDefault = true)
		identity(id(21), IMAP, "Owner (Verein)", "verein@example.test", position = 1, replyTo = "board@example.test", signature = "")
		folder(INBOX, IMAP, "INBOX", "INBOX", role = "INBOX", synced = true)
		folder(id(31), IMAP, "INBOX/Archiv 2025", "Archiv 2025", role = "ARCHIVE", overridden = true)
		folder(id(32), IMAP, "[Gmail]", "[Gmail]", selectable = false, delimiter = null)
		folder(ALL_MAIL, GOOGLE, "[Gmail]/All Mail", "All Mail", synced = true)
		message(LINKED, IMAP, INBOX, 17, "<invoice-17@example.test>", "Rechnung Oktober", version = 5)
		message(DETACHED, GOOGLE, null, null, null, "Lost message")
		message(REMINDED, GOOGLE, ALL_MAIL, 3, "<follow-up@work.example.test>", "Follow up")
		message(UNLINKED, IMAP, INBOX, 18, "<newsletter@example.test>", "Weekly newsletter")
		jdbc.update(
			"insert into mail_body (id, created_at, updated_at, version, message_id, text_plain, text_html, size_bytes, last_accessed_at) values (?, ?, ?, 0, ?, ?, ?, 10, ?)",
			id(60),
			ts(T0),
			ts(T0),
			LINKED,
			"Body of the invoice",
			"<p>HTML body</p>",
			ts(T0),
		)
		if (!links) return
		link("TASK", id(90), "EMAIL", LINKED)
		link("NOTE", id(91), "EMAIL", LINKED, origin = "SYNC")
		link("EMAIL", DETACHED, "NOTE", id(92))
		jdbc.update(
			"insert into reminder (id, created_at, updated_at, version, item_type, item_id, remind_at, channel) values (?, ?, ?, 0, 'EMAIL', ?, ?, 'NOTIFICATION')",
			id(95),
			ts(T0),
			ts(T0),
			REMINDED,
			ts(T2),
		)
	}

	private fun account(
		id: UUID,
		type: String,
		password: String? = null,
		connection: UUID? = null,
		version: Long = 0,
	) {
		jdbc.update(
			"""insert into mail_account (id, created_at, updated_at, version, type, display_name, email_address, imap_host, imap_port,
			imap_security, smtp_host, smtp_port, smtp_security, username, password, oauth_connection_id, signature_md, notify_new_mail,
			sync_window_months) values (?, ?, ?, ?, ?, ?, ?, ?, 993, 'SSL_TLS', ?, 587, 'STARTTLS', ?, ?, ?, ?, ?, ?)""",
			id,
			ts(T0),
			ts(T1),
			version,
			type,
			if (type == "IMAP_SMTP") "Privat «Zürich»" else "Work",
			if (type == "IMAP_SMTP") "owner@example.test" else "owner@work.example.test",
			if (type == "IMAP_SMTP") "imap.example.test" else "imap.gmail.com",
			if (type == "IMAP_SMTP") "smtp.example.test" else "smtp.gmail.com",
			if (type == "IMAP_SMTP") "owner" else "owner@work.example.test",
			password?.let(cipher::encrypt),
			connection,
			if (type == "IMAP_SMTP") "--\n**Owner**" else null,
			type != "IMAP_SMTP",
			if (type == "IMAP_SMTP") 6 else 12,
		)
	}

	private fun identity(
		id: UUID,
		account: UUID,
		name: String,
		email: String,
		position: Int,
		isDefault: Boolean = false,
		replyTo: String? = null,
		signature: String? = null,
	) {
		jdbc.update(
			"""insert into mail_identity (id, created_at, updated_at, version, account_id, name, email, reply_to, signature_md, is_default,
			position) values (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)""",
			id,
			ts(T0),
			ts(T1),
			account,
			name,
			email,
			replyTo,
			signature,
			isDefault,
			position,
		)
	}

	private fun folder(
		id: UUID,
		account: UUID,
		path: String,
		name: String,
		role: String? = null,
		overridden: Boolean = false,
		selectable: Boolean = true,
		delimiter: String? = "/",
		synced: Boolean = false,
	) {
		jdbc.update(
			"""insert into mail_folder (id, created_at, updated_at, version, account_id, path, name, delimiter, role, role_overridden,
			selectable, uidvalidity, uidnext, highestmodseq, last_synced_at) values (?, ?, ?, 2, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			id,
			ts(T0),
			ts(T1),
			account,
			path,
			name,
			delimiter,
			role,
			overridden,
			selectable,
			if (synced) 424242L else null,
			if (synced) 1717L else null,
			if (synced) 987654L else null,
			if (synced) ts(Instant.parse("2026-10-01T08:00:00Z")) else null,
		)
	}

	private fun message(
		id: UUID,
		account: UUID,
		folder: UUID?,
		uid: Long?,
		messageId: String?,
		subject: String,
		version: Long = 0,
	) {
		jdbc.update(
			"""insert into mail_message (id, created_at, updated_at, version, account_id, folder_id, uid, modseq, message_id_header, subject,
			from_addresses, to_addresses, snippet, thread_key, seen, flagged) values (?, ?, ?, ?, ?, ?, ?, 12, ?, ?, ?, ?, ?, ?, true, true)""",
			id,
			ts(T0),
			ts(T1),
			version,
			account,
			folder,
			uid,
			messageId,
			subject,
			"""[{"address":"billing@example.test","name":"Billing Dept"}]""",
			"""[{"address":"recipient@example.test"}]""",
			"Snippet of the invoice",
			"thread-$id",
		)
	}

	private fun link(
		sourceType: String,
		sourceId: UUID,
		targetType: String,
		targetId: UUID,
		origin: String = "MANUAL",
	) {
		jdbc.update(
			"""insert into link (id, created_at, updated_at, version, source_type, source_id, target_type, target_id, label, origin)
			values (?, ?, ?, 0, ?, ?, ?, ?, null, ?)""",
			UUID.randomUUID(),
			ts(T0),
			ts(T0),
			sourceType,
			sourceId,
			targetType,
			targetId,
			origin,
		)
	}

	private fun remote(
		uid: Long,
		messageId: String,
	) = RemoteMessage(
		uid = uid,
		messageId = messageId,
		inReplyTo = null,
		references = null,
		subject = "Rechnung Oktober (again)",
		from = listOf(MailAddress("billing@example.test", "Billing Dept")),
		to = listOf(MailAddress("owner@example.test")),
		cc = emptyList(),
		bcc = emptyList(),
		replyTo = emptyList(),
		sentAt = T2,
		receivedAt = T2,
		flags = RemoteFlags(seen = true),
		sizeBytes = 2048,
		hasAttachments = false,
		snippet = "Snippet",
	)

	companion object {
		private const val DATABASE = "mail_backup_contributor"
		private val dataDir: Path = Files.createTempDirectory("cockpit-mail-backup-data")

		private const val PASSWORD = "fictitious-imap-password"
		private const val PASSPHRASE = "fictitious backup passphrase"

		private val T0 = Instant.parse("2026-03-01T08:15:30.123456Z")
		private val T1 = Instant.parse("2026-03-02T09:00:00Z")
		private val T2 = Instant.parse("2026-03-03T10:00:00Z")

		private fun ts(instant: Instant) = Timestamp.from(instant)

		/** Fictitious UUIDv7-shaped ids, ordered by [n]. */
		private fun id(n: Int): UUID = UUID.fromString("0192f000-0000-7000-8000-%012d".format(n))

		private val IMAP = id(1)
		private val GOOGLE = id(2)
		private val CONNECTION = id(9)
		private val INBOX = id(30)
		private val ALL_MAIL = id(33)
		private val LINKED = id(40)
		private val DETACHED = id(41)
		private val REMINDED = id(42)
		private val UNLINKED = id(43)

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) = RestoreTestDatabases.register(registry, DATABASE, dataDir)
	}
}
