package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailFolderService
import ch.sonensei.cockpit.mail.imap.MailServer
import ch.sonensei.cockpit.mail.imap.NewMailAccount
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.Date
import java.util.Properties
import java.util.UUID

/**
 * Header sync against Dovecot, which (unlike GreenMail) supports CONDSTORE and QRESYNC (RFC 7162): flag changes,
 * expunges and new mail are reconciled once through the QRESYNC open and once through `CHANGEDSINCE` on a server
 * that only announces CONDSTORE. Every test logs in as a user of its own, so each starts with an empty mailbox.
 */
@ExternalTest
@SpringBootTest
@WithCockpitUser
@DirtiesContext
class HeaderSyncDovecotTests(
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountService,
	@Autowired private val folderService: MailFolderService,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailSyncRepository,
	@Autowired private val sync: MailHeaderSyncService,
	@Autowired private val mailbox: RemoteMailbox,
) : PostgresTestBase() {
	private class DovecotContainer(
		config: String,
	) : GenericContainer<DovecotContainer>(DockerImageName.parse(IMAGE)) {
		init {
			withExposedPorts(IMAP_PORT)
			withCopyToContainer(Transferable.of(config), "/etc/dovecot/conf.d/90-test.conf")
			waitingFor(Wait.forLogMessage(".*starting up for imap.*", 1))
		}
	}

	/** One Dovecot user and its account in the cockpit. */
	private inner class Mailbox(
		private val server: DovecotContainer,
	) {
		private val user = "user-${UUID.randomUUID()}"
		val account: MailAccount =
			accounts.create(
				NewMailAccount(
					type = MailAccountType.IMAP_SMTP,
					displayName = "Dovecot",
					emailAddress = "$user@example.test",
					imap = MailServer(server.host, server.getMappedPort(IMAP_PORT), MailSecurity.NONE),
					smtp = MailServer(server.host, SMTP_PORT, MailSecurity.NONE),
					username = user,
					password = PASSWORD,
					oauthConnectionId = null,
					signatureMd = null,
					notifyNewMail = null,
					syncWindowMonths = null,
				),
			)

		fun <T> remote(block: (Store) -> T): T =
			Session.getInstance(Properties()).getStore("imap").use { store ->
				store.connect(server.host, server.getMappedPort(IMAP_PORT), user, PASSWORD)
				block(store)
			}

		fun append(vararg messageIds: String) = remote { store -> store.getFolder("INBOX").appendMessages(messageIds.map(::mail).toTypedArray()) }

		/** Runs [block] on the server copy of the message with that Message-ID, with INBOX open read-write. */
		fun onServer(
			messageId: String,
			block: (Message) -> Unit,
		) = remote { store ->
			val folder = store.getFolder("INBOX")
			folder.open(Folder.READ_WRITE)
			try {
				block(folder.messages.single { it.getHeader("Message-ID")?.firstOrNull() == messageId })
			} finally {
				folder.close(true)
			}
		}

		fun inbox(): MailFolder {
			folderService.sync(account.id)
			return checkNotNull(folders.findByAccountIdAndPath(account.id, "INBOX"))
		}
	}

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from link")
		jdbc.update("delete from mail_account")
	}

	private fun mail(messageId: String): MimeMessage {
		val received = Instant.now()
		val message =
			object : MimeMessage(Session.getInstance(Properties())) {
				override fun getReceivedDate(): Date = Date.from(received)

				override fun updateMessageID() = Unit
			}
		message.setHeader("Message-ID", messageId)
		message.setFrom(InternetAddress("ann.example@example.test", "Ann Example"))
		message.setRecipients(Message.RecipientType.TO, "jane.doe@example.test")
		message.subject = "Subject of $messageId"
		message.sentDate = Date.from(received)
		message.setText("Hello from the fictitious test.", "UTF-8")
		message.saveChanges()
		return message
	}

	private fun cached(folder: MailFolder): List<MailMessage> = messages.findByFolderIdAndUidIsNotNull(folder.id).sortedBy { it.uid }

	private fun cached(
		folder: MailFolder,
		messageId: String,
	): MailMessage = cached(folder).single { it.messageIdHeader == messageId }

	/** Initial sync of a, b, c, then on the server: a seen and flagged, b expunged, d new. */
	private fun syncedThenChanged(mail: Mailbox): MailFolder {
		mail.append("<a@example.test>", "<b@example.test>", "<c@example.test>")
		val inbox = mail.inbox()
		sync.syncFolder(inbox.id).created shouldBe 3
		folders
			.findById(inbox.id)
			.orElseThrow()
			.highestModSeq
			.shouldNotBeNull()
		cached(inbox).mapNotNull { it.modSeq }.size shouldBe 3

		mail.onServer("<a@example.test>") { it.setFlags(Flags(Flags.Flag.SEEN).apply { add(Flags.Flag.FLAGGED) }, true) }
		mail.onServer("<b@example.test>") { it.setFlag(Flags.Flag.DELETED, true) }
		mail.append("<d@example.test>")
		return folders.findById(inbox.id).orElseThrow()
	}

	private fun assertReconciled(
		inbox: MailFolder,
		before: MailFolder,
	) {
		cached(inbox).map { it.messageIdHeader } shouldContainExactly listOf("<a@example.test>", "<c@example.test>", "<d@example.test>")
		cached(inbox, "<a@example.test>").let {
			it.seen shouldBe true
			it.flagged shouldBe true
			(it.modSeq!! > before.highestModSeq!!) shouldBe true
		}
		cached(inbox, "<c@example.test>").seen shouldBe false
		val after = folders.findById(inbox.id).orElseThrow()
		(after.highestModSeq!! > before.highestModSeq!!) shouldBe true
		after.uidNext shouldBe 5
		// Nothing changed since: nothing to do.
		sync.syncFolder(inbox.id) shouldBe FolderSyncResult(inbox.id, full = false, created = 0, updated = 0, removed = 0)
	}

	@Test
	fun `with QRESYNC the open reports flag changes and expunges, and new mail is fetched`() {
		val mail = Mailbox(qresyncServer)
		val before = syncedThenChanged(mail)
		val uidOfA = cached(before, "<a@example.test>").uid
		val uidOfB = cached(before, "<b@example.test>").uid

		// What the server reports when the folder is opened with the cached state.
		mailbox.withFolder(mail.account, "INBOX", KnownFolderState(before.uidValidity!!, before.highestModSeq!!)) { remote ->
			remote.status.qresync shouldBe true
			remote.status.condstore shouldBe true
			val changes = remote.qresyncChanges.shouldNotBeNull()
			changes.vanished shouldBe setOf(uidOfB)
			// The new message d is reported as changed too (it got a MODSEQ since); the store ignores UIDs it has not cached.
			changes.changed.map { it.uid } shouldContainExactly listOf(uidOfA!!, 4L)
			changes.changed.first().flags shouldBe RemoteFlags(seen = true, flagged = true)
			changes.changed
				.first()
				.modSeq
				.shouldNotBeNull()
		}

		val result = sync.syncFolder(before.id)

		result shouldBe FolderSyncResult(before.id, full = false, created = 1, updated = 1, removed = 1)
		assertReconciled(before, before)
	}

	@Test
	fun `with CONDSTORE only, flags changed since the cached HIGHESTMODSEQ and expunges are reconciled`() {
		val mail = Mailbox(condstoreServer)
		System.err.println("DEBUGCAP " + condstoreServer.execInContainer("doveconf", "imap_capability").stdout + condstoreServer.execInContainer("cat", "/etc/dovecot/conf.d/90-test.conf").stdout)
		val before = syncedThenChanged(mail)
		val uidOfA = cached(before, "<a@example.test>").uid!!
		val uidOfC = cached(before, "<c@example.test>").uid!!

		mailbox.withFolder(mail.account, "INBOX", KnownFolderState(before.uidValidity!!, before.highestModSeq!!)) { remote ->
			remote.status.qresync shouldBe false
			remote.status.condstore shouldBe true
			remote.qresyncChanges.shouldBeNull()
			(remote.status.highestModSeq!! > before.highestModSeq!!) shouldBe true
			remote.flagsChangedSince(uidOfA, before.highestModSeq!!).map { it.uid } shouldContainExactly listOf(uidOfA, 4L)
			remote.flagsChangedSince(uidOfA, remote.status.highestModSeq!!).shouldBeEmpty()
			remote.uidsFrom(uidOfA) shouldContainExactly listOf(uidOfA, uidOfC, 4L)
		}

		val result = sync.syncFolder(before.id)

		result shouldBe FolderSyncResult(before.id, full = false, created = 1, updated = 1, removed = 1)
		assertReconciled(before, before)
	}

	companion object {
		private const val IMAGE = "dovecot/dovecot:2.3.21"
		private const val IMAP_PORT = 143
		private const val SMTP_PORT = 25

		/** The image's static passdb accepts any user with this password. */
		private const val PASSWORD = "pass"
		private const val BASE_CONFIG = "protocols = imap\ndisable_plaintext_auth = no\nauth_mechanisms = plain login\n"

		/** Dovecot as it comes: CONDSTORE and QRESYNC. */
		private val qresyncServer = DovecotContainer(BASE_CONFIG).apply { start() }

		/** Announces CONDSTORE but not QRESYNC, so the sync takes the `CHANGEDSINCE` path. */
		private val condstoreServer =
			DovecotContainer(
				BASE_CONFIG +
					"imap_capability = IMAP4rev1 SASL-IR LOGIN-REFERRALS ID ENABLE IDLE LITERAL+ NAMESPACE UIDPLUS CHILDREN " +
					"UNSELECT LIST-EXTENDED MOVE CONDSTORE\n",
			).apply { start() }
	}
}
