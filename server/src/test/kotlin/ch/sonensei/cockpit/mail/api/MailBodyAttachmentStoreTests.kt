package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailBodyCache
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration

/**
 * Attachment metadata cached next to a body (`mail_body_attachments`): it lives and goes with its body in the LRU
 * body cache, and only both together are a cache hit for [MailContentLoader]. Same context as `MailBodyCacheTests`
 * (a 100-byte cache budget).
 */
@SpringBootTest(properties = ["cockpit.mail.body-cache-max-bytes=100"])
@Import(MutableClockTestConfiguration::class)
class MailBodyAttachmentStoreTests(
	@Autowired private val store: MailBodyAttachmentStore,
	@Autowired private val cache: MailBodyCache,
	@Autowired private val loader: MailContentLoader,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailMessageRepository,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private lateinit var folder: MailFolder
	private var nextUid = 1L

	@BeforeEach
	fun setUp() {
		jdbc.execute("delete from mail_account")
		clock.set(MutableClockTestConfiguration.START)
		val account =
			accounts.save(
				MailAccount(
					MailAccountType.IMAP_SMTP,
					"Example",
					"ann@example.test",
					"imap.example.test",
					993,
					MailSecurity.SSL_TLS,
					"smtp.example.test",
					465,
					MailSecurity.SSL_TLS,
					"ann",
				),
			)
		folder = folders.save(MailFolder(account.id, "INBOX", "INBOX"))
	}

	private fun newMessage(): MailMessage = messages.save(MailMessage(folder.accountId, folder.id, nextUid++))

	private fun attachmentRows(): Int = jdbc.queryForObject("select count(*) from mail_body_attachments", Int::class.java)!!

	@Test
	fun `attachment metadata round-trips with every field, in order`() {
		val message = newMessage()
		cache.put(message.id, "text", null)
		val parts =
			listOf(
				MailAttachment("1.2", "Grüezi logo.png", "image/png", 1200, inline = true, contentId = "logo@example.test"),
				MailAttachment("2", null, "application/octet-stream", null, inline = false, contentId = null),
			)

		store.save(message.id, parts)

		store.find(message.id) shouldContainExactly parts
	}

	@Test
	fun `an empty list is cached as such, and an unknown message has no entry`() {
		val message = newMessage()
		cache.put(message.id, "text", null)
		store.save(message.id, emptyList())

		store.find(message.id).shouldBeEmpty()
		store.find(newMessage().id).shouldBeNull()
		store.find(UuidV7.generate()).shouldBeNull()
	}

	@Test
	fun `saving again replaces the metadata`() {
		val message = newMessage()
		cache.put(message.id, "text", null)
		store.save(message.id, listOf(MailAttachment("2", "old.pdf", "application/pdf", 1, inline = false, contentId = null)))

		store.save(message.id, listOf(MailAttachment("3", "new.pdf", "application/pdf", 2, inline = false, contentId = null)))

		store.find(message.id)!!.map { it.filename } shouldContainExactly listOf("new.pdf")
		attachmentRows() shouldBe 1
	}

	@Test
	fun `metadata needs a cached body`() {
		val message = newMessage()

		shouldThrow<DataIntegrityViolationException> { store.save(message.id, emptyList()) }
		attachmentRows() shouldBe 0
	}

	@Test
	fun `evicting a body from the LRU cache removes its attachment metadata`() {
		val old = newMessage()
		cache.put(old.id, "o".repeat(60), null)
		store.save(old.id, listOf(MailAttachment("2", "a.pdf", "application/pdf", 1, inline = false, contentId = null)))
		clock.advance(Duration.ofMinutes(1))
		val current = newMessage()

		cache.put(current.id, "c".repeat(60), null)

		cache.get(old.id).shouldBeNull()
		store.find(old.id).shouldBeNull()
		attachmentRows() shouldBe 0
	}

	@Test
	fun `deleting a message removes body and attachment metadata`() {
		val message = newMessage()
		cache.put(message.id, "text", null)
		store.save(message.id, emptyList())

		messages.deleteById(message.id)

		attachmentRows() shouldBe 0
	}

	@Test
	fun `a cached body with its metadata is loaded without the mail server`() {
		val message = newMessage()
		cache.put(message.id, "Plain", "<p>Html</p>")
		val parts = listOf(MailAttachment("2", "a.pdf", "application/pdf", 10, inline = false, contentId = null))
		store.save(message.id, parts)

		// The account points at an unreachable fictitious host: any server access would fail.
		val content = loader.load(message)

		content shouldBe MailContent("Plain", "<p>Html</p>", parts)
	}

	@Test
	fun `a body without its attachment metadata is no cache hit`() {
		val message = newMessage()
		cache.put(message.id, "Plain", null)
		// Without a UID the server copy cannot be found: the loader reports it gone instead of using the cache.
		val detached = messages.save(message.apply { detach() })

		shouldThrow<NotFoundException> { loader.load(detached) }
	}

	@Test
	fun `metadata without a body is not possible, so an evicted body is always a cache miss`() {
		val message = newMessage()
		cache.put(message.id, "Plain", null)
		store.save(message.id, emptyList())
		jdbc.update("delete from mail_body where message_id = ?", message.id)

		store.find(message.id).shouldBeNull()
		shouldThrow<NotFoundException> { loader.load(messages.save(message.apply { detach() })) }
	}
}
