package ch.sonensei.cockpit.mail.model

import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID

@SpringBootTest(properties = ["cockpit.mail.body-cache-max-bytes=100"])
@Import(MutableClockTestConfiguration::class)
class MailBodyCacheTests(
	@Autowired private val cache: MailBodyCache,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailMessageRepository,
	@Autowired private val bodies: MailBodyRepository,
	@Autowired private val clock: MutableClock,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private lateinit var folder: MailFolder

	@BeforeEach
	fun setUp() {
		jdbc.execute("delete from mail_account")
		clock.set(MutableClockTestConfiguration.START)
		val account =
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
		folder = folders.save(MailFolder(account.id, "INBOX", "INBOX"))
	}

	@Test
	fun `a stored body is returned with its UTF-8 size`() {
		val message = newMessage(1)
		cache.put(message, "Grüezi", "<p>Hi</p>")

		val body = cache.get(message).shouldNotBeNull()
		body.textPlain shouldBe "Grüezi"
		body.textHtml shouldBe "<p>Hi</p>"
		body.sizeBytes shouldBe 7L + 9L
		cache.totalSizeBytes() shouldBe 16
		cache.get(newMessage(2)).shouldBeNull()
	}

	@Test
	fun `storing again replaces the body and its size`() {
		val message = newMessage(1)
		cache.put(message, "first version", null)
		cache.put(message, "v2", null)

		cache.get(message).shouldNotBeNull().textPlain shouldBe "v2"
		cache.totalSizeBytes() shouldBe 2
		bodies.count() shouldBe 1
	}

	@Test
	fun `the least recently used bodies are evicted when the budget is exceeded`() {
		val a = newMessage(1)
		val b = newMessage(2)
		val c = newMessage(3)
		cache.put(a, "a".repeat(40), null)
		clock.advance(Duration.ofMinutes(1))
		cache.put(b, "b".repeat(40), null)
		clock.advance(Duration.ofMinutes(1))
		cache.get(a)
		clock.advance(Duration.ofMinutes(1))

		cache.put(c, "c".repeat(40), null)

		cache.get(b).shouldBeNull()
		cache.get(a).shouldNotBeNull()
		cache.get(c).shouldNotBeNull()
		cache.totalSizeBytes() shouldBe 80
	}

	@Test
	fun `a body larger than the whole budget is kept until the next one arrives`() {
		val big = newMessage(1)
		cache.put(big, "x".repeat(150), null)
		cache.get(big).shouldNotBeNull()
		cache.evict() shouldBe 1

		cache.put(big, "x".repeat(150), null)
		clock.advance(Duration.ofMinutes(1))
		cache.put(newMessage(2), "small", null)
		cache.get(big).shouldBeNull()
	}

	@Test
	fun `bodies go away with their message`() {
		val message = newMessage(1)
		cache.put(message, "text", null)
		messages.deleteById(message)

		bodies.count() shouldBe 0
	}

	private fun newMessage(uid: Long): UUID = messages.save(MailMessage(folder.accountId, folder.id, uid)).id
}
