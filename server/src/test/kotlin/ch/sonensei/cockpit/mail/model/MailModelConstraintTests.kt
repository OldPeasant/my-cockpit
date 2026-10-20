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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

/** Database constraints and repository queries of the mail model beyond the basic round trips. */
@SpringBootTest
@Import(MutableClockTestConfiguration::class)
class MailModelConstraintTests(
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
	fun `ports must be between 1 and 65535`() {
		accounts.saveAndFlush(imapAccount(imapPort = 1, smtpPort = 65535)).id.shouldNotBeNull()
		for ((imap, smtp) in listOf(0 to 587, 65536 to 587, 993 to 0, 993 to 65536, -1 to 587)) {
			shouldThrow<DataIntegrityViolationException> { accounts.saveAndFlush(imapAccount(imapPort = imap, smtpPort = smtp)) }
		}
	}

	@Test
	fun `the sync window must be at least one month`() {
		accounts.saveAndFlush(imapAccount().apply { syncWindowMonths = 1 }).syncWindowMonths shouldBe 1
		shouldThrow<DataIntegrityViolationException> { accounts.saveAndFlush(imapAccount().apply { syncWindowMonths = 0 }) }
		shouldThrow<DataIntegrityViolationException> { accounts.saveAndFlush(imapAccount().apply { syncWindowMonths = -3 }) }
	}

	@Test
	fun `a Microsoft account cannot carry a password, but an IMAP account may have none`() {
		shouldThrow<DataIntegrityViolationException> {
			accounts.saveAndFlush(imapAccount(type = MailAccountType.MICROSOFT).apply { password = EncryptedSecret("secret") })
		}
		val microsoft =
			accounts.saveAndFlush(imapAccount(type = MailAccountType.MICROSOFT).apply { oauthConnectionId = UuidV7.generate() })
		microsoft.usesOAuth shouldBe true
		accounts.saveAndFlush(imapAccount()).password.shouldBeNull()
	}

	@Test
	fun `unknown enum values are rejected by check constraints`() {
		val account = accounts.saveAndFlush(imapAccount())
		shouldThrow<DataIntegrityViolationException> {
			jdbc.update("update mail_account set type = 'POP3' where id = ?", account.id)
		}
		shouldThrow<DataIntegrityViolationException> {
			jdbc.update("update mail_account set imap_security = 'TLS' where id = ?", account.id)
		}
		shouldThrow<DataIntegrityViolationException> {
			jdbc.update("update mail_account set smtp_security = 'none' where id = ?", account.id)
		}
		val folder = folders.saveAndFlush(MailFolder(account.id, "INBOX", "INBOX"))
		shouldThrow<DataIntegrityViolationException> {
			jdbc.update("update mail_folder set role = 'JUNK' where id = ?", folder.id)
		}
	}

	@Test
	fun `every folder role is accepted and a folder may have no role`() {
		val account = accounts.save(imapAccount())
		MailFolderRole.entries.forEach { role ->
			folders.saveAndFlush(MailFolder(account.id, role.name, role.name).apply { this.role = role })
		}
		folders.saveAndFlush(MailFolder(account.id, "Projects", "Projects")).role.shouldBeNull()
		folders.saveAndFlush(MailFolder(account.id, "Clients", "Clients")).role.shouldBeNull()

		MailFolderRole.entries.forEach { folders.findByAccountIdAndRole(account.id, it).shouldNotBeNull().path shouldBe it.name }
	}

	@Test
	fun `paths, roles, identity emails and defaults are unique per account only`() {
		val first = accounts.save(imapAccount())
		val second = accounts.save(imapAccount())
		listOf(first, second).forEach { account ->
			folders.saveAndFlush(MailFolder(account.id, "INBOX", "INBOX").apply { role = MailFolderRole.INBOX })
			identities.saveAndFlush(MailIdentity(account.id, "Ann", "ann@example.com").apply { isDefault = true })
		}

		folders.findByAccountIdAndPath(second.id, "INBOX").shouldNotBeNull().accountId shouldBe second.id
		folders.findByAccountIdAndPath(second.id, "Archive").shouldBeNull()
		identities.count() shouldBe 2
	}

	@Test
	fun `identity overrides, folder flags and remaining message flags round-trip`() {
		val account = accounts.save(imapAccount())
		val identity =
			identities.saveAndFlush(
				MailIdentity(account.id, "Ann (support)", "support@example.com").apply {
					replyTo = "help@example.com"
					signatureMd = "**Support team**"
				},
			)
		val folder =
			folders.saveAndFlush(
				MailFolder(account.id, "[Gmail]", "[Gmail]").apply {
					role = MailFolderRole.ARCHIVE
					roleOverridden = true
					selectable = false
				},
			)
		val inbox = folders.save(MailFolder(account.id, "INBOX", "INBOX"))
		val message =
			messages.saveAndFlush(
				MailMessage(account.id, inbox.id, 1).apply {
					answered = true
					draft = true
				},
			)

		identities.findById(identity.id).get().let {
			it.accountId shouldBe account.id
			it.name shouldBe "Ann (support)"
			it.replyTo shouldBe "help@example.com"
			it.signatureMd shouldBe "**Support team**"
			it.isDefault shouldBe false
			it.position shouldBe 0
		}
		folders.findById(folder.id).get().let {
			it.name shouldBe "[Gmail]"
			it.roleOverridden shouldBe true
			it.selectable shouldBe false
			it.uidValidity.shouldBeNull()
		}
		messages.findById(message.id).get().let {
			it.answered shouldBe true
			it.draft shouldBe true
			it.keywords.size shouldBe 0
		}
	}

	@Test
	fun `several non-default identities are allowed per account`() {
		val account = accounts.save(imapAccount())
		identities.saveAndFlush(MailIdentity(account.id, "A", "a@example.com"))
		identities.saveAndFlush(MailIdentity(account.id, "B", "b@example.com"))

		identities.findByAccountIdOrderByPositionAscIdAsc(account.id).size shouldBe 2
	}

	@Test
	fun `a non-positive UID and a negative size are rejected by the database`() {
		val account = accounts.save(imapAccount())
		val inbox = folders.save(MailFolder(account.id, "INBOX", "INBOX"))
		val message = messages.saveAndFlush(MailMessage(account.id, inbox.id, 5))

		shouldThrow<DataIntegrityViolationException> {
			jdbc.update("update mail_message set uid = 0 where id = ?", message.id)
		}
		shouldThrow<DataIntegrityViolationException> {
			jdbc.update("update mail_message set folder_id = null, uid = -1 where id = ?", message.id)
		}
		shouldThrow<DataIntegrityViolationException> {
			messages.saveAndFlush(MailMessage(account.id, inbox.id, 6).apply { sizeBytes = -1 })
		}
	}

	@Test
	fun `the same Message-ID may exist in several folders of an account`() {
		val account = accounts.save(imapAccount())
		val inbox = folders.save(MailFolder(account.id, "INBOX", "INBOX"))
		val archive = folders.save(MailFolder(account.id, "Archive", "Archive"))
		val a = messages.saveAndFlush(message(account.id, inbox.id, 1, "<same@example.com>"))
		val b = messages.saveAndFlush(message(account.id, archive.id, 1, "<same@example.com>"))
		val detached = messages.saveAndFlush(message(account.id, inbox.id, 2, "<same@example.com>").apply { detach() })

		messages.findByAccountIdAndMessageIdHeaderAndFolderIdIsNotNullAndUidIsNotNullOrderByIdAsc(account.id, "<same@example.com>").map {
			it.id
		} shouldContainExactly
			listOf(a.id, b.id)
		messages.findByAccountIdAndMessageIdHeaderOrderByIdAsc(account.id, "<same@example.com>").map { it.id } shouldContainExactly
			listOf(a.id, b.id, detached.id)
	}

	@Test
	fun `a thread lists its located messages by arrival`() {
		val account = accounts.save(imapAccount())
		val inbox = folders.save(MailFolder(account.id, "INBOX", "INBOX"))
		val sent = folders.save(MailFolder(account.id, "Sent", "Sent"))
		val reply = messages.save(message(account.id, sent.id, 1, "<r@example.com>", "<root@example.com>", "2026-10-19T12:00:00Z"))
		val root = messages.save(message(account.id, inbox.id, 1, "<root@example.com>", "<root@example.com>", "2026-10-19T10:00:00Z"))
		messages.save(message(account.id, inbox.id, 2, "<x@example.com>", "<root@example.com>", "2026-10-19T11:00:00Z").apply { detach() })
		messages.save(message(account.id, inbox.id, 3, "<o@example.com>", "<other@example.com>", "2026-10-19T09:00:00Z"))

		messages.findByAccountIdAndThreadKeyAndFolderIdIsNotNullAndUidIsNotNullOrderByReceivedAtAscIdAsc(account.id, "<root@example.com>").map {
			it.id
		} shouldContainExactly
			listOf(root.id, reply.id)
	}

	@Test
	fun `accounts list by display name and folders by path`() {
		val zeta = accounts.save(imapAccount(displayName = "Zeta"))
		val alpha = accounts.save(imapAccount(displayName = "Alpha"))
		folders.save(MailFolder(alpha.id, "INBOX/Projects", "Projects"))
		folders.save(MailFolder(alpha.id, "Archive", "Archive"))
		folders.save(MailFolder(alpha.id, "INBOX", "INBOX"))

		accounts.findAllByOrderByDisplayNameAscIdAsc().map { it.id } shouldContainExactly listOf(alpha.id, zeta.id)
		folders.findByAccountIdOrderByPathAsc(alpha.id).map { it.path } shouldContainExactly listOf("Archive", "INBOX", "INBOX/Projects")
		folders.findByAccountIdOrderByPathAsc(zeta.id).size shouldBe 0
	}

	@Test
	fun `deleting an account removes its folders, located messages and identities`() {
		val account = accounts.save(imapAccount())
		val inbox = folders.save(MailFolder(account.id, "INBOX", "INBOX"))
		messages.save(message(account.id, inbox.id, 1, "<a@example.com>"))
		messages.save(message(account.id, inbox.id, 2, "<b@example.com>").apply { detach() })
		identities.save(MailIdentity(account.id, "Ann", "ann@example.com"))
		val other = accounts.save(imapAccount())
		val otherInbox = folders.save(MailFolder(other.id, "INBOX", "INBOX"))
		messages.save(message(other.id, otherInbox.id, 1, "<a@example.com>"))

		accounts.deleteById(account.id)

		folders.findByAccountIdOrderByPathAsc(account.id).size shouldBe 0
		messages.findByAccountIdAndMessageIdHeaderOrderByIdAsc(account.id, "<a@example.com>").size shouldBe 0
		messages.count() shouldBe 1
		identities.count() shouldBe 0
	}

	@Test
	fun `deleting a folder keeps its messages as link targets`() {
		val account = accounts.save(imapAccount())
		val folder = folders.save(MailFolder(account.id, "Projects", "Projects"))
		val message = messages.saveAndFlush(message(account.id, folder.id, 3, "<a@example.com>", "<a@example.com>"))
		val other = folders.save(MailFolder(account.id, "Other", "Other"))
		val copy = messages.saveAndFlush(message(account.id, other.id, 3, "<a@example.com>", "<a@example.com>"))

		folders.deleteById(folder.id)

		val kept = messages.findById(message.id).get()
		kept.folderId.shouldBeNull()
		kept.isLocated shouldBe false
		// The stale UID left behind is not a located copy, nor a thread member.
		messages.findByAccountIdAndMessageIdHeaderAndFolderIdIsNotNullAndUidIsNotNullOrderByIdAsc(account.id, "<a@example.com>").map {
			it.id
		} shouldContainExactly listOf(copy.id)
		messages.findByAccountIdAndThreadKeyAndFolderIdIsNotNullAndUidIsNotNullOrderByReceivedAtAscIdAsc(account.id, "<a@example.com>").map {
			it.id
		} shouldContainExactly listOf(copy.id)
	}

	@Test
	fun `stale UIDs of a deleted folder do not collide and the row can be located again`() {
		val account = accounts.save(imapAccount())
		val a = folders.save(MailFolder(account.id, "A", "A"))
		val b = folders.save(MailFolder(account.id, "B", "B"))
		val first = messages.saveAndFlush(message(account.id, a.id, 1, "<1@example.com>"))
		messages.saveAndFlush(message(account.id, b.id, 1, "<2@example.com>"))

		folders.deleteById(a.id)
		folders.deleteById(b.id)

		val inbox = folders.save(MailFolder(account.id, "INBOX", "INBOX"))
		val stale = messages.findById(first.id).get()
		stale.locate(inbox.id, 1)
		messages.saveAndFlush(stale).isLocated shouldBe true
	}

	private fun message(
		accountId: UUID,
		folderId: UUID,
		uid: Long,
		messageId: String,
		thread: String? = null,
		received: String? = null,
	) = MailMessage(accountId, folderId, uid).apply {
		messageIdHeader = messageId
		threadKey = thread
		receivedAt = received?.let(Instant::parse)
	}

	private fun imapAccount(
		type: MailAccountType = MailAccountType.IMAP_SMTP,
		displayName: String = "Example",
		imapPort: Int = 993,
		smtpPort: Int = 587,
	) = MailAccount(
		type = type,
		displayName = displayName,
		emailAddress = "ann@example.com",
		imapHost = "imap.example.com",
		imapPort = imapPort,
		imapSecurity = MailSecurity.SSL_TLS,
		smtpHost = "smtp.example.com",
		smtpPort = smtpPort,
		smtpSecurity = MailSecurity.STARTTLS,
		username = "ann",
	)
}
