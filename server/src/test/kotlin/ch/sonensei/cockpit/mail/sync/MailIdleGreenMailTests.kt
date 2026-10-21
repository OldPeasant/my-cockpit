package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.events.domain.ClientNotification
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.mail.imap.MailAccountChanges
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailServer
import ch.sonensei.cockpit.mail.imap.NewMailAccount
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * IMAP IDLE against GreenMail: a message delivered over SMTP shows up in the cache within seconds although polling
 * runs only once an hour, with an `EMAIL` change event and (per account setting) a `mail` notification. The IDLE is
 * renewed every second here, so new mail must also arrive across re-IDLEs.
 */
@ExternalTest
@SpringBootTest(
	properties = [
		"cockpit.mail.push.enabled=true",
		"cockpit.mail.push.poll-interval=PT1H",
		"cockpit.mail.push.re-idle-interval=PT1S",
		"cockpit.mail.push.initial-backoff=PT0.2S",
		"cockpit.mail.push.max-backoff=PT1S",
	],
)
@WithCockpitUser
@DirtiesContext
class MailIdleGreenMailTests(
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountService,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailSyncRepository,
	@Autowired private val push: MailPushService,
	@Autowired private val recorder: EventRecorder,
) : PostgresTestBase() {
	private lateinit var account: MailAccount

	@TestConfiguration
	class Events {
		@Bean
		fun eventRecorder() = EventRecorder()
	}

	/** Records the change events and client notifications published on any thread. */
	class EventRecorder {
		val changes = CopyOnWriteArrayList<ChangeEvent>()
		val notifications = CopyOnWriteArrayList<ClientNotification>()

		@EventListener
		fun onChange(event: ChangeEvent) {
			changes += event
		}

		@EventListener
		fun onNotification(event: ClientNotification) {
			notifications += event
		}
	}

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
		// Creating the account starts its watcher; it is ready once it caught up and IDLEs.
		eventually("the Inbox IDLE to start") { push.watcher(account.id)?.idling == true }
		recorder.changes.clear()
		recorder.notifications.clear()
	}

	@AfterEach
	fun tearDown() {
		jdbc.update("delete from mail_account")
		push.reconcile()
	}

	private fun deliver(subject: String) =
		GreenMailUtil.sendTextEmail(EMAIL, "ann.example@example.test", subject, "Fictitious body.", greenMail.smtp.serverSetup)

	private fun inboxMessage(subject: String): MailMessage? {
		val inbox = folders.findByAccountIdAndRole(account.id, MailFolderRole.INBOX) ?: return null
		return messages.findByFolderIdAndUidIsNotNull(inbox.id).firstOrNull { it.subject == subject }
	}

	@Test
	fun `a delivered message appears within seconds without polling, with a change event and a notification`() {
		val started = System.nanoTime()
		deliver("Budget review")

		eventually("the delivered message to be cached") { inboxMessage("Budget review") != null }
		val elapsed = Duration.ofNanos(System.nanoTime() - started)
		val message = inboxMessage("Budget review").shouldNotBeNull()

		(elapsed < ARRIVAL_LIMIT) shouldBe true
		recorder.changes shouldContain
			ChangeEvent(ItemType.EMAIL, message.id, ChangeOp.CREATED, recorder.changes.first { it.id == message.id }.at)
		eventually("the new-mail notification") { recorder.notifications.isNotEmpty() }
		val notification = recorder.notifications.single()
		notification.name shouldBe NewMailAnnouncer.EVENT_NAME
		val data = notification.data as NewMailNotification
		data.accountId shouldBe account.id
		data.count shouldBe 1
		data.messages.single().id shouldBe message.id
		data.messages.single().subject shouldBe "Budget review"
	}

	@Test
	fun `mail keeps arriving across re-IDLEs`() {
		deliver("First")
		eventually("the first message") { inboxMessage("First") != null }
		// Several re-IDLE intervals pass before the next delivery.
		Thread.sleep(2_500)
		push.watcher(account.id)?.idling shouldBe true

		deliver("Second")

		eventually("the second message") { inboxMessage("Second") != null }
	}

	@Test
	fun `an account that does not notify gets change events but no notification`() {
		accounts.update(account.id, MailAccountChanges(notifyNewMail = false), ETags.of(accounts.get(account.id).version))
		recorder.changes.clear()

		deliver("Quiet")

		eventually("the quiet message") { inboxMessage("Quiet") != null }
		val message = inboxMessage("Quiet").shouldNotBeNull()
		recorder.changes.filter { it.type == ItemType.EMAIL.name && it.id == message.id } shouldHaveSize 1
		Thread.sleep(300)
		recorder.notifications.shouldBeEmpty()
	}

	@Test
	fun `deleting the account stops its watcher`() {
		val watcher = push.watcher(account.id).shouldNotBeNull()

		accounts.delete(account.id, ETags.of(accounts.get(account.id).version))

		push.watcher(account.id) shouldBe null
		eventually("the watcher thread to end") { !watcher.alive }
	}

	private fun eventually(
		what: String,
		condition: () -> Boolean,
	) {
		val deadline = System.nanoTime() + ARRIVAL_LIMIT.toNanos()
		while (!condition()) {
			check(System.nanoTime() < deadline) { "Timed out waiting for $what" }
			Thread.sleep(50)
		}
	}

	companion object {
		private const val HOST = "localhost"
		private const val EMAIL = "jane.doe@example.test"
		private const val USER = "jane"
		private const val PASSWORD = "fictitious-password"

		/** "Within seconds": far below the one-hour poll interval. */
		private val ARRIVAL_LIMIT = Duration.ofSeconds(10)

		@JvmField
		@RegisterExtension
		val greenMail: GreenMailExtension =
			GreenMailExtension(arrayOf(ServerSetupTest.SMTP, ServerSetupTest.IMAP).map { it.dynamicPort() }.toTypedArray())
	}
}
