package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.imap.MailServer
import ch.sonensei.cockpit.mail.imap.NewMailAccount
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import jakarta.activation.DataHandler
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import org.eclipse.angus.mail.imap.IMAPFolder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Properties

/**
 * Header sync against GreenMail (no CONDSTORE/QRESYNC, so the plain flag fetch path): the initial window, older mail
 * on demand, new / deleted / flag-changed messages, threading of real headers and a UIDVALIDITY change.
 */
@ExternalTest
@SpringBootTest
@WithCockpitUser
@DirtiesContext
class HeaderSyncGreenMailTests(
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountService,
	@Autowired private val folderService: MailFolderService,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailSyncRepository,
	@Autowired private val sync: MailHeaderSyncService,
	@Autowired private val links: LinkService,
) : PostgresTestBase() {
	private lateinit var account: MailAccount

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from link")
		jdbc.update("delete from mail_account")
		greenMail.setUser(EMAIL, USER, PASSWORD)
		account =
			accounts.create(
				NewMailAccount(
					type = MailAccountType.IMAP_SMTP,
					displayName = "GreenMail",
					emailAddress = EMAIL,
					imap = MailServer(HOST, greenMail.imap.port, MailSecurity.NONE),
					smtp = MailServer(HOST, greenMail.smtp.port, MailSecurity.NONE),
					username = USER,
					password = PASSWORD,
					oauthConnectionId = null,
					signatureMd = null,
					notifyNewMail = null,
					syncWindowMonths = null,
				),
			)
	}

	private fun <T> remote(block: (Store) -> T): T =
		Session.getInstance(Properties()).getStore("imap").use { store ->
			store.connect(HOST, greenMail.imap.port, USER, PASSWORD)
			block(store)
		}

	/** A message as another client or the MTA would put it into a folder, arriving at [received]. */
	private fun mail(
		messageId: String,
		subject: String,
		received: Instant = Instant.now(),
		inReplyTo: String? = null,
		references: String? = null,
		text: String = "Hello from the fictitious test.",
		attachment: Boolean = false,
	): MimeMessage {
		val message =
			object : MimeMessage(Session.getInstance(Properties())) {
				override fun getReceivedDate(): Date = Date.from(received)

				// Keep the Message-ID we set instead of generating one.
				override fun updateMessageID() = Unit
			}
		message.setHeader("Message-ID", messageId)
		message.setFrom(InternetAddress("ann.example@example.test", "Ann Example"))
		message.setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(EMAIL)))
		message.setRecipients(Message.RecipientType.CC, "bob@example.test")
		message.subject = subject
		message.sentDate = Date.from(received)
		inReplyTo?.let { message.setHeader("In-Reply-To", it) }
		references?.let { message.setHeader("References", it) }
		if (attachment) {
			val body = MimeBodyPart().apply { setText(text, "UTF-8") }
			val file =
				MimeBodyPart().apply {
					dataHandler = DataHandler(ByteArrayDataSource("fictitious,csv".toByteArray(), "text/csv"))
					fileName = "budget.csv"
					disposition = MimeBodyPart.ATTACHMENT
				}
			message.setContent(MimeMultipart(body, file))
		} else {
			message.setText(text, "UTF-8")
		}
		message.saveChanges()
		return message
	}

	private fun append(
		path: String,
		vararg mails: MimeMessage,
	) = remote { store ->
		val folder = store.getFolder(path)
		if (!folder.exists()) folder.create(Folder.HOLDS_MESSAGES)
		folder.appendMessages(mails)
	}

	/** Runs [block] on the server copy of the message with that Message-ID, with the folder open read-write. */
	private fun onServer(
		path: String,
		messageId: String,
		block: (Message) -> Unit,
	) = remote { store ->
		val folder = store.getFolder(path)
		folder.open(Folder.READ_WRITE)
		try {
			block(folder.messages.single { it.getHeader("Message-ID")?.firstOrNull() == messageId })
		} finally {
			folder.close(true)
		}
	}

	private fun uidValidity(path: String): Long =
		remote { store ->
			val folder = store.getFolder(path) as IMAPFolder
			folder.open(Folder.READ_ONLY)
			try {
				folder.uidValidity
			} finally {
				folder.close(false)
			}
		}

	/**
	 * Deletes and recreates a folder with [mails], as another client might. GreenMail derives UIDVALIDITY from the clock,
	 * so this retries until the new folder got another one.
	 */
	private fun recreate(
		path: String,
		vararg mails: MimeMessage,
	) {
		val before = uidValidity(path)
		repeat(RECREATE_ATTEMPTS) {
			remote { store -> store.getFolder(path).delete(true) shouldBe true }
			append(path, *mails)
			if (uidValidity(path) != before) return
			Thread.sleep(RECREATE_PAUSE_MILLIS)
		}
		error("GreenMail kept UIDVALIDITY $before for $path")
	}

	private fun folder(path: String): MailFolder {
		folderService.sync(account.id)
		return checkNotNull(folders.findByAccountIdAndPath(account.id, path))
	}

	private fun cached(folder: MailFolder): List<MailMessage> = messages.findByFolderIdAndUidIsNotNull(folder.id).sortedBy { it.uid }

	private fun cached(
		folder: MailFolder,
		messageId: String,
	): MailMessage = cached(folder).single { it.messageIdHeader == messageId }

	@Test
	fun `the initial sync caches the headers of the window and threads them`() {
		val now = Instant.now()
		append(
			"INBOX",
			mail("<old@example.test>", "Ancient history", received = now.minus(400, ChronoUnit.DAYS)),
			mail("<root@example.test>", "Budget 2026", received = now.minus(3, ChronoUnit.DAYS), text = "Line one\nLine two"),
			mail(
				"<reply@example.test>",
				"Re: Budget 2026",
				received = now.minus(2, ChronoUnit.DAYS),
				inReplyTo = "<root@example.test>",
				references = "<root@example.test>",
				text = "Looks good.\n> Line one\n> Line two",
				attachment = true,
			),
			mail("<other@example.test>", "Holidays", received = now.minus(1, ChronoUnit.DAYS)),
		)
		val inbox = folder("INBOX")

		val result = sync.syncFolder(inbox.id)

		result.full shouldBe true
		result.created shouldBe 3
		cached(inbox).map { it.messageIdHeader } shouldContainExactlyInAnyOrder
			listOf("<root@example.test>", "<reply@example.test>", "<other@example.test>")
		val root = cached(inbox, "<root@example.test>")
		val reply = cached(inbox, "<reply@example.test>")
		root.subject shouldBe "Budget 2026"
		root.from shouldBe listOf(MailAddress("ann.example@example.test", "Ann Example"))
		root.to shouldBe listOf(MailAddress(EMAIL))
		root.cc shouldBe listOf(MailAddress("bob@example.test"))
		root.snippet shouldBe "Line one Line two"
		root.hasAttachments shouldBe false
		root.seen shouldBe false
		root.receivedAt.shouldNotBeNull()
		(root.sizeBytes > 0) shouldBe true
		reply.inReplyTo shouldBe "<root@example.test>"
		reply.referencesHeader shouldBe "<root@example.test>"
		reply.hasAttachments shouldBe true
		reply.snippet shouldBe "Looks good."
		reply.threadKey shouldBe root.threadKey
		cached(inbox, "<other@example.test>").threadKey shouldNotBe root.threadKey
		val stored = folders.findById(inbox.id).orElseThrow()
		stored.uidValidity.shouldNotBeNull()
		stored.uidNext shouldBe 5
		stored.lastSyncedAt.shouldNotBeNull()
		// The sync examines the folder read-only: nothing became \Seen on the server.
		onServer("INBOX", "<root@example.test>") { it.isSet(Flags.Flag.SEEN) shouldBe false }
	}

	@Test
	fun `older mail is loaded on demand`() {
		val now = Instant.now()
		append(
			"INBOX",
			mail("<older@example.test>", "Older", received = now.minus(500, ChronoUnit.DAYS)),
			mail("<old@example.test>", "Old", received = now.minus(400, ChronoUnit.DAYS)),
			mail("<new@example.test>", "New", received = now.minus(1, ChronoUnit.DAYS)),
		)
		val inbox = folder("INBOX")
		sync.syncFolder(inbox.id)
		cached(inbox).map { it.messageIdHeader } shouldBe listOf("<new@example.test>")

		sync.loadOlder(inbox.id, limit = 1) shouldBe OlderMessagesResult(loaded = 1, hasMore = true)
		cached(inbox).map { it.messageIdHeader } shouldBe listOf("<old@example.test>", "<new@example.test>")

		sync.loadOlder(inbox.id, limit = 5) shouldBe OlderMessagesResult(loaded = 1, hasMore = false)
		sync.loadOlder(inbox.id) shouldBe OlderMessagesResult(loaded = 0, hasMore = false)
		cached(inbox).size shouldBe 3
		// Older messages take part in the incremental sync like the others.
		onServer("INBOX", "<older@example.test>") { it.setFlag(Flags.Flag.FLAGGED, true) }
		sync.syncFolder(inbox.id).updated shouldBe 1
		cached(inbox, "<older@example.test>").flagged shouldBe true
	}

	@Test
	fun `older mail is picked by arrival date, not by UID`() {
		val now = Instant.now()
		append(
			"Archive",
			mail("<recent@example.test>", "Recent", received = now.minus(10, ChronoUnit.DAYS)),
			mail("<ancient@example.test>", "Ancient", received = now.minus(900, ChronoUnit.DAYS)),
		)
		// Archived later: a high UID, but it arrived long ago (COPY/MOVE keep INTERNALDATE).
		append("Archive", mail("<archived@example.test>", "Archived", received = now.minus(400, ChronoUnit.DAYS)))
		val archive = folder("Archive")
		sync.syncFolder(archive.id)
		cached(archive).map { it.messageIdHeader } shouldBe listOf("<recent@example.test>")

		sync.loadOlder(archive.id, limit = 1) shouldBe OlderMessagesResult(loaded = 1, hasMore = true)
		cached(archive).map { it.messageIdHeader } shouldBe listOf("<recent@example.test>", "<archived@example.test>")

		sync.loadOlder(archive.id, limit = 1) shouldBe OlderMessagesResult(loaded = 1, hasMore = false)
		cached(archive).map { it.messageIdHeader } shouldBe
			listOf("<recent@example.test>", "<ancient@example.test>", "<archived@example.test>")
	}

	@Test
	fun `a reply that arrives before its parent stays in the conversation when the root arrives last`() {
		append(
			"INBOX",
			mail("<c@example.test>", "Re: Offsite", inReplyTo = "<b@example.test>"),
			mail("<other@example.test>", "Unrelated subject"),
		)
		val inbox = folder("INBOX")
		sync.syncFolder(inbox.id)
		append("INBOX", mail("<b@example.test>", "Re: Offsite", inReplyTo = "<a@example.test>", references = "<a@example.test>"))
		sync.syncFolder(inbox.id)
		cached(inbox, "<c@example.test>").threadKey shouldBe "<a@example.test>"

		// The root comes last, filed in another folder, with a subject of its own.
		append("Sent", mail("<a@example.test>", "Unrelated subject"))
		val sent = folder("Sent")
		sync.syncFolder(sent.id)

		// The root has no references, so the conversation takes its subject key and, by subject fallback, also holds
		// the other reference-less message with the same subject.
		cached(sent, "<a@example.test>").threadKey shouldBe "subject:unrelated subject"
		cached(inbox, "<b@example.test>").threadKey shouldBe "subject:unrelated subject"
		cached(inbox, "<c@example.test>").threadKey shouldBe "subject:unrelated subject"
		cached(inbox, "<other@example.test>").threadKey shouldBe "subject:unrelated subject"
	}

	@Test
	fun `a reply that connects two cached conversations merges them in the database`() {
		append(
			"INBOX",
			mail("<c@example.test>", "Re: Plan", inReplyTo = "<b@example.test>"),
			mail("<a@example.test>", "Plan"),
		)
		val inbox = folder("INBOX")
		sync.syncFolder(inbox.id)
		cached(inbox, "<c@example.test>").threadKey shouldNotBe cached(inbox, "<a@example.test>").threadKey

		append("INBOX", mail("<b@example.test>", "Re: Plan", inReplyTo = "<a@example.test>", references = "<a@example.test>"))
		sync.syncFolder(inbox.id)

		cached(inbox).map { it.threadKey }.toSet() shouldBe setOf("subject:plan")
	}

	@Test
	fun `an incremental sync reconciles new, deleted and flag-changed messages`() {
		append(
			"INBOX",
			mail("<a@example.test>", "First"),
			mail("<b@example.test>", "Second"),
			mail("<c@example.test>", "Third"),
		)
		val inbox = folder("INBOX")
		sync.syncFolder(inbox.id)
		val untouched = cached(inbox, "<c@example.test>")

		onServer("INBOX", "<a@example.test>") { it.setFlags(Flags(Flags.Flag.SEEN).apply { add(Flags.Flag.FLAGGED) }, true) }
		onServer("INBOX", "<a@example.test>") { it.setFlags(Flags("\$Forwarded"), true) }
		onServer("INBOX", "<b@example.test>") { it.setFlag(Flags.Flag.DELETED, true) }
		append("INBOX", mail("<d@example.test>", "Re: First", inReplyTo = "<a@example.test>"))

		val result = sync.syncFolder(inbox.id)

		result shouldBe FolderSyncResult(inbox.id, full = false, created = 1, updated = 1, removed = 1, arrived = result.arrived)
		result.arrived.size shouldBe 1
		cached(inbox).map { it.messageIdHeader } shouldBe listOf("<a@example.test>", "<c@example.test>", "<d@example.test>")
		cached(inbox, "<a@example.test>").let {
			it.seen shouldBe true
			it.flagged shouldBe true
			it.keywords.toList() shouldBe listOf("\$Forwarded")
		}
		cached(inbox, "<d@example.test>").threadKey shouldBe cached(inbox, "<a@example.test>").threadKey
		cached(inbox, "<c@example.test>").version shouldBe untouched.version
		messages.findAll().map { it.messageIdHeader } shouldContainExactlyInAnyOrder
			listOf("<a@example.test>", "<c@example.test>", "<d@example.test>")

		// Nothing changed: nothing written.
		sync.syncFolder(inbox.id) shouldBe FolderSyncResult(inbox.id, full = false, created = 0, updated = 0, removed = 0)

		onServer("INBOX", "<a@example.test>") { it.setFlag(Flags.Flag.SEEN, false) }
		sync.syncFolder(inbox.id).updated shouldBe 1
		cached(inbox, "<a@example.test>").seen shouldBe false
	}

	@Test
	fun `a deleted message that an item links to stays as a detached link target`() {
		append("INBOX", mail("<a@example.test>", "Linked"), mail("<b@example.test>", "Other"))
		val inbox = folder("INBOX")
		sync.syncFolder(inbox.id)
		val linked = cached(inbox, "<a@example.test>")
		links.create(ItemRef(ItemType.EMAIL, linked.id), ItemRef(ItemType.EMAIL, cached(inbox, "<b@example.test>").id), null)

		onServer("INBOX", "<a@example.test>") { it.setFlag(Flags.Flag.DELETED, true) }
		sync.syncFolder(inbox.id).removed shouldBe 1

		val row = messages.findById(linked.id).orElseThrow()
		row.isLocated shouldBe false
		row.messageIdHeader shouldBe "<a@example.test>"

		// The message shows up again (e.g. moved back): the linked row takes it over.
		append("INBOX", mail("<a@example.test>", "Linked"))
		sync.syncFolder(inbox.id).updated shouldBe 1
		messages.findById(linked.id).orElseThrow().isLocated shouldBe true
		cached(inbox).size shouldBe 2
	}

	@Test
	fun `a UIDVALIDITY change resyncs the folder and keeps linked rows`() {
		append("Projects", mail("<p1@example.test>", "Plan"), mail("<p2@example.test>", "Re: Plan", inReplyTo = "<p1@example.test>"))
		val projects = folder("Projects")
		sync.syncFolder(projects.id)
		val linked = cached(projects, "<p1@example.test>")
		val target = cached(projects, "<p2@example.test>")
		links.create(ItemRef(ItemType.EMAIL, linked.id), ItemRef(ItemType.EMAIL, target.id), null)
		val validity = folders.findById(projects.id).orElseThrow().uidValidity

		// Another client deletes and recreates the folder: same path, new UIDVALIDITY, different messages.
		recreate("Projects", mail("<p3@example.test>", "Fresh start"), mail("<p1@example.test>", "Plan"))

		val result = sync.syncFolder(projects.id)

		result.full shouldBe true
		result.created shouldBe 1
		result.updated shouldBe 1
		cached(projects).map { it.messageIdHeader } shouldContainExactlyInAnyOrder listOf("<p1@example.test>", "<p3@example.test>")
		// The linked row was found again by Message-ID and points to the new UID.
		cached(projects, "<p1@example.test>").id shouldBe linked.id
		cached(projects, "<p1@example.test>").uid shouldBe 2
		// The other message is gone from the server, but it is linked too (as the link's target), so it stays detached.
		messages.findById(target.id).orElseThrow().isLocated shouldBe false
		folders.findById(projects.id).orElseThrow().uidValidity shouldNotBe validity
	}

	@Test
	fun `a resync finds linked messages older than the window again by Message-ID`() {
		val old = Instant.now().minus(500, ChronoUnit.DAYS)
		append("Projects", mail("<recent@example.test>", "Recent"), mail("<old@example.test>", "Old", received = old))
		val projects = folder("Projects")
		sync.syncFolder(projects.id)
		sync.loadOlder(projects.id).loaded shouldBe 1
		val linked = cached(projects, "<old@example.test>")
		links.create(ItemRef(ItemType.EMAIL, linked.id), ItemRef(ItemType.EMAIL, cached(projects, "<recent@example.test>").id), null)

		// New UIDVALIDITY; the old message is still there, outside the window.
		recreate("Projects", mail("<old@example.test>", "Old", received = old), mail("<recent@example.test>", "Recent"))
		val result = sync.syncFolder(projects.id)

		result.full shouldBe true
		result.removed shouldBe 0
		cached(projects, "<old@example.test>").id shouldBe linked.id
		cached(projects, "<old@example.test>").uid shouldBe 1
		cached(projects).map { it.messageIdHeader } shouldContainExactlyInAnyOrder listOf("<old@example.test>", "<recent@example.test>")
	}

	@Test
	fun `a resync finds many linked old messages again with batched Message-ID searches`() {
		val old = Instant.now().minus(500, ChronoUnit.DAYS)
		// More than one search batch (20 Message-IDs per UID SEARCH, nested ORs), plus an old message nothing links to.
		val oldIds = (1..22).map { "<old-$it@example.test>" }
		val oldMails = { (oldIds + "<unlinked-old@example.test>").map { mail(it, "Old $it", received = old) }.toTypedArray() }
		append("Projects", mail("<recent@example.test>", "Recent"), *oldMails())
		val projects = folder("Projects")
		sync.syncFolder(projects.id)
		sync.loadOlder(projects.id).loaded shouldBe 23
		val recent = cached(projects, "<recent@example.test>")
		val linkedIds = oldIds.associateWith { cached(projects, it).id }
		linkedIds.values.forEach { links.create(ItemRef(ItemType.EMAIL, it), ItemRef(ItemType.EMAIL, recent.id), null) }

		recreate("Projects", *oldMails(), mail("<recent@example.test>", "Recent"))
		val result = sync.syncFolder(projects.id)

		result.full shouldBe true
		// Every linked old row is located again under its own id; only the unlinked old one is not fetched again.
		oldIds.forEach { id -> cached(projects, id).id shouldBe linkedIds.getValue(id) }
		cached(projects).map { it.messageIdHeader } shouldContainExactlyInAnyOrder oldIds + "<recent@example.test>"
		result.removed shouldBe 1
	}

	@Test
	fun `a resync removes rows that nothing links to`() {
		append("Projects", mail("<p1@example.test>", "Plan"))
		val projects = folder("Projects")
		sync.syncFolder(projects.id)
		val before = cached(projects).single()

		recreate("Projects", mail("<p9@example.test>", "Other"))
		val result = sync.syncFolder(projects.id)

		result shouldBe FolderSyncResult(projects.id, full = true, created = 1, updated = 0, removed = 1)
		messages.existsById(before.id) shouldBe false
	}

	@Test
	fun `syncAccount syncs every selectable folder and skips folders gone from the server`() {
		append("INBOX", mail("<i@example.test>", "Inbox mail"))
		append("Archive", mail("<x@example.test>", "Archived mail"))
		folderService.sync(account.id)
		remote { store -> store.getFolder("Archive").delete(true) shouldBe true }

		val results = sync.syncAccount(account.id)

		results.map { folders.findById(it.folderId).orElseThrow().path } shouldBe listOf("INBOX")
		results.single().created shouldBe 1
	}

	@Test
	fun `a folder emptied on the server removes every cached message`() {
		append("INBOX", mail("<a@example.test>", "First"), mail("<b@example.test>", "Second"))
		val inbox = folder("INBOX")
		sync.syncFolder(inbox.id)
		cached(inbox).size shouldBe 2

		remote { store ->
			val folder = store.getFolder("INBOX")
			folder.open(Folder.READ_WRITE)
			folder.messages.forEach { it.setFlag(Flags.Flag.DELETED, true) }
			folder.close(true)
		}

		sync.syncFolder(inbox.id) shouldBe FolderSyncResult(inbox.id, full = false, created = 0, updated = 0, removed = 2)
		cached(inbox).size shouldBe 0
	}

	@Test
	fun `encoded subjects and names, html-only bodies and inline images are read from real headers`() {
		val received = Instant.now()
		val html =
			object : MimeMessage(Session.getInstance(Properties())) {
				override fun getReceivedDate(): Date = Date.from(received)

				override fun updateMessageID() = Unit
			}.apply {
				setHeader("Message-ID", "<html@example.test>")
				setFrom(InternetAddress("zoe.example@example.test", "Zoë Exämple", "UTF-8"))
				// GreenMail cannot build an ENVELOPE for group syntax (e.g. `undisclosed-recipients:;`), so plain lists here.
				setRecipients(Message.RecipientType.TO, "Jane Doe <jane.doe@example.test>")
				setRecipients(Message.RecipientType.CC, "carl@example.test, dora@example.test")
				setSubject("Grüezi from Zürich", "UTF-8")
				sentDate = Date.from(received)
				val body = MimeBodyPart().apply { setContent("<p>Hello <b>Jane</b>,</p><p>see the café plan.</p>", "text/html; charset=UTF-8") }
				val logo =
					MimeBodyPart().apply {
						// Named by its Content-Type, without Content-Disposition, as many mailers send inline images.
						dataHandler = DataHandler(ByteArrayDataSource(byteArrayOf(1, 2, 3), "image/png; name=logo.png"))
						setHeader("Content-ID", "<logo@example.test>")
					}
				setContent(MimeMultipart("related", body, logo))
				saveChanges()
			}
		append("INBOX", html)
		val inbox = folder("INBOX")

		sync.syncFolder(inbox.id).created shouldBe 1

		val row = cached(inbox, "<html@example.test>")
		row.subject shouldBe "Grüezi from Zürich"
		row.from shouldBe listOf(MailAddress("zoe.example@example.test", "Zoë Exämple"))
		row.to shouldBe listOf(MailAddress("jane.doe@example.test", "Jane Doe"))
		row.cc shouldBe listOf(MailAddress("carl@example.test"), MailAddress("dora@example.test"))
		row.snippet shouldBe "Hello Jane, see the café plan."
		// The named image is part of the HTML body (multipart/related), not an attachment.
		row.hasAttachments shouldBe false
		row.threadKey shouldBe "subject:grüezi from zürich"
	}

	companion object {
		private const val HOST = "localhost"
		private const val EMAIL = "jane.doe@example.test"
		private const val USER = "jane"
		private const val PASSWORD = "fictitious-password"
		private const val RECREATE_ATTEMPTS = 20
		private const val RECREATE_PAUSE_MILLIS = 200L

		@JvmField
		@RegisterExtension
		val greenMail: GreenMailExtension =
			GreenMailExtension(arrayOf(ServerSetupTest.SMTP, ServerSetupTest.IMAP).map { it.dynamicPort() }.toTypedArray())
	}
}
