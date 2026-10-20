package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.LinkService
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

/** [MailHeaderStore] against Postgres: inserts, flag updates, threading across batches, removal and re-attachment. */
@SpringBootTest
@WithCockpitUser
class MailHeaderStoreTests(
	@Autowired private val store: MailHeaderStore,
	@Autowired private val messages: MailSyncRepository,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val links: LinkService,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private lateinit var account: MailAccount
	private lateinit var inbox: MailFolder
	private lateinit var archive: MailFolder
	private lateinit var other: MailFolder

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from link")
		jdbc.update("delete from mail_account")
		account = accounts.save(account("jane.doe@example.test"))
		inbox = folders.save(MailFolder(account.id, "INBOX", "INBOX"))
		archive = folders.save(MailFolder(account.id, "Archive", "Archive"))
		other = folders.save(MailFolder(account.id, "Other", "Other"))
	}

	private fun account(email: String) =
		MailAccount(
			MailAccountType.IMAP_SMTP,
			"Fictitious",
			email,
			"imap.example.test",
			993,
			MailSecurity.SSL_TLS,
			"smtp.example.test",
			465,
			MailSecurity.SSL_TLS,
			"jane",
		)

	private fun remote(
		uid: Long,
		messageId: String?,
		subject: String? = "Budget",
		inReplyTo: String? = null,
		references: String? = null,
		flags: RemoteFlags = RemoteFlags(),
		modSeq: Long? = null,
	) = RemoteMessage(
		uid = uid,
		messageId = messageId,
		inReplyTo = inReplyTo,
		references = references,
		subject = subject,
		from = listOf(MailAddress("ann.example@example.test", "Ann Example")),
		to = listOf(MailAddress("jane.doe@example.test")),
		cc = emptyList(),
		bcc = emptyList(),
		replyTo = emptyList(),
		sentAt = Instant.parse("2026-10-19T08:00:00Z"),
		receivedAt = Instant.parse("2026-10-19T08:00:05Z"),
		flags = flags,
		sizeBytes = 1234,
		hasAttachments = uid % 2 == 0L,
		snippet = "Snippet $uid",
		modSeq = modSeq,
	)

	private fun row(
		folder: MailFolder,
		uid: Long,
	): MailMessage = messages.findByFolderIdAndUidIn(folder.id, listOf(uid)).single()

	private fun byMessageId(messageId: String): List<MailMessage> = messages.findAll().filter { it.messageIdHeader == messageId }

	/** Links [message] to an unrelated anchor message, so it must survive as a link target. */
	private fun link(message: MailMessage) {
		val anchor =
			messages.findAll().firstOrNull { it.messageIdHeader == "<anchor@example.test>" }
				?: run {
					store.upsert(account.id, other.id, listOf(remote(999, "<anchor@example.test>", subject = "Anchor")))
					row(other, 999)
				}
		links.create(ItemRef(ItemType.EMAIL, message.id), ItemRef(ItemType.EMAIL, anchor.id), null)
	}

	@Test
	fun `new headers are stored with all fields and counted as created`() {
		val counts =
			store.upsert(
				account.id,
				inbox.id,
				listOf(
					remote(2, "<a@example.test>", flags = RemoteFlags(seen = true, keywords = listOf("\$Forwarded")), modSeq = 40),
					remote(3, "<b@example.test>", subject = "Holidays"),
				),
			)

		counts shouldBe StoreCounts(created = 2)
		val a = row(inbox, 2)
		a.accountId shouldBe account.id
		a.messageIdHeader shouldBe "<a@example.test>"
		a.subject shouldBe "Budget"
		a.from shouldBe listOf(MailAddress("ann.example@example.test", "Ann Example"))
		a.to shouldBe listOf(MailAddress("jane.doe@example.test"))
		a.sentAt shouldBe Instant.parse("2026-10-19T08:00:00Z")
		a.receivedAt shouldBe Instant.parse("2026-10-19T08:00:05Z")
		a.sizeBytes shouldBe 1234
		a.hasAttachments shouldBe true
		a.snippet shouldBe "Snippet 2"
		a.seen shouldBe true
		a.keywords.toList() shouldBe listOf("\$Forwarded")
		a.modSeq shouldBe 40
		a.threadKey shouldBe "subject:budget"
		row(inbox, 3).threadKey shouldBe "subject:holidays"
		store.locatedUids(inbox.id) shouldContainExactly listOf(2L, 3L)
	}

	@Test
	fun `an empty batch writes nothing`() {
		store.upsert(account.id, inbox.id, emptyList()) shouldBe StoreCounts()
		store.reconcile(inbox.id, emptyList(), emptySet()) shouldBe StoreCounts()
		messages.count() shouldBe 0
	}

	@Test
	fun `upserting cached UIDs only refreshes flags and counts real changes`() {
		store.upsert(account.id, inbox.id, listOf(remote(1, "<a@example.test>"), remote(2, "<b@example.test>")))
		val before = row(inbox, 2)

		val counts =
			store.upsert(
				account.id,
				inbox.id,
				listOf(
					// Headers in a re-fetch are ignored; only the flag change counts.
					remote(1, "<a@example.test>", subject = "Changed subject", flags = RemoteFlags(flagged = true)),
					// Same flags, only a new MODSEQ: stored, not counted.
					remote(2, "<b@example.test>", modSeq = 99),
				),
			)

		counts shouldBe StoreCounts(updated = 1)
		row(inbox, 1).flagged shouldBe true
		row(inbox, 1).subject shouldBe "Budget"
		row(inbox, 2).modSeq shouldBe 99
		row(inbox, 2).seen shouldBe before.seen
		messages.count() shouldBe 2
	}

	@Test
	fun `reconcile applies flag changes of cached UIDs, ignores unknown ones and counts each change`() {
		store.upsert(account.id, inbox.id, listOf(remote(1, "<a@example.test>"), remote(2, "<b@example.test>"), remote(3, "<c@example.test>")))
		// Another folder with the same UID is not touched.
		store.upsert(account.id, archive.id, listOf(remote(1, "<x@example.test>")))

		val counts =
			store.reconcile(
				inbox.id,
				listOf(
					RemoteFlagState(1, RemoteFlags(seen = true, answered = true, draft = true), modSeq = 7),
					RemoteFlagState(2, RemoteFlags(), modSeq = 8),
					RemoteFlagState(3, RemoteFlags(keywords = listOf("\$Junk", "\$Label1"))),
					RemoteFlagState(42, RemoteFlags(seen = true)),
				),
				emptySet(),
			)

		counts shouldBe StoreCounts(updated = 2)
		row(inbox, 1).let {
			it.seen shouldBe true
			it.answered shouldBe true
			it.draft shouldBe true
			it.modSeq shouldBe 7
		}
		row(inbox, 2).modSeq shouldBe 8
		row(inbox, 3).keywords.toList() shouldBe listOf("\$Junk", "\$Label1")
		row(archive, 1).seen shouldBe false
		messages.count() shouldBe 4
	}

	@Test
	fun `answered alone and draft alone are flag changes`() {
		store.upsert(account.id, inbox.id, listOf(remote(1, "<a@example.test>"), remote(2, "<b@example.test>")))

		val counts =
			store.reconcile(
				inbox.id,
				listOf(RemoteFlagState(1, RemoteFlags(answered = true)), RemoteFlagState(2, RemoteFlags(draft = true))),
				emptySet(),
			)

		counts shouldBe StoreCounts(updated = 2)
		row(inbox, 1).answered shouldBe true
		row(inbox, 2).draft shouldBe true
	}

	@Test
	fun `a keyword removed on the server is a change`() {
		store.upsert(account.id, inbox.id, listOf(remote(1, "<a@example.test>", flags = RemoteFlags(keywords = listOf("\$Forwarded")))))

		store.reconcile(inbox.id, listOf(RemoteFlagState(1, RemoteFlags())), emptySet()) shouldBe StoreCounts(updated = 1)

		row(inbox, 1).keywords.toList().shouldBeEmpty()
	}

	@Test
	fun `vanished UIDs are deleted unless linked, then only detached`() {
		store.upsert(account.id, inbox.id, listOf(remote(1, "<a@example.test>"), remote(2, "<b@example.test>"), remote(3, "<c@example.test>")))
		val linked = row(inbox, 2)
		link(linked)

		val counts = store.reconcile(inbox.id, emptyList(), setOf(1L, 2L, 77L))

		counts shouldBe StoreCounts(removed = 2)
		byMessageId("<a@example.test>").shouldBeEmpty()
		val detached = messages.findById(linked.id).orElseThrow()
		detached.isLocated shouldBe false
		detached.folderId shouldBe inbox.id
		detached.messageIdHeader shouldBe "<b@example.test>"
		store.locatedUids(inbox.id) shouldContainExactly listOf(3L)
	}

	@Test
	fun `a detached row is taken over by a later message with its Message-ID and keeps its id and thread`() {
		store.upsert(account.id, inbox.id, listOf(remote(1, "<a@example.test>", subject = "Plan")))
		val original = row(inbox, 1)
		link(original)
		store.reconcile(inbox.id, emptyList(), setOf(1L))

		val counts = store.upsert(account.id, inbox.id, listOf(remote(5, "<a@example.test>", subject = "Plan")))

		counts shouldBe StoreCounts(updated = 1)
		row(inbox, 5).id shouldBe original.id
		row(inbox, 5).threadKey shouldBe original.threadKey
		byMessageId("<a@example.test>").size shouldBe 1
	}

	@Test
	fun `re-attachment prefers a detached row of the same folder, else the oldest one of the account`() {
		// Two copies of the same message, in Archive first (older row id), then in INBOX; both linked, both gone.
		store.upsert(account.id, archive.id, listOf(remote(1, "<dup@example.test>")))
		store.upsert(account.id, inbox.id, listOf(remote(1, "<dup@example.test>")))
		val inArchive = row(archive, 1)
		val inInbox = row(inbox, 1)
		(inArchive.id < inInbox.id) shouldBe true
		link(inArchive)
		link(inInbox)
		store.reconcile(archive.id, emptyList(), setOf(1L))
		store.reconcile(inbox.id, emptyList(), setOf(1L))

		// It shows up in INBOX again: the INBOX row takes it, although the Archive row is older.
		store.upsert(account.id, inbox.id, listOf(remote(10, "<dup@example.test>"))) shouldBe StoreCounts(updated = 1)
		row(inbox, 10).id shouldBe inInbox.id
		messages.findById(inArchive.id).orElseThrow().isLocated shouldBe false

		// A copy in a third folder takes the remaining (Archive) row and moves it there.
		store.upsert(account.id, other.id, listOf(remote(20, "<dup@example.test>"))) shouldBe StoreCounts(updated = 1)
		row(other, 20).id shouldBe inArchive.id
		row(other, 20).folderId shouldBe other.id

		// No detached row left: a fourth copy is a new row.
		store.upsert(account.id, archive.id, listOf(remote(30, "<dup@example.test>"))) shouldBe StoreCounts(created = 1)
		byMessageId("<dup@example.test>").size shouldBe 3
	}

	@Test
	fun `two copies with the same Message-ID in one batch take one detached row and create the other`() {
		store.upsert(account.id, inbox.id, listOf(remote(1, "<dup@example.test>")))
		val original = row(inbox, 1)
		link(original)
		store.reconcile(inbox.id, emptyList(), setOf(1L))

		val counts = store.upsert(account.id, inbox.id, listOf(remote(4, "<dup@example.test>"), remote(5, "<dup@example.test>")))

		counts shouldBe StoreCounts(created = 1, updated = 1)
		row(inbox, 4).id shouldBe original.id
		row(inbox, 5).id shouldNotBe original.id
	}

	@Test
	fun `detached rows of another account are never taken over`() {
		val stranger = accounts.save(account("someone.else@example.test"))
		val strangerInbox = folders.save(MailFolder(stranger.id, "INBOX", "INBOX"))
		store.upsert(stranger.id, strangerInbox.id, listOf(remote(1, "<shared@example.test>")))
		val theirs = row(strangerInbox, 1)
		messages.save(theirs.apply { detach() })
		messages.flush()

		store.upsert(account.id, inbox.id, listOf(remote(1, "<shared@example.test>"))) shouldBe StoreCounts(created = 1)

		row(inbox, 1).id shouldNotBe theirs.id
	}

	@Test
	fun `a reply and its root in one batch share a thread whatever their order`() {
		store.upsert(
			account.id,
			inbox.id,
			listOf(
				remote(1, "<reply@example.test>", subject = "Re: Plan", inReplyTo = "<root@example.test>", references = "<root@example.test>"),
				remote(2, "<root@example.test>", subject = "Plan"),
			),
		)

		row(inbox, 1).threadKey shouldBe "<root@example.test>"
		row(inbox, 2).threadKey shouldBe "<root@example.test>"
	}

	@Test
	fun `a message that connects two conversations in one batch merges them`() {
		store.upsert(
			account.id,
			inbox.id,
			listOf(
				// Subject-threaded root.
				remote(1, "<a@example.test>", subject = "Offsite"),
				// A reply to <c>, which is not cached yet: provisionally keyed by <c>.
				remote(2, "<b@example.test>", subject = "Re: Offsite agenda", inReplyTo = "<c@example.test>"),
				// <c> replies to <a>: joins <a>'s conversation and pulls <b> along.
				remote(3, "<c@example.test>", subject = "Offsite agenda", inReplyTo = "<a@example.test>"),
			),
		)

		val keys = listOf(1L, 2L, 3L).map { row(inbox, it).threadKey }
		keys shouldBe List(3) { "subject:offsite" }
	}

	@Test
	fun `threads merge across batches and folders`() {
		store.upsert(
			account.id,
			inbox.id,
			listOf(remote(1, "<late-reply@example.test>", subject = "Re: Q4", references = "<q4@example.test> <mid@example.test>")),
		)
		store.upsert(account.id, archive.id, listOf(remote(1, "<mid@example.test>", subject = "Re: Q4", inReplyTo = "<q4@example.test>")))
		row(inbox, 1).threadKey shouldBe "<q4@example.test>"
		row(archive, 1).threadKey shouldBe "<q4@example.test>"

		// The root is subject-less and was filed elsewhere; it arrives last and everyone stays together.
		store.upsert(account.id, other.id, listOf(remote(1, "<q4@example.test>", subject = null)))

		row(other, 1).threadKey shouldBe "<q4@example.test>"
		row(inbox, 1).threadKey shouldBe "<q4@example.test>"
		row(archive, 1).threadKey shouldBe "<q4@example.test>"
	}

	@Test
	fun `threads are separate per account`() {
		val stranger = accounts.save(account("someone.else@example.test"))
		val strangerInbox = folders.save(MailFolder(stranger.id, "INBOX", "INBOX"))
		store.upsert(stranger.id, strangerInbox.id, listOf(remote(1, "<root@example.test>", subject = "Shared root")))

		store.upsert(account.id, inbox.id, listOf(remote(1, "<r@example.test>", subject = "Re: x", inReplyTo = "<root@example.test>")))

		// Not the stranger's key (which is subject-based), but the provisional root key of this account.
		row(inbox, 1).threadKey shouldBe "<root@example.test>"
		row(strangerInbox, 1).threadKey shouldBe "subject:shared root"
	}

	@Test
	fun `a message without Message-ID, references and subject gets a key of its own`() {
		store.upsert(account.id, inbox.id, listOf(remote(1, null, subject = null), remote(2, null, subject = "  ")))

		val first = row(inbox, 1).threadKey
		val second = row(inbox, 2).threadKey
		first shouldBe "id:${row(inbox, 1).id}"
		second shouldBe "id:${row(inbox, 2).id}"
	}

	@Test
	fun `detachFolder and pruneDetached keep linked rows and remove the rest of that folder only`() {
		store.upsert(account.id, inbox.id, listOf(remote(1, "<a@example.test>"), remote(2, "<b@example.test>")))
		store.upsert(account.id, archive.id, listOf(remote(1, "<z@example.test>")))
		val linked = row(inbox, 1)
		link(linked)

		store.detachFolder(inbox.id)
		store.locatedUids(inbox.id).shouldBeEmpty()
		store.locatedUids(archive.id) shouldContainExactly listOf(1L)

		store.pruneDetached(inbox.id) shouldBe 1
		messages
			.findById(linked.id)
			.orElseThrow()
			.uid
			.shouldBeNull()
		byMessageId("<b@example.test>").shouldBeEmpty()
		row(archive, 1).messageIdHeader shouldBe "<z@example.test>"
		// Nothing left to prune.
		store.pruneDetached(inbox.id) shouldBe 0
	}

	@Test
	fun `a reply before its parent that refers to the root stays with the root arriving last in another batch`() {
		store.upsert(account.id, inbox.id, listOf(remote(1, "<c@example.test>", subject = "Re: Plan", inReplyTo = "<b@example.test>")))
		store.upsert(
			account.id,
			archive.id,
			listOf(remote(1, "<b@example.test>", subject = "Re: Plan", inReplyTo = "<a@example.test>", references = "<a@example.test>")),
		)
		store.upsert(account.id, other.id, listOf(remote(1, "<a@example.test>", subject = "Unrelated subject")))

		listOf(row(inbox, 1), row(archive, 1), row(other, 1)).map { it.threadKey } shouldBe List(3) { "<a@example.test>" }
	}

	@Test
	fun `a root synced after its reply still shares the subject conversation of other messages without references`() {
		// INBOX is synced before Sent: the colleague's reply arrives first, then the sent root, then a reminder that
		// has no References/In-Reply-To but the same subject (subject fallback).
		store.upsert(account.id, inbox.id, listOf(remote(1, "<reply@example.test>", subject = "Re: Offsite", inReplyTo = "<root@example.test>")))
		store.upsert(account.id, archive.id, listOf(remote(1, "<root@example.test>", subject = "Offsite")))
		store.upsert(account.id, other.id, listOf(remote(1, "<reminder@example.test>", subject = "Offsite")))

		listOf(row(inbox, 1), row(archive, 1), row(other, 1)).map { it.threadKey }.toSet().size shouldBe 1
	}

	@Test
	fun `linkedDetachedMessageIds lists the Message-IDs of linked detached rows of that folder only`() {
		store.upsert(
			account.id,
			inbox.id,
			listOf(remote(1, "<a@example.test>"), remote(2, "<b@example.test>"), remote(3, null, subject = "No id")),
		)
		store.upsert(account.id, archive.id, listOf(remote(1, "<z@example.test>")))
		link(row(inbox, 1))
		link(row(inbox, 3))
		link(row(archive, 1))
		store.linkedDetachedMessageIds(inbox.id).shouldBeEmpty()

		store.detachFolder(inbox.id)
		store.detachFolder(archive.id)

		store.linkedDetachedMessageIds(inbox.id) shouldContainExactly listOf("<a@example.test>")
	}

	@Test
	fun `finish records the folder state`() {
		val at = Instant.parse("2026-10-20T10:00:00Z")

		store.finish(inbox.id, RemoteFolderStatus(uidValidity = 17, uidNext = 42, highestModSeq = 900, condstore = true, qresync = false), at)

		val stored = folders.findById(inbox.id).orElseThrow()
		stored.uidValidity shouldBe 17
		stored.uidNext shouldBe 42
		stored.highestModSeq shouldBe 900
		stored.lastSyncedAt shouldBe at

		store.finish(inbox.id, RemoteFolderStatus(17, 43, null, condstore = false, qresync = false), at)
		folders
			.findById(inbox.id)
			.orElseThrow()
			.highestModSeq
			.shouldBeNull()
	}

	@Test
	fun `finish of an unknown folder fails`() {
		shouldThrow<NotFoundException> {
			store.finish(UuidV7.generate(), RemoteFolderStatus(1, 1, null, condstore = false, qresync = false), Instant.EPOCH)
		}
	}
}
