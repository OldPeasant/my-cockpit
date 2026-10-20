package ch.sonensei.cockpit.mail.model

import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@SpringBootTest
@Import(MutableClockTestConfiguration::class)
class MailItemResolverTests(
	@Autowired private val registry: ItemResolverRegistry,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailMessageRepository,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private lateinit var account: MailAccount
	private lateinit var inbox: MailFolder
	private lateinit var archive: MailFolder

	@BeforeEach
	fun setUp() {
		jdbc.execute("delete from mail_account")
		account =
			accounts.save(
				MailAccount(
					MailAccountType.IMAP_SMTP,
					"Example",
					"ann@example.com",
					"imap.example.com",
					993,
					MailSecurity.SSL_TLS,
					"smtp.example.com",
					465,
					MailSecurity.SSL_TLS,
					"ann",
				),
			)
		inbox = folders.save(MailFolder(account.id, "INBOX", "INBOX"))
		archive = folders.save(MailFolder(account.id, "Archive", "Archive"))
	}

	private val resolver get() = registry.find(ItemType.EMAIL)!!

	@Test
	fun `the EMAIL resolver is registered`() {
		registry.find(ItemType.EMAIL).shouldBeInstanceOf<MailItemResolver>()
	}

	@Test
	fun `a message resolves to subject, sender and deep link`() {
		val message = messages.save(message(inbox, 10, "<a@example.com>"))

		resolver.resolve(message.id) shouldBe
			ResolvedItem("Lunch on Friday?", "Bob Example", "mail", "/mail/messages/${message.id}")
		resolver.exists(message.id) shouldBe true
	}

	@Test
	fun `read messages, missing subjects and senders without a name have fallbacks`() {
		val message =
			messages.save(
				MailMessage(account.id, inbox.id, 11).apply {
					seen = true
					subject = "  "
					from = listOf(MailAddress("noreply@example.org"))
				},
			)

		resolver.resolve(message.id) shouldBe
			ResolvedItem("(no subject)", "noreply@example.org", "drafts", "/mail/messages/${message.id}")
	}

	@Test
	fun `a message whose UID changed is re-resolved by Message-ID, preferring the same folder`() {
		val linked = messages.save(message(inbox, 10, "<a@example.com>"))
		val inArchive = messages.save(message(archive, 3, "<a@example.com>"))
		linked.detach()
		messages.save(linked)

		resolver.resolve(linked.id) shouldBe
			ResolvedItem("Lunch on Friday?", "Bob Example", "mail", "/mail/messages/${inArchive.id}")

		val sameFolder = messages.save(message(inbox, 99, "<a@example.com>"))
		resolver.resolve(linked.id)?.deepLink shouldBe "/mail/messages/${sameFolder.id}"
	}

	@Test
	fun `a detached row whose folder was deleted re-resolves to the oldest copy in another folder`() {
		val projects = folders.save(MailFolder(account.id, "Projects", "Projects"))
		val linked = messages.save(message(projects, 5, "<a@example.com>").apply { detach() })
		val first = messages.save(message(archive, 3, "<a@example.com>"))
		messages.save(message(inbox, 4, "<a@example.com>"))
		folders.deleteById(projects.id)

		messages
			.findById(linked.id)
			.get()
			.folderId
			.shouldBeNull()
		resolver.resolve(linked.id)?.deepLink shouldBe "/mail/messages/${first.id}"
	}

	@Test
	fun `a located row whose folder was deleted re-resolves to a copy in another folder`() {
		val projects = folders.save(MailFolder(account.id, "Projects", "Projects"))
		val linked = messages.save(message(projects, 5, "<a@example.com>"))
		val copy = messages.save(message(archive, 3, "<a@example.com>"))
		folders.deleteById(projects.id)

		messages
			.findById(linked.id)
			.get()
			.uid shouldBe 5
		resolver.resolve(linked.id)?.deepLink shouldBe "/mail/messages/${copy.id}"
	}

	@Test
	fun `a located message resolves to itself even when other copies exist`() {
		val located = messages.save(message(archive, 3, "<a@example.com>"))
		messages.save(message(inbox, 1, "<a@example.com>"))

		resolver.resolve(located.id)?.deepLink shouldBe "/mail/messages/${located.id}"
	}

	@Test
	fun `a message without sender has no subtitle`() {
		val message = messages.save(message(inbox, 10, "<a@example.com>").apply { from = emptyList() })

		resolver.resolve(message.id)?.subtitle.shouldBeNull()
	}

	@Test
	fun `a message gone from the account, or without a Message-ID, does not resolve`() {
		val gone = messages.save(message(inbox, 10, "<gone@example.com>").apply { detach() })
		val noId = messages.save(message(inbox, 11, null).apply { detach() })
		messages.save(message(inbox, 12, "<other@example.com>"))

		resolver.resolve(gone.id).shouldBeNull()
		resolver.resolve(noId.id).shouldBeNull()
		resolver.exists(gone.id) shouldBe false
		resolver.resolve(UuidV7.generate()).shouldBeNull()
	}

	@Test
	fun `copies in another account are not used`() {
		val other =
			accounts.save(
				MailAccount(
					MailAccountType.IMAP_SMTP,
					"Other",
					"ann@example.org",
					"imap.example.org",
					993,
					MailSecurity.SSL_TLS,
					"smtp.example.org",
					465,
					MailSecurity.SSL_TLS,
					"ann",
				),
			)
		val otherInbox = folders.save(MailFolder(other.id, "INBOX", "INBOX"))
		messages.save(message(otherInbox, 1, "<a@example.com>", other))
		val linked = messages.save(message(inbox, 10, "<a@example.com>").apply { detach() })

		resolver.resolve(linked.id).shouldBeNull()
	}

	private fun message(
		folder: MailFolder,
		uid: Long,
		messageId: String?,
		owner: MailAccount = account,
	) = MailMessage(owner.id, folder.id, uid).apply {
		messageIdHeader = messageId
		subject = "Lunch on Friday?"
		from = listOf(MailAddress("bob@example.org", "Bob Example"))
	}
}
