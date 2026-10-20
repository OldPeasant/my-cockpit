package ch.sonensei.cockpit.mail.model

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

@SpringBootTest
@Import(MutableClockTestConfiguration::class)
class MailModelPersistenceTests(
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val identities: MailIdentityRepository,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailMessageRepository,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	fun reset() {
		jdbc.execute("delete from mail_account")
	}

	@Test
	fun `an IMAP account is stored with its password encrypted`() {
		val saved =
			accounts.save(
				imapAccount().apply {
					password = EncryptedSecret("app-password-123")
					signatureMd = "-- \nAnn"
					notifyNewMail = false
					syncWindowMonths = 6
				},
			)

		val loaded = accounts.findById(saved.id).get()
		loaded.type shouldBe MailAccountType.IMAP_SMTP
		loaded.imapHost shouldBe "imap.example.com"
		loaded.imapPort shouldBe 993
		loaded.imapSecurity shouldBe MailSecurity.SSL_TLS
		loaded.smtpSecurity shouldBe MailSecurity.STARTTLS
		loaded.password?.reveal() shouldBe "app-password-123"
		loaded.signatureMd shouldBe "-- \nAnn"
		loaded.notifyNewMail shouldBe false
		loaded.syncWindowMonths shouldBe 6
		loaded.usesOAuth shouldBe false
		val stored = jdbc.queryForObject("select password from mail_account where id = ?", String::class.java, saved.id)
		stored.shouldNotBeNull() shouldNotContain "app-password-123"
	}

	@Test
	fun `an OAuth account references its connection and defaults to 12 months and notifications`() {
		val connectionId = UuidV7.generate()
		val saved = accounts.save(googleAccount().apply { oauthConnectionId = connectionId })

		val loaded = accounts.findById(saved.id).get()
		loaded.oauthConnectionId shouldBe connectionId
		loaded.password.shouldBeNull()
		loaded.usesOAuth shouldBe true
		loaded.notifyNewMail shouldBe true
		loaded.syncWindowMonths shouldBe 12
	}

	@Test
	fun `the database rejects mixing password and OAuth credentials`() {
		shouldThrow<DataIntegrityViolationException> {
			accounts.saveAndFlush(googleAccount().apply { password = EncryptedSecret("secret") })
		}
		shouldThrow<DataIntegrityViolationException> {
			accounts.saveAndFlush(imapAccount().apply { oauthConnectionId = UuidV7.generate() })
		}
	}

	@Test
	fun `identity emails are unique per account regardless of case and one identity is the default`() {
		val account = accounts.save(imapAccount())
		identities.save(MailIdentity(account.id, "Ann", "ann@example.com").apply { isDefault = true })
		identities.save(MailIdentity(account.id, "Ann (work)", "ann.work@example.com").apply { position = 1 })

		identities.findByAccountIdOrderByPositionAscIdAsc(account.id).map { it.email } shouldContainExactly
			listOf("ann@example.com", "ann.work@example.com")
		shouldThrow<DataIntegrityViolationException> {
			identities.saveAndFlush(MailIdentity(account.id, "Dup", "ANN@example.com"))
		}
		shouldThrow<DataIntegrityViolationException> {
			identities.saveAndFlush(MailIdentity(account.id, "Other", "other@example.com").apply { isDefault = true })
		}
	}

	@Test
	fun `folders keep their sync state and a role exists once per account`() {
		val account = accounts.save(imapAccount())
		val inbox =
			folders.save(
				MailFolder(account.id, "INBOX", "INBOX").apply {
					role = MailFolderRole.INBOX
					delimiter = "/"
					uidValidity = 1_700_000_000
					uidNext = 4_294_967_295
					highestModSeq = 9_000_000_000
					lastSyncedAt = Instant.parse("2026-10-20T08:00:00Z")
				},
			)

		val loaded = folders.findByAccountIdAndRole(account.id, MailFolderRole.INBOX).shouldNotBeNull()
		loaded.id shouldBe inbox.id
		loaded.uidNext shouldBe 4_294_967_295
		loaded.highestModSeq shouldBe 9_000_000_000
		loaded.lastSyncedAt shouldBe Instant.parse("2026-10-20T08:00:00Z")
		shouldThrow<DataIntegrityViolationException> {
			folders.saveAndFlush(MailFolder(account.id, "Posteingang", "Posteingang").apply { role = MailFolderRole.INBOX })
		}
		shouldThrow<DataIntegrityViolationException> {
			folders.saveAndFlush(MailFolder(account.id, "INBOX", "INBOX"))
		}
	}

	@Test
	fun `message headers, flags and addresses round-trip`() {
		val account = accounts.save(imapAccount())
		val inbox = folders.save(MailFolder(account.id, "INBOX", "INBOX"))
		val saved =
			messages.save(
				MailMessage(account.id, inbox.id, 42).apply {
					modSeq = 17
					messageIdHeader = "<m1@example.com>"
					inReplyTo = "<m0@example.com>"
					referencesHeader = "<root@example.com> <m0@example.com>"
					threadKey = "<root@example.com>"
					subject = "Quarterly figures"
					from = listOf(MailAddress("bob@example.org", "Doe, Bob"))
					to = listOf(MailAddress("ann@example.com", "Ann Example"), MailAddress("team@example.com"))
					cc = listOf(MailAddress("zoë@example.ch", "Zoë Müller"))
					sentAt = Instant.parse("2026-10-19T10:00:00Z")
					receivedAt = Instant.parse("2026-10-19T10:00:05Z")
					seen = true
					flagged = true
					keywords = arrayOf("\$Forwarded", "Work")
					sizeBytes = 12_345
					hasAttachments = true
					snippet = "Hi Ann, here are the numbers"
				},
			)

		val loaded = messages.findByFolderIdAndUid(inbox.id, 42).shouldNotBeNull()
		loaded.id shouldBe saved.id
		loaded.from shouldContainExactly listOf(MailAddress("bob@example.org", "Doe, Bob"))
		loaded.to shouldContainExactly listOf(MailAddress("ann@example.com", "Ann Example"), MailAddress("team@example.com"))
		loaded.cc shouldContainExactly listOf(MailAddress("zoë@example.ch", "Zoë Müller"))
		loaded.bcc shouldBe emptyList()
		loaded.keywords.toList() shouldContainExactly listOf("\$Forwarded", "Work")
		loaded.seen shouldBe true
		loaded.flagged shouldBe true
		loaded.answered shouldBe false
		loaded.sizeBytes shouldBe 12_345
		loaded.hasAttachments shouldBe true
		loaded.threadKey shouldBe "<root@example.com>"
		jdbc.queryForObject("select bcc_addresses from mail_message where id = ?", String::class.java, saved.id).shouldBeNull()
	}

	@Test
	fun `a UID is unique per folder, and a detached row frees it`() {
		val account = accounts.save(imapAccount())
		val inbox = folders.save(MailFolder(account.id, "INBOX", "INBOX"))
		val first = messages.save(MailMessage(account.id, inbox.id, 7).apply { messageIdHeader = "<a@example.com>" })
		shouldThrow<DataIntegrityViolationException> { messages.saveAndFlush(MailMessage(account.id, inbox.id, 7)) }

		first.detach()
		messages.saveAndFlush(first)
		messages.saveAndFlush(MailMessage(account.id, inbox.id, 7)).uid shouldBe 7
	}

	@Test
	fun `deleting a folder keeps its messages as link targets, deleting the account removes everything`() {
		val account = accounts.save(imapAccount())
		val folder = folders.save(MailFolder(account.id, "Projects", "Projects"))
		val message = messages.save(MailMessage(account.id, null, null).apply { messageIdHeader = "<a@example.com>" })
		message.locate(folder.id, 3)
		messages.saveAndFlush(message)
		identities.save(MailIdentity(account.id, "Ann", "ann@example.com"))

		jdbc.update("update mail_message set uid = null where id = ?", message.id)
		folders.deleteById(folder.id)
		messages
			.findById(message.id)
			.get()
			.folderId
			.shouldBeNull()

		accounts.deleteById(account.id)
		messages.count() shouldBe 0
		identities.count() shouldBe 0
	}

	@Test
	fun `a UID needs a folder and must be positive`() {
		val accountId = UuidV7.generate()
		shouldThrow<IllegalArgumentException> { MailMessage(accountId, null, 5) }
		shouldThrow<IllegalArgumentException> { MailMessage(accountId, UuidV7.generate(), 0) }
		MailMessage(accountId, UuidV7.generate(), 1).isLocated shouldBe true
		MailMessage(accountId, null, null).isLocated shouldNotBe true
	}

	private fun imapAccount() =
		MailAccount(
			type = MailAccountType.IMAP_SMTP,
			displayName = "Example",
			emailAddress = "ann@example.com",
			imapHost = "imap.example.com",
			imapPort = 993,
			imapSecurity = MailSecurity.SSL_TLS,
			smtpHost = "smtp.example.com",
			smtpPort = 587,
			smtpSecurity = MailSecurity.STARTTLS,
			username = "ann",
		)

	private fun googleAccount() =
		MailAccount(
			type = MailAccountType.GOOGLE,
			displayName = "Gmail",
			emailAddress = "ann.example@gmail.com",
			imapHost = "imap.gmail.com",
			imapPort = 993,
			imapSecurity = MailSecurity.SSL_TLS,
			smtpHost = "smtp.gmail.com",
			smtpPort = 465,
			smtpSecurity = MailSecurity.SSL_TLS,
			username = "ann.example@gmail.com",
		)
}
