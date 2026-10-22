package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailIdentity
import ch.sonensei.cockpit.mail.model.MailIdentityRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

/** Recent correspondents ([CorrespondentService]) from the cached headers in Postgres. */
@SpringBootTest
class CorrespondentServiceTests(
	@Autowired private val service: CorrespondentService,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val identities: MailIdentityRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailMessageRepository,
) : PostgresTestBase() {
	private lateinit var work: MailAccount
	private lateinit var inbox: MailFolder
	private lateinit var sent: MailFolder
	private var nextUid = 1L

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from mail_account")
		work = account("jane.doe@work.example.test")
		identities.save(MailIdentity(work.id, "Jane Support", "Support@Work.Example.test"))
		inbox = folder(work, "INBOX", MailFolderRole.INBOX)
		sent = folder(work, "Sent", MailFolderRole.SENT)
	}

	private fun account(email: String) =
		accounts.save(
			MailAccount(
				MailAccountType.IMAP_SMTP,
				"Account $email",
				email,
				"imap.unreachable.example.test",
				993,
				MailSecurity.SSL_TLS,
				"smtp.unreachable.example.test",
				587,
				MailSecurity.STARTTLS,
				email,
			),
		)

	private fun folder(
		account: MailAccount,
		path: String,
		role: MailFolderRole?,
	) = folders.save(MailFolder(account.id, path, path).apply { this.role = role })

	private fun message(
		folder: MailFolder,
		at: String?,
		from: List<MailAddress> = emptyList(),
		to: List<MailAddress> = emptyList(),
		cc: List<MailAddress> = emptyList(),
		bcc: List<MailAddress> = emptyList(),
		located: Boolean = true,
		receivedAt: String? = null,
	) = messages.save(
		MailMessage(folder.accountId, folder.id, if (located) nextUid++ else null).apply {
			this.from = from
			this.to = to
			this.cc = cc
			this.bcc = bcc
			sentAt = at?.let(Instant::parse)
			this.receivedAt = receivedAt?.let(Instant::parse)
			subject = "Fictitious"
		},
	)

	private fun addresses(
		query: String? = null,
		limit: Int = 50,
	) = service.recent(query, limit).map { it.address }

	@Test
	fun `recipients of sent mail and senders of received mail count, own addresses do not`() {
		message(
			sent,
			"2026-03-05T10:00:00Z",
			from = listOf(MailAddress("jane.doe@work.example.test")),
			to = listOf(MailAddress("bob@example.test"), MailAddress("JANE.DOE@work.example.test")),
			cc = listOf(MailAddress("carol@example.test")),
			bcc = listOf(MailAddress("dave@example.test")),
		)
		message(
			inbox,
			"2026-03-04T10:00:00Z",
			from = listOf(MailAddress("erin@example.test")),
			to = listOf(MailAddress("jane.doe@work.example.test"), MailAddress("frank@example.test")),
		)
		message(inbox, "2026-03-03T10:00:00Z", from = listOf(MailAddress("support@work.example.test")))

		addresses() shouldContainExactly listOf("bob@example.test", "carol@example.test", "dave@example.test", "erin@example.test")
	}

	@Test
	fun `addresses of another account are own addresses too`() {
		val home = account("jane@home.example.test")
		message(inbox, "2026-03-05T10:00:00Z", from = listOf(MailAddress("jane@home.example.test")))
		message(folder(home, "INBOX", MailFolderRole.INBOX), "2026-03-04T10:00:00Z", from = listOf(MailAddress("gina@example.test")))

		addresses() shouldContainExactly listOf("gina@example.test")
	}

	@Test
	fun `Drafts, Trash and Spam are left out, other folders count`() {
		message(folder(work, "Drafts", MailFolderRole.DRAFTS), "2026-03-05T10:00:00Z", from = listOf(MailAddress("draft@example.test")))
		message(folder(work, "Trash", MailFolderRole.TRASH), "2026-03-05T10:00:00Z", from = listOf(MailAddress("trash@example.test")))
		message(folder(work, "Junk", MailFolderRole.SPAM), "2026-03-05T10:00:00Z", from = listOf(MailAddress("spam@example.test")))
		message(folder(work, "Archive", MailFolderRole.ARCHIVE), "2026-03-04T10:00:00Z", from = listOf(MailAddress("archived@example.test")))
		message(folder(work, "Projects", null), "2026-03-03T10:00:00Z", from = listOf(MailAddress("project@example.test")))

		addresses() shouldContainExactly listOf("archived@example.test", "project@example.test")
	}

	@Test
	fun `the sender of mail in Sent does not count, only its recipients`() {
		message(
			sent,
			"2026-03-05T10:00:00Z",
			from = listOf(MailAddress("alias-not-configured@example.test")),
			to = listOf(MailAddress("bob@example.test")),
		)

		addresses() shouldContainExactly listOf("bob@example.test")
	}

	@Test
	fun `newest first, each address once with its most recent date regardless of case`() {
		message(inbox, "2026-03-01T10:00:00Z", from = listOf(MailAddress("bob@example.test", "Bob Old")))
		message(inbox, "2026-03-03T10:00:00Z", from = listOf(MailAddress("carol@example.test")))
		message(sent, "2026-03-05T10:00:00Z", to = listOf(MailAddress("Bob@Example.test", "Bob Example")))

		val result = service.recent(null, 50)

		result.map { it.address } shouldContainExactly listOf("Bob@Example.test", "carol@example.test")
		result[0].name shouldBe "Bob Example"
		result[0].lastAt shouldBe Instant.parse("2026-03-05T10:00:00Z")
		result[1].lastAt shouldBe Instant.parse("2026-03-03T10:00:00Z")
	}

	@Test
	fun `a missing name is filled from older mail`() {
		message(inbox, "2026-03-01T10:00:00Z", from = listOf(MailAddress("bob@example.test", "Bob Example")))
		message(sent, "2026-03-05T10:00:00Z", to = listOf(MailAddress("bob@example.test")))

		val bob = service.recent(null, 50).single()

		bob.name shouldBe "Bob Example"
		bob.lastAt shouldBe Instant.parse("2026-03-05T10:00:00Z")
	}

	@Test
	fun `the arrival date stands in for a missing Date header, mail without either is skipped`() {
		message(inbox, null, from = listOf(MailAddress("late@example.test")), receivedAt = "2026-03-06T10:00:00Z")
		message(inbox, "2026-03-05T10:00:00Z", from = listOf(MailAddress("bob@example.test")))
		message(inbox, null, from = listOf(MailAddress("undated@example.test")))

		addresses() shouldContainExactly listOf("late@example.test", "bob@example.test")
	}

	@Test
	fun `messages no longer on the server are left out`() {
		message(inbox, "2026-03-05T10:00:00Z", from = listOf(MailAddress("gone@example.test")), located = false)

		addresses().shouldBeEmpty()
	}

	@Test
	fun `the query matches address or name, contains and case-insensitive, trimmed`() {
		message(inbox, "2026-03-05T10:00:00Z", from = listOf(MailAddress("bob@example.test", "Robert Builder")))
		message(inbox, "2026-03-04T10:00:00Z", from = listOf(MailAddress("carol@example.test", "Carol Bobson")))
		message(inbox, "2026-03-03T10:00:00Z", from = listOf(MailAddress("dave@example.test")))

		addresses("  BOB ") shouldContainExactly listOf("bob@example.test", "carol@example.test")
		addresses("builder") shouldContainExactly listOf("bob@example.test")
		addresses("example.test") shouldContainExactly listOf("bob@example.test", "carol@example.test", "dave@example.test")
		addresses("   ") shouldContainExactly listOf("bob@example.test", "carol@example.test", "dave@example.test")
		addresses("nobody").shouldBeEmpty()
	}

	@Test
	fun `the limit applies after filtering`() {
		(1..5).forEach { message(inbox, "2026-03-0${it}T10:00:00Z", from = listOf(MailAddress("person$it@example.test"))) }
		message(inbox, "2026-02-01T10:00:00Z", from = listOf(MailAddress("other@example.test")))

		addresses(limit = 2) shouldContainExactly listOf("person5@example.test", "person4@example.test")
		addresses("person", limit = 1) shouldContainExactly listOf("person5@example.test")
	}

	@Test
	fun `no mail means no correspondents`() {
		addresses().shouldBeEmpty()
	}
}
