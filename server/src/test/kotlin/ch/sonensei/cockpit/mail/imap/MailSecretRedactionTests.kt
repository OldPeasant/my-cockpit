package ch.sonensei.cockpit.mail.imap

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailSecurity
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.mockk
import jakarta.mail.AuthenticationFailedException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import java.util.UUID

/** Passwords, tokens and server responses never reach `toString`, INFO logs or the Problem Details body. */
class MailSecretRedactionTests {
	private val logger = LoggerFactory.getLogger(MailAccountController::class.java) as Logger
	private val appender = ListAppender<ILoggingEvent>()
	private var previousLevel: Level? = null

	@BeforeEach
	fun attach() {
		previousLevel = logger.level
		logger.level = Level.DEBUG
		appender.start()
		logger.addAppender(appender)
	}

	@AfterEach
	fun detach() {
		logger.detachAppender(appender)
		logger.level = previousLevel
	}

	@Test
	fun `request toString never shows the password`() {
		val create =
			CreateMailAccountRequest(
				type = MailAccountType.IMAP_SMTP,
				emailAddress = "jane.doe@example.test",
				imap = MailServerRequest("imap.example.test", 993, MailSecurity.SSL_TLS),
				username = "jane",
				password = "fictitious-secret-1",
				oauthConnectionId = UUID.fromString("0192f0c4-7a1e-7c3d-9b2a-1f2e3d4c5b6c"),
			)
		val update = UpdateMailAccountRequest(username = "jane", password = "fictitious-secret-2")

		create.toString() shouldNotContain "fictitious-secret-1"
		update.toString() shouldNotContain "fictitious-secret-2"
	}

	@Test
	fun `the connection settings kept per pooled connection never show the password`() {
		val account =
			MailAccount(
				type = MailAccountType.IMAP_SMTP,
				displayName = "Work",
				emailAddress = "jane.doe@example.test",
				imapHost = "imap.example.test",
				imapPort = 993,
				imapSecurity = MailSecurity.SSL_TLS,
				smtpHost = "smtp.example.test",
				smtpPort = 465,
				smtpSecurity = MailSecurity.SSL_TLS,
				username = "jane",
			).apply { password = EncryptedSecret("fictitious-secret-1") }

		ImapConnectionSettings.of(account).toString() shouldNotContain "fictitious-secret-1"
	}

	@Test
	fun `a connection failure answers 502 with a fixed message and logs no server response at INFO`() {
		val cause = AuthenticationFailedException("NO LOGIN failed for jane with fictitious-secret-1")
		val controller = MailAccountController(mockk(), mockk())

		val response = controller.handleConnectionFailure(MailConnectionException(MailConnectionError.AUTHENTICATION_FAILED, cause))

		response.statusCode shouldBe HttpStatus.BAD_GATEWAY
		response.body!!.detail shouldBe MailConnectionError.AUTHENTICATION_FAILED.message
		response.body!!.properties!!["errorCode"] shouldBe "AUTHENTICATION_FAILED"
		response.body.toString() shouldNotContain "fictitious-secret-1"
		val atInfo = appender.list.filter { it.level.isGreaterOrEqual(Level.INFO) }
		atInfo.size shouldBe 1
		atInfo.forEach {
			it.formattedMessage shouldNotContain "fictitious-secret-1"
			it.throwableProxy shouldBe null
		}
	}

	@Test
	fun `an exhausted pool answers 503`() {
		val response =
			MailAccountController(mockk(), mockk()).handleConnectionFailure(MailConnectionException(MailConnectionError.POOL_EXHAUSTED))

		response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
		response.body!!.properties!!["errorCode"] shouldBe "POOL_EXHAUSTED"
	}
}
