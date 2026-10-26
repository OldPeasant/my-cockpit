package ch.sonensei.cockpit.contacts.backup

import ch.sonensei.cockpit.backup.RestoreTestDatabases
import ch.sonensei.cockpit.backup.Zips
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.backup.infra.ZipBackupReader
import ch.sonensei.cockpit.contacts.domain.AddressesConverter
import ch.sonensei.cockpit.contacts.domain.ContactAddress
import ch.sonensei.cockpit.contacts.domain.ContactEmail
import ch.sonensei.cockpit.contacts.domain.ContactPhone
import ch.sonensei.cockpit.contacts.domain.EmailsConverter
import ch.sonensei.cockpit.contacts.domain.PhonesConverter
import ch.sonensei.cockpit.core.crypto.SecretCipher
import ch.sonensei.cockpit.files.infra.BlobStore
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.VCardVersion
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
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
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes

/**
 * [ContactsBackupContributor] (SPEC.md §8.3, §10.2): `data/contacts.jsonl`, the vCard mirror
 * `readable/contacts/contacts.vcf` and the restore of rows and search documents.
 *
 * Rows are inserted with JDBC so ids, times, versions and sources are under the test's control. Runs against a
 * database of its own, because every test empties all tables. All data is fictitious.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension::class)
class ContactsBackupContributorTests(
	@Autowired private val backups: BackupService,
	@Autowired private val contributor: ContactsBackupContributor,
	@Autowired private val blobs: BlobStore,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactions: TransactionTemplate,
	@Autowired private val cipher: SecretCipher,
) {
	@TempDir lateinit var work: Path

	@BeforeEach
	fun wipe() = RestoreTestDatabases.wipe(jdbc, dataDir)

	// --- data/contacts.jsonl ----------------------------------------------------------------------------------------

	@Test
	fun `contacts jsonl holds every contact with all fields, the ones in the Trash and of external sources included`() {
		full(id(1))
		contact(id(2), "Former colleague", deletedAt = T2, emails = """[{"address":"old@example.test"}]""")
		contact(id(3), "Sam Synced", source = "GOOGLE", externalId = "people/c0000000000000000001")

		val contacts = export().lines("data/contacts.jsonl")

		contacts.map { it["id"].asString() } shouldContainExactly listOf(id(1), id(2), id(3)).map(UUID::toString)
		val zoe = contacts[0]
		zoe["displayName"].asString() shouldBe "Zoë Müller-Exemple"
		zoe["givenName"].asString() shouldBe "Zoë"
		zoe["familyName"].asString() shouldBe "Müller-Exemple"
		zoe["emails"].values().map { it["address"].asString() to it["label"].asString() } shouldContainExactly
			listOf("zoe@example.test" to "work", "zoe.home@example.test" to "home")
		zoe["phones"].values().map { it["number"].asString() } shouldContainExactly listOf("+41 44 000 00 00", "044 000 00 01")
		zoe["phones"][1]["label"].isNull shouldBe true
		zoe["addresses"][0]["street"].asString() shouldBe "Rue du Lac 1"
		zoe["addresses"][0]["postalCode"].asString() shouldBe "1000"
		zoe["addresses"][1]["city"].asString() shouldBe "Zürich"
		zoe["organization"].asString() shouldBe "Example «Labs» AG"
		zoe["title"].asString() shouldBe "Engineer"
		zoe["birthday"].asString() shouldBe "1990-02-28"
		zoe["notesMd"].asString() shouldBe "Met at the *meetup*\n\n- likes tea ☕"
		zoe["photoFileId"].asString() shouldBe id(41).toString()
		zoe["createdAt"].asString() shouldBe T0.toString()
		zoe["updatedAt"].asString() shouldBe T1.toString()
		zoe["version"].asLong() shouldBe 3
		zoe["source"].asString() shouldBe "LOCAL"
		zoe["deletedAt"].isNull shouldBe true
		zoe["externalId"].isNull shouldBe true
		contacts[1]["deletedAt"].asString() shouldBe T2.toString()
		contacts[1]["givenName"].isNull shouldBe true
		contacts[1]["birthday"].isNull shouldBe true
		contacts[1]["phones"].size() shouldBe 0
		contacts[2]["source"].asString() shouldBe "GOOGLE"
		contacts[2]["externalId"].asString() shouldBe "people/c0000000000000000001"
	}

	@Test
	fun `contacts jsonl is present and empty when there are no contacts`() {
		val exported = export()

		exported.lines("data/contacts.jsonl").shouldBeEmpty()
		exported.entryNames.filter { it.startsWith("readable/contacts/") }.shouldBeEmpty()
	}

	// --- readable/contacts/contacts.vcf -----------------------------------------------------------------------------

	@Test
	fun `the vcf parses back into one vCard 3_0 per contact not in the Trash, sorted by name, with its fields`() {
		full(id(1))
		contact(id(2), "Alex Example", emails = """[{"address":"alex@example.test"}]""")
		contact(id(3), "Former colleague", deletedAt = T2)
		contact(id(4), "Sam Synced", source = "GOOGLE", externalId = "people/c0000000000000000001")

		val cards = export().vcards()

		cards shouldHaveSize 3
		cards.forEach { it.version shouldBe VCardVersion.V3_0 }
		cards.map { it.formattedName.value } shouldContainExactly listOf("Alex Example", "Sam Synced", "Zoë Müller-Exemple")
		cards.map { it.uid.value } shouldContainExactly listOf(id(2), id(4), id(1)).map { "urn:uuid:$it" }
		val zoe = cards[2]
		zoe.structuredName.given shouldBe "Zoë"
		zoe.structuredName.family shouldBe "Müller-Exemple"
		zoe.emails.map { it.value } shouldContainExactly listOf("zoe@example.test", "zoe.home@example.test")
		zoe.emails[0].types.map { it.value.lowercase() } shouldContain "work"
		zoe.telephoneNumbers.map { it.text } shouldContainExactly listOf("+41 44 000 00 00", "044 000 00 01")
		zoe.addresses.map { it.locality } shouldContainExactly listOf("Lausanne", "Zürich")
		zoe.addresses[0].streetAddress shouldBe "Rue du Lac 1"
		zoe.organization.values shouldContainExactly listOf("Example «Labs» AG")
		zoe.titles.single().value shouldBe "Engineer"
		zoe.birthday.date.toString() shouldBe "1990-02-28"
		zoe.notes.single().value shouldBe "Met at the *meetup*\n\n- likes tea ☕"
		cards[0].emails.map { it.value } shouldContainExactly listOf("alex@example.test")
	}

	@Test
	fun `a photo is a PHOTO uri that resolves to the photo's file entry in the backup, also with spaces and brackets`() {
		val people = folder(id(40), "People photos")
		file(id(41), "Zoë portrait (1).png", people, "image/png")
		file(id(42), "alex.jpg", people, "image/jpeg")
		full(id(1))
		contact(id(2), "Alex Example", photo = id(42))

		val exported = export()
		val cards = exported.vcards()

		cards shouldHaveSize 2
		cards.forEach { card ->
			val url = card.photos.single().url
			url shouldStartWith "../../files/"
			// Relative to readable/contacts/contacts.vcf, the URI must name an entry of the zip.
			val entry = URI("readable/contacts/contacts.vcf").resolve(URI(url)).path
			exported.entryNames shouldContain entry
		}
		URI("readable/contacts/").resolve(URI(cards[1].photos.single().url)).path shouldBe "files/People photos/Zoë portrait (1).png"
		cards[1]
			.photos
			.single()
			.contentType
			?.extension shouldBe "png"
		cards[0]
			.photos
			.single()
			.contentType
			?.extension shouldBe "jpg"
		cards[1].photos.single().url shouldBe "../../files/People%20photos/Zo%C3%AB%20portrait%20%281%29.png"
	}

	@Test
	fun `a photo whose file name has no or an unknown extension is still a PHOTO uri to its file entry, without type`() {
		val people = folder(id(40), "People photos")
		file(id(41), "portrait", people, "application/octet-stream")
		file(id(42), "alex.unknownext", people, "application/octet-stream")
		contact(id(1), "Alex Example", photo = id(42))
		contact(id(2), "Blake Example", photo = id(41))

		val exported = export()
		val photos = exported.vcards().map { it.photos.single() }

		photos.map { URI("readable/contacts/contacts.vcf").resolve(URI(it.url)).path } shouldContainExactly
			listOf("files/People photos/alex.unknownext", "files/People photos/portrait")
		photos.forEach { it.url shouldStartWith "../../files/" }
		photos[1].contentType.shouldBeNull()
	}

	@Test
	fun `a photo whose file is missing leaves the card without PHOTO and keeps the file id out of the mirror`() {
		contact(id(1), "Alex Example", photo = id(99))

		val exported = export()

		exported
			.vcards()
			.single()
			.photos
			.shouldBeEmpty()
		exported.text("readable/contacts/contacts.vcf") shouldNotContain id(99).toString()
		exported.lines("data/contacts.jsonl").single()["photoFileId"].asString() shouldBe id(99).toString()
	}

	@Test
	fun `there is no vcf when every contact is in the Trash`() {
		contact(id(1), "Former colleague", deletedAt = T2)

		val exported = export()

		exported.entries shouldNotContainKey "readable/contacts/contacts.vcf"
		exported.lines("data/contacts.jsonl") shouldHaveSize 1
	}

	@Test
	fun `the vcf has no PRODID and writes a contact with only a name as a card with FN and UID`() {
		contact(id(1), "Acme Corp", organization = "Acme Corp")

		val exported = export()
		val text = exported.text("readable/contacts/contacts.vcf")
		val card = exported.vcards().single()

		text shouldNotContain "PRODID"
		card.formattedName.value shouldBe "Acme Corp"
		card.uid.value shouldBe "urn:uuid:${id(1)}"
		card.emails.shouldBeEmpty()
		card.birthday.shouldBeNull()
	}

	// --- import -----------------------------------------------------------------------------------------------------

	@Test
	fun `import restores every contact with its id, times, version, Trash state, source and external id`() {
		full(id(1))
		contact(id(2), "Former colleague", deletedAt = T2)
		contact(id(3), "Sam Synced", source = "GOOGLE", externalId = "people/c0000000000000000001")
		val zip = export()
		val contacts = rows()
		wipe()

		import(zip.path)

		rows() shouldBe contacts
		contacts shouldHaveSize 3
	}

	@Test
	fun `import indexes the contacts not in the Trash for search, the trashed ones not`() {
		full(id(1))
		contact(id(2), "Former colleague", deletedAt = T2, emails = """[{"address":"old@example.test"}]""")
		contact(id(3), "Sam Synced", source = "GOOGLE", externalId = "people/c0000000000000000001")
		val zip = export()
		wipe()

		import(zip.path)

		val documents = jdbc.queryForList("select item_type, item_id, title, body from search_document order by item_id")
		documents.map { it["item_id"] } shouldContainExactlyInAnyOrder listOf(id(1), id(3))
		documents.forEach { it["item_type"] shouldBe "CONTACT" }
		documents[0]["title"] shouldBe "Zoë Müller-Exemple"
		(documents[0]["body"] as String).lines() shouldContain "zoe@example.test"
		(documents[0]["body"] as String).lines() shouldContain "Lausanne"
		matches("zoe.home@example.test") shouldContainExactly listOf(id(1))
		matches("Synced") shouldContainExactly listOf(id(3))
		matches("colleague").shouldBeEmpty()
	}

	// --- CardDAV accounts and links ---------------------------------------------------------------------------------

	@Test
	fun `CardDAV accounts and links are exported without passwords, sync tokens or sync status`() {
		seedCardDav()

		val exported = export()
		val accounts = exported.json("data/contacts_carddav_accounts.json")
		val links = exported.lines("data/contacts_carddav_links.jsonl")

		accounts.map { it["id"].asString() } shouldContainExactly listOf(HOME_ACCOUNT, OPEN_ACCOUNT).map(UUID::toString)
		accounts[0]["name"].asString() shouldBe "Home server"
		accounts[0]["url"].asString() shouldBe "https://dav.example.test/dav/"
		accounts[0]["username"].asString() shouldBe "alice"
		accounts[0]["addressbookUrl"].asString() shouldBe ADDRESSBOOK
		accounts[1]["name"].asString() shouldBe "Open server"
		accounts[1].opt("username").shouldBeNull()
		accounts[1].opt("addressbookUrl").shouldBeNull()
		accounts.forEach { account ->
			listOf("password", "syncToken", "lastAttemptAt", "lastSuccessAt", "lastError").forEach { field ->
				withClue(field) { account.has(field) shouldBe false }
			}
		}

		links.map { it["id"].asString() } shouldContainExactly listOf(id(20), id(21)).map(UUID::toString)
		links[0]["contactId"].asString() shouldBe id(1).toString()
		links[0]["accountId"].asString() shouldBe HOME_ACCOUNT.toString()
		links[0]["href"].asString() shouldBe "/dav/alice/contacts/card-0001.vcf"
		links[0]["etag"].asString() shouldBe "\"etag-1\""
		links[0]["syncedVersion"].asLong() shouldBe 3
		links[1].opt("contactId").shouldBeNull()
		links[1]["href"].asString() shouldBe "/dav/alice/contacts/card-0002.vcf"
		links[1]["syncedVersion"].asLong() shouldBe 5

		exported.entryNames.filter { it.startsWith("data/secrets") }.shouldBeEmpty()
		exported.texts().forEach { (name, text) ->
			withClue(name) {
				text shouldNotContain CARDDAV_PASSWORD
				text shouldNotContain "https://dav.example.test/sync/42"
				text shouldNotContain "fictitious failure"
			}
		}
	}

	@Test
	fun `with a passphrase the CardDAV password is only in the encrypted secrets, neither in plain text nor as stored`() {
		seedCardDav()
		val stored =
			jdbc.queryForObject("select password from contacts_carddav_account where id = ?", String::class.java, HOME_ACCOUNT)!!

		val exported = export(PASSPHRASE)

		exported.entryNames shouldContain "data/secrets.enc.json"
		exported.texts().forEach { (name, text) ->
			withClue(name) {
				text shouldNotContain CARDDAV_PASSWORD
				text shouldNotContain stored
			}
		}
		exported.json("data/contacts_carddav_accounts.json").forEach { it.has("password") shouldBe false }
	}

	@Test
	fun `import restores CardDAV accounts and links, the password only with the passphrase, and no sync state`() {
		seedCardDav()
		val withSecrets = export(PASSPHRASE)
		val links = jdbc.queryForList("select * from contacts_carddav_link order by id")

		wipe()
		import(withSecrets.path, PASSPHRASE)

		cipher.decrypt(carddavPassword(HOME_ACCOUNT).shouldNotBeNull()) shouldBe CARDDAV_PASSWORD
		carddavPassword(OPEN_ACCOUNT).shouldBeNull()
		val home = jdbc.queryForMap("select * from contacts_carddav_account where id = ?", HOME_ACCOUNT)
		home["name"] shouldBe "Home server"
		home["url"] shouldBe "https://dav.example.test/dav/"
		home["username"] shouldBe "alice"
		home["addressbook_url"] shouldBe ADDRESSBOOK
		home["sync_token"].shouldBeNull()
		home["last_attempt_at"].shouldBeNull()
		home["last_success_at"].shouldBeNull()
		home["last_error"].shouldBeNull()
		val open = jdbc.queryForMap("select * from contacts_carddav_account where id = ?", OPEN_ACCOUNT)
		open["username"].shouldBeNull()
		open["addressbook_url"].shouldBeNull()
		jdbc.queryForList("select * from contacts_carddav_link order by id") shouldBe links
		links shouldHaveSize 2

		wipe()
		import(withSecrets.path)

		carddavPassword(HOME_ACCOUNT).shouldBeNull()
		jdbc.queryForObject("select count(*) from contacts_carddav_account", Int::class.java) shouldBe 2
		jdbc.queryForList("select * from contacts_carddav_link order by id") shouldBe links
	}

	@Test
	fun `a backup without passphrase restores the accounts without password and logs no secret`(output: CapturedOutput) {
		seedCardDav()
		val withoutSecrets = export()

		wipe()
		import(withoutSecrets.path)

		carddavPassword(HOME_ACCOUNT).shouldBeNull()
		carddavPassword(OPEN_ACCOUNT).shouldBeNull()
		jdbc.queryForMap("select * from contacts_carddav_account where id = ?", HOME_ACCOUNT)["addressbook_url"] shouldBe ADDRESSBOOK
		output.out shouldContain "Restored 2 CardDAV account(s) without a password"
		output.out shouldNotContain CARDDAV_PASSWORD
	}

	@Test
	fun `restore warnings tell that CardDAV accounts need their password, singular and plural`() {
		seedCardDav()

		warnings(export(PASSPHRASE)) shouldContainExactly
			listOf(
				"Without the backup's passphrase, 2 CardDAV contacts accounts must be added again if they need a password " +
					"(it is in the secrets)",
			)
		warnings(export()) shouldContainExactly
			listOf("The backup has no CardDAV passwords: 2 CardDAV contacts accounts must be added again if they need one")

		jdbc.update("delete from contacts_carddav_account where id = ?", OPEN_ACCOUNT)
		warnings(export()) shouldContainExactly
			listOf("The backup has no CardDAV passwords: 1 CardDAV contacts account must be added again if they need one")
	}

	@Test
	fun `without CardDAV accounts there are no CardDAV files and no restore warnings`() {
		contact(id(1), "Alex Example")

		val exported = export(PASSPHRASE)

		exported.entryNames.filter { it.contains("carddav") }.shouldBeEmpty()
		warnings(exported).shouldBeEmpty()
		wipe()
		import(exported.path, PASSPHRASE)
		jdbc.queryForObject("select count(*) from contacts_carddav_account", Int::class.java) shouldBe 0
		rows() shouldHaveSize 1
	}

	@Test
	fun `an account without links writes an empty links file and restores without links`() {
		carddavAccount(OPEN_ACCOUNT, "Open server", "http://localhost:5232/", null, null, null)

		val exported = export()

		exported.lines("data/contacts_carddav_links.jsonl").shouldBeEmpty()
		wipe()
		import(exported.path)
		jdbc.queryForObject("select count(*) from contacts_carddav_account", Int::class.java) shouldBe 1
		jdbc.queryForObject("select count(*) from contacts_carddav_link", Int::class.java) shouldBe 0
	}

	// --- helpers ----------------------------------------------------------------------------------------------------

	private class Exported(
		val path: Path,
		private val json: tools.jackson.databind.json.JsonMapper,
	) {
		val entries: Map<String, ByteArray> = Zips.read(path.readBytes())

		val entryNames: Set<String> get() = entries.keys

		fun text(name: String): String = String(entries.getValue(name), Charsets.UTF_8)

		fun texts(): Map<String, String> = entries.mapValues { String(it.value, Charsets.UTF_8) }

		fun json(name: String): List<JsonNode> = json.readTree(entries.getValue(name)).values().toList()

		fun lines(name: String): List<JsonNode> = text(name).lines().filter { it.isNotBlank() }.map { json.readTree(it) }

		fun vcards(): List<VCard> = Ezvcard.parse(text("readable/contacts/contacts.vcf")).all()
	}

	private fun export(passphrase: String? = null): Exported {
		val target = Files.createTempFile(work, "backup-", ".zip")
		target.outputStream().use { backups.export(it, passphrase) }
		return Exported(target, backups.canonicalJson)
	}

	/** Runs only the contacts contributor's import, as restore does (one transaction, into an empty install). */
	private fun import(
		zip: Path,
		passphrase: String? = null,
	) = ZipBackupReader.open(zip, backups.canonicalJson, passphrase).use { reader ->
		transactions.executeWithoutResult { contributor.import(reader) }
	}

	private fun warnings(exported: Exported): List<String> =
		ZipBackupReader.open(exported.path, backups.canonicalJson).use { contributor.restoreWarnings(it) }

	/** The field's value, or `null` when it is missing or JSON null. */
	private fun JsonNode.opt(name: String): JsonNode? = get(name)?.takeUnless { it.isNull }

	/**
	 * Two CardDAV accounts (one with user name, password, address book and sync state, one without), a CARDDAV contact
	 * linked to a card and the tombstone link of a purged contact.
	 */
	private fun seedCardDav() {
		contact(id(1), "Robin Carddav", source = "CARDDAV", externalId = ADDRESSBOOK + "card-0001.vcf")
		carddavAccount(HOME_ACCOUNT, "Home server", "https://dav.example.test/dav/", "alice", CARDDAV_PASSWORD, ADDRESSBOOK)
		jdbc.update(
			"""update contacts_carddav_account set sync_token = 'https://dav.example.test/sync/42', last_attempt_at = ?,
			last_success_at = ?, last_error = 'fictitious failure' where id = ?""",
			Timestamp.from(T1),
			Timestamp.from(T0),
			HOME_ACCOUNT,
		)
		carddavAccount(OPEN_ACCOUNT, "Open server", "http://localhost:5232/", null, null, null)
		carddavLink(id(20), id(1), HOME_ACCOUNT, "/dav/alice/contacts/card-0001.vcf", "\"etag-1\"", 3)
		carddavLink(id(21), null, HOME_ACCOUNT, "/dav/alice/contacts/card-0002.vcf", "\"etag-2\"", 5)
	}

	private fun carddavAccount(
		id: UUID,
		name: String,
		url: String,
		username: String?,
		password: String?,
		addressbookUrl: String?,
	) {
		jdbc.update(
			"""insert into contacts_carddav_account (id, name, url, username, password, addressbook_url)
			values (?, ?, ?, ?, ?, ?)""",
			id,
			name,
			url,
			username,
			password?.let(cipher::encrypt),
			addressbookUrl,
		)
	}

	private fun carddavLink(
		id: UUID,
		contactId: UUID?,
		accountId: UUID,
		href: String,
		etag: String?,
		syncedVersion: Long,
	) {
		jdbc.update(
			"""insert into contacts_carddav_link (id, contact_id, account_id, href, etag, synced_version)
			values (?, ?, ?, ?, ?, ?)""",
			id,
			contactId,
			accountId,
			href,
			etag,
			syncedVersion,
		)
	}

	private fun carddavPassword(account: UUID): String? =
		jdbc.queryForObject("select password from contacts_carddav_account where id = ?", String::class.java, account)

	/** The ids of the search documents whose text search matches [word]. */
	private fun matches(word: String): List<UUID> =
		jdbc
			.queryForList(
				"select item_id from search_document where tsv @@ plainto_tsquery('simple', ?) order by item_id",
				UUID::class.java,
				word,
			).filterNotNull()

	/**
	 * A contact with every field set: several labelled emails, phones and addresses, unicode, notes, a birthday. The
	 * lists are stored as the app stores them (its JPA converters, unset fields as `null`).
	 */
	private fun full(id: UUID) =
		contact(
			id,
			"Zoë Müller-Exemple",
			givenName = "Zoë",
			familyName = "Müller-Exemple",
			emails =
				EmailsConverter().convertToDatabaseColumn(
					listOf(ContactEmail("zoe@example.test", "work"), ContactEmail("zoe.home@example.test", "home")),
				),
			phones = PhonesConverter().convertToDatabaseColumn(listOf(ContactPhone("+41 44 000 00 00", "mobile"), ContactPhone("044 000 00 01"))),
			addresses =
				AddressesConverter().convertToDatabaseColumn(
					listOf(ContactAddress("home", "Rue du Lac 1", "Lausanne", "VD", "1000", "Switzerland"), ContactAddress(city = "Zürich")),
				),
			organization = "Example «Labs» AG",
			title = "Engineer",
			birthday = LocalDate.parse("1990-02-28"),
			notes = "Met at the *meetup*\n\n- likes tea ☕",
			photo = id(41),
		)

	private fun contact(
		id: UUID,
		displayName: String,
		givenName: String? = null,
		familyName: String? = null,
		emails: String = "[]",
		phones: String = "[]",
		addresses: String = "[]",
		organization: String? = null,
		title: String? = null,
		birthday: LocalDate? = null,
		notes: String? = null,
		photo: UUID? = null,
		source: String = "LOCAL",
		externalId: String? = null,
		deletedAt: Instant? = null,
	) {
		jdbc.update(
			"""insert into contact (id, created_at, updated_at, version, deleted_at, display_name, given_name, family_name,
			emails, phones, addresses, organization, title, birthday, notes_md, photo_file_id, source, external_id)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)""",
			id,
			Timestamp.from(T0),
			Timestamp.from(T1),
			3,
			deletedAt?.let(Timestamp::from),
			displayName,
			givenName,
			familyName,
			emails,
			phones,
			addresses,
			organization,
			title,
			birthday?.let(Date::valueOf),
			notes,
			photo,
			source,
			externalId,
		)
	}

	private fun folder(
		id: UUID,
		name: String,
	): UUID {
		jdbc.update(
			"insert into folder (id, created_at, updated_at, version, parent_id, name) values (?, ?, ?, ?, ?, ?)",
			id,
			Timestamp.from(T0),
			Timestamp.from(T0),
			0,
			null,
			name,
		)
		return id
	}

	private fun file(
		id: UUID,
		name: String,
		folder: UUID,
		mime: String,
	) {
		val blob = blobs.write("img".byteInputStream())
		jdbc.update(
			"""insert into file (id, created_at, updated_at, version, folder_id, name, mime, size, sha256, storage_key)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			id,
			Timestamp.from(T0),
			Timestamp.from(T0),
			0,
			folder,
			name,
			mime,
			blob.size,
			blob.sha256,
			blob.key,
		)
	}

	private fun rows() = jdbc.queryForList("select * from contact order by id")

	companion object {
		private const val DATABASE = "contacts_backup_contributor"
		private val dataDir: Path = Files.createTempDirectory("cockpit-contacts-backup-data")

		/** Runs also when a test failed, so no run leaves its data directory behind. */
		@JvmStatic
		@AfterAll
		fun deleteDataDir() {
			dataDir.toFile().deleteRecursively()
		}

		private val T0 = Instant.parse("2026-03-01T08:15:30.123456Z")
		private val T1 = Instant.parse("2026-03-02T09:00:00Z")
		private val T2 = Instant.parse("2026-03-03T10:00:00Z")

		private const val PASSPHRASE = "fictitious backup passphrase"
		private const val CARDDAV_PASSWORD = "fictitious-carddav-pw-4K"
		private const val ADDRESSBOOK = "https://dav.example.test/dav/alice/contacts/"
		private val HOME_ACCOUNT: UUID = UUID.fromString("0192f000-0000-7000-8000-000000000010")
		private val OPEN_ACCOUNT: UUID = UUID.fromString("0192f000-0000-7000-8000-000000000011")

		/** Fictitious UUIDv7-shaped ids, ordered by [n]. */
		private fun id(n: Int): UUID = UUID.fromString("0192f000-0000-7000-8000-%012d".format(n))

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) = RestoreTestDatabases.register(registry, DATABASE, dataDir)
	}
}
