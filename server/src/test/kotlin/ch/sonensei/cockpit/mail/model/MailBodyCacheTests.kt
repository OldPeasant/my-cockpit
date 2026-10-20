package ch.sonensei.cockpit.mail.model

import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

	@Test
	fun `reading a body marks it as used without bumping its version`() {
		val message = newMessage(1)
		cache.put(message, "text", null)
		val stored = bodies.findByMessageId(message).shouldNotBeNull()
		clock.advance(Duration.ofMinutes(5))

		val read = cache.get(message).shouldNotBeNull()

		read.version shouldBe stored.version
		read.lastAccessedAt shouldBe MutableClockTestConfiguration.START.plus(Duration.ofMinutes(5))
	}

	@Test
	fun `replacing a body bumps its version and keeps its id`() {
		val message = newMessage(1)
		cache.put(message, "v1", null)
		val first = bodies.findByMessageId(message).shouldNotBeNull()

		cache.put(message, "v2", "<p>v2</p>")

		val second = bodies.findByMessageId(message).shouldNotBeNull()
		second.id shouldBe first.id
		second.version shouldBe first.version + 1
		second.sizeBytes shouldBe 2L + 9L
	}

	@Test
	fun `a cache filled exactly to the budget evicts nothing`() {
		val a = newMessage(1)
		val b = newMessage(2)
		cache.put(a, "a".repeat(50), null)
		clock.advance(Duration.ofMinutes(1))
		cache.put(b, "b".repeat(50), null)

		cache.evict() shouldBe 0
		cache.totalSizeBytes() shouldBe 100
		clock.advance(Duration.ofMinutes(1))
		cache.put(newMessage(3), "c", null)
		cache.get(a).shouldBeNull()
		cache.get(b).shouldNotBeNull()
	}

	@Test
	fun `evict removes the least recently used bodies until the total fits`() {
		val ids = (1L..3L).map(::newMessage)
		ids.forEach {
			cache.put(it, "z".repeat(30), null)
			clock.advance(Duration.ofMinutes(1))
		}
		jdbc.update("update mail_body set size_bytes = 60")

		cache.evict() shouldBe 2

		cache.get(ids[0]).shouldBeNull()
		cache.get(ids[1]).shouldBeNull()
		cache.get(ids[2]).shouldNotBeNull()
		cache.evict() shouldBe 0
	}

	@Test
	fun `replacing a body with a larger one evicts others but keeps it`() {
		val old = newMessage(1)
		val current = newMessage(2)
		cache.put(old, "o".repeat(40), null)
		clock.advance(Duration.ofMinutes(1))
		cache.put(current, "small", null)
		clock.advance(Duration.ofMinutes(1))

		cache.put(current, "n".repeat(90), null)

		cache.get(old).shouldBeNull()
		cache.get(current).shouldNotBeNull().sizeBytes shouldBe 90
	}

	@Test
	fun `storing a body for an unknown message fails`() {
		shouldThrow<DataIntegrityViolationException> { cache.put(UuidV7.generate(), "text", null) }
		bodies.count() shouldBe 0
	}

	@Test
	fun `concurrent stores of the same message leave one body`() {
		val message = newMessage(1)
		val start = CountDownLatch(1)
		val pool = Executors.newFixedThreadPool(4)
		try {
			val results =
				(1..8).map { n ->
					pool.submit {
						start.await()
						cache.put(message, "version $n", null)
					}
				}
			start.countDown()
			results.forEach { it.get(30, TimeUnit.SECONDS) }
		} finally {
			pool.shutdownNow()
		}

		bodies.count() shouldBe 1
		cache
			.get(message)
			.shouldNotBeNull()
			.textPlain
			.shouldNotBeNull() shouldStartWith "version "
		cache.totalSizeBytes() shouldBe 9
	}

	@Test
	fun `a negative budget is rejected`() {
		shouldThrow<IllegalArgumentException> { MailBodyCache(bodies, clock, -1) }
		MailBodyCache(bodies, clock, 0).maxBytes shouldBe 0
	}

	private fun newMessage(uid: Long): UUID = messages.save(MailMessage(folder.accountId, folder.id, uid)).id
}
