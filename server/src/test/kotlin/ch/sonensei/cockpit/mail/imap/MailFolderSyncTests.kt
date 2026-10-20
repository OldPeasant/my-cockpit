package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.core.web.PreconditionFailedException
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.mail.Folder
import jakarta.mail.MessagingException
import jakarta.persistence.EntityManager
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Folder sync against a scripted IMAP folder list (SPECIAL-USE attributes, which GreenMail does not send) with the real
 * database: role moves under the unique (account, role) index, overrides, INBOX, `\Noselect` and MAIL_FOLDER events.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@RecordApplicationEvents
class MailFolderSyncTests(
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val changes: ChangeEventPublisher,
	@Autowired private val transactions: TransactionTemplate,
	@Autowired private val entityManager: EntityManager,
) : PostgresTestBase() {
	private val pool = mockk<ImapConnectionPool>()
	private val store = mockk<IMAPStore>()
	private val root = mockk<Folder>()
	private lateinit var service: MailFolderService
	private lateinit var account: MailAccount

	/** What the server lists next: path to attributes; `/` is the delimiter. */
	private var remote: List<Pair<String, List<String>>> = emptyList()

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from mail_account")
		account =
			accounts.saveAndFlush(
				MailAccount(
					type = MailAccountType.IMAP_SMTP,
					displayName = "Work",
					emailAddress = "jane.doe@example.test",
					imapHost = "imap.example.test",
					imapPort = 993,
					imapSecurity = MailSecurity.SSL_TLS,
					smtpHost = "smtp.example.test",
					smtpPort = 465,
					smtpSecurity = MailSecurity.SSL_TLS,
					username = "jane",
				).apply { password = EncryptedSecret("fictitious-secret-1") },
			)
		service = MailFolderService(accounts, folders, pool, changes, transactions, entityManager)
		every { pool.withStore(any(), any<(IMAPStore) -> Any?>()) } answers { secondArg<(IMAPStore) -> Any?>().invoke(store) }
		every { store.defaultFolder } returns root
		every { root.list("*") } answers { remote.map { (path, attributes) -> imapFolder(path, attributes) }.toTypedArray() }
	}

	private fun imapFolder(
		path: String,
		attributes: List<String>,
	): IMAPFolder =
		mockk<IMAPFolder>().also {
			every { it.fullName } returns path
			every { it.name } returns path.substringAfterLast('/')
			every { it.separator } returns '/'
			every { it.attributes } returns attributes.toTypedArray()
		}

	private fun sync(vararg listed: Pair<String, List<String>>): Map<String, MailFolderRole?> {
		remote = listed.toList()
		return service.sync(account.id).associate { it.path to it.role }
	}

	private fun stored() = folders.findByAccountIdOrderByPathAsc(account.id).associate { it.path to it.role }

	private fun folder(path: String) = checkNotNull(folders.findByAccountIdAndPath(account.id, path))

	private fun ApplicationEvents.folderEvents() =
		stream(ChangeEvent::class.java).toList().filter { it.type == MailFolderService.FOLDER_EVENT }.map { it.id to it.op }

	@Test
	fun `SPECIAL-USE attributes win over names and nested folders`() {
		val roles =
			sync(
				"INBOX" to emptyList(),
				"Gesendet" to listOf("\\Sent"),
				"Sent" to emptyList(),
				"[Gmail]/All Mail" to listOf("\\HasNoChildren", "\\All"),
				"[Gmail]/Spam" to listOf("\\Junk"),
				"Papierkorb" to emptyList(),
			)

		roles shouldBe
			mapOf(
				"INBOX" to MailFolderRole.INBOX,
				"Gesendet" to MailFolderRole.SENT,
				"Papierkorb" to MailFolderRole.TRASH,
				"Sent" to null,
				"[Gmail]/All Mail" to MailFolderRole.ARCHIVE,
				"[Gmail]/Spam" to MailFolderRole.SPAM,
			)
		stored() shouldBe roles
		folder("[Gmail]/All Mail").let {
			it.name shouldBe "All Mail"
			it.delimiter shouldBe "/"
		}
	}

	@Test
	fun `INBOX is added when the server leaves it out of the list`() {
		sync("Sent" to listOf("\\Sent")) shouldBe mapOf("INBOX" to MailFolderRole.INBOX, "Sent" to MailFolderRole.SENT)
		folder("INBOX").delimiter shouldBe null
	}

	@Test
	fun `a lower-case inbox is not duplicated`() {
		sync("inbox" to emptyList()) shouldBe mapOf("inbox" to MailFolderRole.INBOX)
	}

	@Test
	fun `a Noselect folder is stored as not selectable and gets no role`() {
		sync("INBOX" to emptyList(), "Archive" to listOf("\\Noselect", "\\HasChildren"), "Archive/2026" to emptyList())

		folder("Archive").let {
			it.selectable shouldBe false
			it.role shouldBe null
		}
		folder("Archive/2026").selectable shouldBe true
	}

	@Test
	fun `a role that moves to another folder on resync does not violate the unique role index`(events: ApplicationEvents) {
		sync("INBOX" to emptyList(), "Sent" to listOf("\\Sent"), "Sent Items" to emptyList())
		val sent = folder("Sent")
		val sentItems = folder("Sent Items")
		events.clear()

		sync("INBOX" to emptyList(), "Sent" to emptyList(), "Sent Items" to listOf("\\Sent")) shouldBe
			mapOf("INBOX" to MailFolderRole.INBOX, "Sent" to null, "Sent Items" to MailFolderRole.SENT)

		stored()["Sent Items"] shouldBe MailFolderRole.SENT
		stored()["Sent"] shouldBe null
		events.folderEvents() shouldContainExactlyInAnyOrder listOf(sent.id to ChangeOp.UPDATED, sentItems.id to ChangeOp.UPDATED)
	}

	@Test
	fun `two folders can swap their roles in one sync`() {
		sync("INBOX" to emptyList(), "A" to listOf("\\Sent"), "B" to listOf("\\Drafts"))

		sync("INBOX" to emptyList(), "A" to listOf("\\Drafts"), "B" to listOf("\\Sent"))

		stored() shouldBe mapOf("INBOX" to MailFolderRole.INBOX, "A" to MailFolderRole.DRAFTS, "B" to MailFolderRole.SENT)
	}

	@Test
	fun `the role of a vanished folder goes to a new folder in the same sync`(events: ApplicationEvents) {
		sync("INBOX" to emptyList(), "Junk" to listOf("\\Junk"))
		val junk = folder("Junk")
		events.clear()

		sync("INBOX" to emptyList(), "Spam" to listOf("\\Junk"))

		stored() shouldBe mapOf("INBOX" to MailFolderRole.INBOX, "Spam" to MailFolderRole.SPAM)
		events.folderEvents() shouldContainExactlyInAnyOrder listOf(junk.id to ChangeOp.DELETED, folder("Spam").id to ChangeOp.CREATED)
	}

	@Test
	fun `the role of a vanished folder goes to an existing folder in the same sync`() {
		sync("INBOX" to emptyList(), "Junk" to listOf("\\Junk"), "Spam" to emptyList())
		stored()["Spam"] shouldBe null

		sync("INBOX" to emptyList(), "Spam" to emptyList())

		stored() shouldBe mapOf("INBOX" to MailFolderRole.INBOX, "Spam" to MailFolderRole.SPAM)
	}

	@Test
	fun `the first sync publishes CREATED per folder and an unchanged resync publishes nothing`(events: ApplicationEvents) {
		sync("INBOX" to emptyList(), "Sent" to listOf("\\Sent"))
		events.folderEvents().map { it.second } shouldBe listOf(ChangeOp.CREATED, ChangeOp.CREATED)
		events.clear()

		sync("INBOX" to emptyList(), "Sent" to listOf("\\Sent"))

		events.folderEvents().shouldBeEmpty()
	}

	@Test
	fun `a changed Noselect flag updates the folder`(events: ApplicationEvents) {
		sync("INBOX" to emptyList(), "Projects" to emptyList())
		events.clear()

		sync("INBOX" to emptyList(), "Projects" to listOf("\\Noselect"))

		folder("Projects").selectable shouldBe false
		events.folderEvents() shouldBe listOf(folder("Projects").id to ChangeOp.UPDATED)
	}

	@Test
	fun `a manual role survives a sync whose SPECIAL-USE says otherwise`() {
		sync("INBOX" to emptyList(), "Archive" to listOf("\\Archive"), "Old Mail" to emptyList())
		val oldMail = folder("Old Mail")
		service.overrideRole(account.id, oldMail.id, MailFolderRole.ARCHIVE, automatic = false, ifMatch = "\"${oldMail.version}\"")

		sync("INBOX" to emptyList(), "Archive" to listOf("\\Archive"), "Old Mail" to emptyList())

		stored()["Old Mail"] shouldBe MailFolderRole.ARCHIVE
		stored()["Archive"] shouldBe null
		folder("Old Mail").roleOverridden shouldBe true
	}

	@Test
	fun `a manual 'no role' is kept on the next sync`() {
		sync("INBOX" to emptyList(), "Sent" to listOf("\\Sent"))
		val sent = folder("Sent")
		service.overrideRole(account.id, sent.id, null, automatic = false, ifMatch = "\"${sent.version}\"")

		sync("INBOX" to emptyList(), "Sent" to listOf("\\Sent"))

		stored()["Sent"] shouldBe null
	}

	@Test
	fun `handing a folder back to detection recomputes its role on the next sync`() {
		sync("INBOX" to emptyList(), "Archive" to listOf("\\Archive"), "Old Mail" to emptyList())
		val oldMail = folder("Old Mail")
		service.overrideRole(account.id, oldMail.id, MailFolderRole.ARCHIVE, automatic = false, ifMatch = "\"${oldMail.version}\"")
		sync("INBOX" to emptyList(), "Archive" to listOf("\\Archive"), "Old Mail" to emptyList())

		val current = folder("Old Mail")
		service.overrideRole(account.id, oldMail.id, null, automatic = true, ifMatch = "\"${current.version}\"").let {
			it.roleOverridden shouldBe false
			it.role shouldBe MailFolderRole.ARCHIVE
		}
		sync("INBOX" to emptyList(), "Archive" to listOf("\\Archive"), "Old Mail" to emptyList())

		stored()["Archive"] shouldBe MailFolderRole.ARCHIVE
		stored()["Old Mail"] shouldBe null
	}

	@Test
	fun `an override publishes UPDATED for the folder and the folder that lost the role`(events: ApplicationEvents) {
		sync("INBOX" to emptyList(), "Archive" to listOf("\\Archive"), "Old Mail" to emptyList())
		val archive = folder("Archive")
		val oldMail = folder("Old Mail")
		events.clear()

		service.overrideRole(account.id, oldMail.id, MailFolderRole.ARCHIVE, automatic = false, ifMatch = "\"${oldMail.version}\"")

		events.folderEvents() shouldBe listOf(archive.id to ChangeOp.UPDATED, oldMail.id to ChangeOp.UPDATED)
		folder("Archive").roleOverridden shouldBe false
	}

	@Test
	fun `re-setting the role a folder already has touches no other folder`(events: ApplicationEvents) {
		sync("INBOX" to emptyList(), "Archive" to listOf("\\Archive"))
		val archive = folder("Archive")
		events.clear()

		service.overrideRole(account.id, archive.id, MailFolderRole.ARCHIVE, automatic = false, ifMatch = "\"${archive.version}\"").let {
			it.role shouldBe MailFolderRole.ARCHIVE
			it.roleOverridden shouldBe true
		}

		events.folderEvents() shouldBe listOf(archive.id to ChangeOp.UPDATED)
	}

	@Test
	fun `an override with a stale ETag changes nothing`(events: ApplicationEvents) {
		sync("INBOX" to emptyList(), "Archive" to listOf("\\Archive"), "Old Mail" to emptyList())
		val oldMail = folder("Old Mail")
		events.clear()

		shouldThrow<PreconditionFailedException> {
			service.overrideRole(account.id, oldMail.id, MailFolderRole.ARCHIVE, automatic = false, ifMatch = "\"99\"")
		}

		stored()["Archive"] shouldBe MailFolderRole.ARCHIVE
		events.folderEvents().shouldBeEmpty()
	}

	@Test
	fun `a failing LIST is a connection failure and leaves the cache unchanged`() {
		sync("INBOX" to emptyList(), "Sent" to listOf("\\Sent"))
		every { root.list("*") } throws MessagingException("fictitious BAD response")

		shouldThrow<MailConnectionException> { service.sync(account.id) }.error shouldBe MailConnectionError.CONNECTION_FAILED

		stored() shouldBe mapOf("INBOX" to MailFolderRole.INBOX, "Sent" to MailFolderRole.SENT)
	}

	@Test
	fun `a pool failure propagates and leaves the cache unchanged`() {
		sync("INBOX" to emptyList())
		every { pool.withStore(any(), any<(IMAPStore) -> Any?>()) } throws MailConnectionException(MailConnectionError.POOL_EXHAUSTED)

		shouldThrow<MailConnectionException> { service.sync(account.id) }.error shouldBe MailConnectionError.POOL_EXHAUSTED

		stored() shouldBe mapOf("INBOX" to MailFolderRole.INBOX)
	}

	@Test
	fun `a vanished folder is removed but its cached messages stay without a folder`() {
		sync("INBOX" to emptyList(), "Projects" to emptyList())
		val projects = folder("Projects")
		val messageId = UuidV7.generate()
		jdbc.update(
			"insert into mail_message (id, created_at, updated_at, version, account_id, folder_id, uid, subject) " +
				"values (?, now(), now(), 0, ?, ?, 42, 'Fictitious kickoff')",
			messageId,
			account.id,
			projects.id,
		)

		sync("INBOX" to emptyList())

		folders.findById(projects.id).isPresent shouldBe false
		jdbc.queryForObject("select count(*) from mail_message where id = ? and folder_id is null", Int::class.java, messageId) shouldBe 1
	}

	@Test
	fun `an unknown account is not found and the server is not contacted`() {
		shouldThrow<NotFoundException> { service.sync(UuidV7.generate()) }
		shouldThrow<NotFoundException> { service.list(UuidV7.generate()) }

		verify(exactly = 0) { pool.withStore(any(), any<(IMAPStore) -> Any?>()) }
	}

	@Test
	fun `an override of an unknown folder or a folder of another account is not found`() {
		sync("INBOX" to emptyList())
		val inbox = folder("INBOX")

		shouldThrow<NotFoundException> {
			service.overrideRole(account.id, UuidV7.generate(), MailFolderRole.ARCHIVE, automatic = false, ifMatch = "\"0\"")
		}
		shouldThrow<NotFoundException> {
			service.overrideRole(UuidV7.generate(), inbox.id, MailFolderRole.ARCHIVE, automatic = false, ifMatch = "\"${inbox.version}\"")
		}
		folder("INBOX").role shouldBe MailFolderRole.INBOX
	}

	@Test
	fun `a flat namespace without delimiter is stored with depth 0 and no delimiter`() {
		every { root.list("*") } answers {
			arrayOf(
				mockk<IMAPFolder>().also {
					every { it.fullName } returns "INBOX"
					every { it.name } returns "INBOX"
					every { it.separator } returns '\u0000'
					every { it.attributes } returns null
				},
			)
		}

		service.sync(account.id)

		folder("INBOX").let {
			it.delimiter shouldBe null
			it.role shouldBe MailFolderRole.INBOX
			it.selectable shouldBe true
		}
	}

	@Test
	fun `folders of another account are not touched by a sync`() {
		val other =
			accounts.saveAndFlush(
				MailAccount(
					type = MailAccountType.IMAP_SMTP,
					displayName = "Private",
					emailAddress = "jane@private.example",
					imapHost = "imap.private.example",
					imapPort = 993,
					imapSecurity = MailSecurity.SSL_TLS,
					smtpHost = "smtp.private.example",
					smtpPort = 465,
					smtpSecurity = MailSecurity.SSL_TLS,
					username = "jane",
				).apply { password = EncryptedSecret("fictitious-secret-2") },
			)
		folders.saveAndFlush(MailFolder(other.id, "Sent", "Sent").apply { role = MailFolderRole.SENT })

		sync("INBOX" to emptyList(), "Sent" to listOf("\\Sent"))

		folders.findByAccountIdAndPath(other.id, "Sent")!!.role shouldBe MailFolderRole.SENT
		stored()["Sent"] shouldBe MailFolderRole.SENT
	}

	@Test
	fun `two parallel first syncs of one account both succeed and store every folder once`() {
		remote = listOf("INBOX" to emptyList(), "Sent" to listOf("\\Sent"), "Projects" to emptyList())
		// Both syncs have read the server's list before either stores it, so both start from an empty cache.
		val bothListed = CyclicBarrier(2)
		every { root.list("*") } answers {
			bothListed.await(10, TimeUnit.SECONDS)
			remote.map { (path, attributes) -> imapFolder(path, attributes) }.toTypedArray()
		}
		val executor = Executors.newFixedThreadPool(2)
		try {
			val results = List(2) { executor.submit<List<MailFolder>> { service.sync(account.id) } }.map { it.get(30, TimeUnit.SECONDS) }

			results.forEach { result -> result.map { it.path } shouldBe listOf("INBOX", "Projects", "Sent") }
		} finally {
			executor.shutdownNow()
		}
		folders.findByAccountIdOrderByPathAsc(account.id).map { it.path } shouldBe listOf("INBOX", "Projects", "Sent")
		stored()["Sent"] shouldBe MailFolderRole.SENT
	}
}
