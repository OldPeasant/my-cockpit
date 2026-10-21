package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.mail.imap.MailAccountService
import ch.sonensei.cockpit.mail.imap.MailServer
import ch.sonensei.cockpit.mail.imap.NewMailAccount
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.ExternalTest
import ch.sonensei.cockpit.support.PostgresTestBase
import com.icegreen.greenmail.junit5.GreenMailExtension
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import java.time.Duration

/**
 * IMAP IDLE push alone against GreenMail. [MailIdleGreenMailTests] renews the IDLE every second, and every renewal is
 * followed by a sync, so there a delivery would also be found without any IDLE notification. Here both the re-IDLE
 * and the poll are an hour away: only the server's IDLE response can bring a delivered message in within seconds.
 */
@ExternalTest
@SpringBootTest(
	properties = [
		"cockpit.mail.push.enabled=true",
		"cockpit.mail.push.poll-interval=PT1H",
		"cockpit.mail.push.re-idle-interval=PT1H",
		"cockpit.mail.push.initial-backoff=PT0.2S",
		"cockpit.mail.push.max-backoff=PT1S",
	],
)
@WithCockpitUser
@DirtiesContext
class MailIdlePushGreenMailTests(
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val accounts: MailAccountService,
	@Autowired private val folders: MailFolderRepository,
	@Autowired private val messages: MailSyncRepository,
	@Autowired private val push: MailPushService,
) : PostgresTestBase() {
	private lateinit var account: MailAccount

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from link")
		jdbc.update("delete from mail_account")
		greenMail.setUser(EMAIL, USER, PASSWORD)
		account =
			accounts.create(
				NewMailAccount(
					type = MailAccountType.IMAP_SMTP,
					displayName = "GreenMail push",
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
		eventually("the Inbox IDLE to start") { push.watcher(account.id)?.idling == true }
		// `idling` is set just before the IDLE command goes out; give it a moment to reach the server.
		Thread.sleep(500)
	}

	@AfterEach
	fun tearDown() {
		jdbc.update("delete from mail_account")
		push.reconcile()
	}

	private fun cached(subject: String): Boolean {
		val inbox = folders.findByAccountIdAndRole(account.id, MailFolderRole.INBOX) ?: return false
		return messages.findByFolderIdAndUidIsNotNull(inbox.id).any { it.subject == subject }
	}

	@Test
	fun `a delivered message is pushed into the cache within seconds with neither re-IDLE nor poll due`() {
		val started = System.nanoTime()
		GreenMailUtil.sendTextEmail(EMAIL, "bob.example@example.test", "Pushed", "Fictitious body.", greenMail.smtp.serverSetup)

		eventually("the pushed message to be cached") { cached("Pushed") }

		(Duration.ofNanos(System.nanoTime() - started) < ARRIVAL_LIMIT) shouldBe true
		push.watcher(account.id)?.idling shouldBe true
		push.watcher(account.id)?.failures shouldBe 0
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
		private const val EMAIL = "max.muster@example.test"
		private const val USER = "max"
		private const val PASSWORD = "fictitious-password"

		/** "Within seconds": far below the one-hour re-IDLE and poll intervals. */
		private val ARRIVAL_LIMIT = Duration.ofSeconds(10)

		@JvmField
		@RegisterExtension
		val greenMail: GreenMailExtension =
			GreenMailExtension(arrayOf(ServerSetupTest.SMTP, ServerSetupTest.IMAP).map { it.dynamicPort() }.toTypedArray())
	}
}
