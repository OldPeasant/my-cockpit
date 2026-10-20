package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnection
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import ch.sonensei.cockpit.core.oauth.infra.OAuthConnectionRepository
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.eclipse.angus.mail.imap.IMAPStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/** MAIL_ACCOUNT events, pool eviction events, OAuth account changes and the connection test (no mail server). */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@RecordApplicationEvents
class MailAccountServiceTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val json: JsonMapper,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val service: MailAccountService,
	@Autowired private val repository: MailAccountRepository,
	@Autowired private val connections: OAuthConnectionRepository,
	@Autowired private val tokens: OAuthTokenProvider,
	@Autowired private val changes: ChangeEventPublisher,
	@Autowired private val publisher: ApplicationEventPublisher,
) : PostgresTestBase() {
	private lateinit var googleConnection: UUID
	private lateinit var otherGoogleConnection: UUID
	private lateinit var microsoftConnection: UUID

	@BeforeEach
	fun setUp() {
		jdbc.update("delete from mail_account")
		jdbc.update("delete from oauth_connection")
		googleConnection = connection(OAuthProvider.GOOGLE, "subject-g1", "jane@gmail.example")
		otherGoogleConnection = connection(OAuthProvider.GOOGLE, "subject-g2", "jane.work@gmail.example")
		microsoftConnection = connection(OAuthProvider.MICROSOFT, "subject-m1", "jane@outlook.example")
	}

	private fun connection(
		provider: OAuthProvider,
		subject: String,
		email: String?,
	): UUID = connections.save(OAuthConnection(provider, subject, email, "Jane", "openid", null)).id

	private fun newPasswordAccount() =
		NewMailAccount(
			type = MailAccountType.IMAP_SMTP,
			displayName = "Work",
			emailAddress = "jane.doe@example.test",
			imap = MailServer("imap.example.test", 993, MailSecurity.SSL_TLS),
			smtp = MailServer("smtp.example.test", 587, MailSecurity.STARTTLS),
			username = "jane",
			password = "fictitious-secret-1",
			oauthConnectionId = null,
			signatureMd = null,
			notifyNewMail = null,
			syncWindowMonths = null,
		)

	private fun newGoogleAccount() =
		newPasswordAccount().copy(
			type = MailAccountType.GOOGLE,
			emailAddress = "jane@gmail.example",
			imap = null,
			smtp = null,
			username = null,
			password = null,
			oauthConnectionId = googleConnection,
		)

	private fun ApplicationEvents.accountEvents() =
		stream(ChangeEvent::class.java).toList().filter { it.type == MailAccountService.ACCOUNT_EVENT }.map { it.id to it.op }

	private fun ApplicationEvents.connectionChanges() = stream(MailAccountConnectionChanged::class.java).toList().map { it.accountId }

	private fun etag(account: MailAccount) = "\"${account.version}\""

	@Test
	fun `the pool's idle eviction sweep is scheduled every minute`(
		@Autowired holders: List<ScheduledTaskHolder>,
	) {
		val task =
			holders
				.flatMap { it.scheduledTasks }
				.map { it.task }
				.filterIsInstance<FixedDelayTask>()
				.singleOrNull { it.toString().endsWith("ImapConnectionPool.evictExpired") }

		task.shouldNotBeNull().intervalDuration shouldBe Duration.ofMinutes(1)
	}

	@Test
	fun `create publishes CREATED and no connection change`(events: ApplicationEvents) {
		val account = service.create(newPasswordAccount())

		events.accountEvents() shouldBe listOf(account.id to ChangeOp.CREATED)
		events.connectionChanges().shouldBeEmpty()
	}

	@Test
	fun `an update of other fields publishes UPDATED but keeps the pooled connections`(events: ApplicationEvents) {
		val account = service.create(newPasswordAccount())
		events.clear()

		service.update(
			account.id,
			MailAccountChanges(
				displayName = "Office",
				signatureMd = "-- Jane",
				notifyNewMail = false,
				syncWindowMonths = 6,
				password = "fictitious-secret-1",
			),
			etag(account),
		)

		events.accountEvents() shouldBe listOf(account.id to ChangeOp.UPDATED)
		events.connectionChanges().shouldBeEmpty()
	}

	@Test
	fun `changing the IMAP server, username or password closes the pooled connections`(events: ApplicationEvents) {
		var account = service.create(newPasswordAccount())
		listOf(
			MailAccountChanges(imap = MailServer("imap.example.test", 143, MailSecurity.STARTTLS)),
			MailAccountChanges(username = "jane.doe"),
			MailAccountChanges(password = "fictitious-secret-2"),
		).forEach { change ->
			events.clear()
			account = service.update(account.id, change, etag(account))
			events.connectionChanges() shouldBe listOf(account.id)
		}
	}

	@Test
	fun `changing only the SMTP server is stored but keeps the pooled IMAP connections`(events: ApplicationEvents) {
		val account = service.create(newPasswordAccount())
		events.clear()

		val updated =
			service.update(
				account.id,
				MailAccountChanges(displayName = " ", smtp = MailServer("submit.example.test", 465, MailSecurity.SSL_TLS)),
				etag(account),
			)

		repository.findById(updated.id).get().let {
			it.smtpHost shouldBe "submit.example.test"
			it.smtpPort shouldBe 465
			it.smtpSecurity shouldBe MailSecurity.SSL_TLS
			it.imapHost shouldBe "imap.example.test"
			it.displayName shouldBe "Work"
		}
		events.accountEvents() shouldBe listOf(account.id to ChangeOp.UPDATED)
		events.connectionChanges().shouldBeEmpty()
	}

	@Test
	fun `delete publishes DELETED and closes the pooled connections`(events: ApplicationEvents) {
		val account = service.create(newPasswordAccount())
		events.clear()

		service.delete(account.id, etag(account))

		events.accountEvents() shouldBe listOf(account.id to ChangeOp.DELETED)
		events.connectionChanges() shouldBe listOf(account.id)
	}

	@Test
	fun `a rejected update publishes nothing and changes nothing`(events: ApplicationEvents) {
		val account = service.create(newPasswordAccount())
		events.clear()

		shouldThrow<InvalidRequestException> {
			service.update(account.id, MailAccountChanges(displayName = "Office", emailAddress = " "), etag(account))
		}

		events.accountEvents().shouldBeEmpty()
		repository.findById(account.id).get().displayName shouldBe "Work"
	}

	@Test
	fun `an unknown account is not found`() {
		shouldThrow<NotFoundException> { service.get(UuidV7.generate()) }
		shouldThrow<NotFoundException> { service.testConnection(UuidV7.generate()) }
	}

	@Test
	fun `a whitespace username on create is required like a missing one`() {
		shouldThrow<InvalidRequestException> { service.create(newPasswordAccount().copy(username = "  ")) }
			.fieldErrors
			.map { it.field } shouldBe listOf("username")
	}

	@Test
	fun `a blank display name falls back to the email address`() {
		service.create(newPasswordAccount().copy(displayName = " ")).displayName shouldBe "jane.doe@example.test"
	}

	@Test
	fun `an OAuth account given servers and username keeps them`() {
		val account =
			service.create(
				newGoogleAccount().copy(
					imap = MailServer("imap.relay.example", 993, MailSecurity.SSL_TLS),
					username = "jane.alias@gmail.example",
				),
			)

		account.imapHost shouldBe "imap.relay.example"
		account.smtpHost shouldBe "smtp.gmail.com"
		account.username shouldBe "jane.alias@gmail.example"
		account.password shouldBe null
	}

	private fun patchViaApi(
		account: MailAccount,
		body: Map<String, Any?>,
	) = mockMvc.patch("${MailAccountController.BASE}/${account.id}") {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = json.writeValueAsString(body)
		header(HttpHeaders.IF_MATCH, etag(account))
	}

	@Test
	fun `an OAuth account can switch to another connection of its provider`(events: ApplicationEvents) {
		val account = service.create(newGoogleAccount())
		events.clear()

		patchViaApi(account, mapOf("oauthConnectionId" to otherGoogleConnection.toString())).andExpect {
			status { isOk() }
			jsonPath("$.oauthConnectionId") { value(otherGoogleConnection.toString()) }
			jsonPath("$.hasPassword") { value(false) }
		}

		repository.findById(account.id).get().oauthConnectionId shouldBe otherGoogleConnection
		events.connectionChanges() shouldBe listOf(account.id)
	}

	@Test
	fun `an OAuth account rejects a connection of another provider, an unknown connection and a password`(events: ApplicationEvents) {
		val account = service.create(newGoogleAccount())
		events.clear()

		patchViaApi(account, mapOf("oauthConnectionId" to microsoftConnection.toString())).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("oauthConnectionId") }
		}
		patchViaApi(account, mapOf("oauthConnectionId" to UuidV7.generate().toString())).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("oauthConnectionId") }
		}
		patchViaApi(account, mapOf("password" to "fictitious-secret-3")).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("password") }
		}

		repository.findById(account.id).get().let {
			it.oauthConnectionId shouldBe googleConnection
			it.password shouldBe null
			it.version shouldBe account.version
		}
		events.accountEvents().shouldBeEmpty()
	}

	@Test
	fun `a Microsoft account rejects a Google connection on create`() {
		shouldThrow<InvalidRequestException> {
			service.create(newGoogleAccount().copy(type = MailAccountType.MICROSOFT, oauthConnectionId = googleConnection))
		}.fieldErrors.map { it.field } shouldBe listOf("oauthConnectionId")
	}

	@Test
	fun `test connection reports IMAP and SMTP independently`() {
		val pool = mockk<ImapConnectionPool>()
		val sessions = mockk<MailSessions>()
		val tester = MailAccountService(repository, tokens, sessions, pool, changes, publisher)
		val account = service.create(newPasswordAccount())

		every { pool.withStore(any(), any<(IMAPStore) -> Unit>()) } throws MailConnectionException(MailConnectionError.TIMEOUT)
		every { sessions.checkSmtp(any()) } just runs
		tester.testConnection(account.id) shouldBe ConnectionTestResult(MailConnectionError.TIMEOUT, null)

		every { pool.withStore(any(), any<(IMAPStore) -> Unit>()) } returns Unit
		every { sessions.checkSmtp(any()) } throws MailConnectionException(MailConnectionError.AUTHENTICATION_FAILED)
		tester.testConnection(account.id) shouldBe ConnectionTestResult(null, MailConnectionError.AUTHENTICATION_FAILED)

		val response = MailAccountController(tester, mockk()).test(account.id)
		response.imap shouldBe ProtocolTestResponse(ok = true)
		response.smtp shouldBe
			ProtocolTestResponse(
				ok = false,
				errorCode = MailConnectionError.AUTHENTICATION_FAILED,
				message = MailConnectionError.AUTHENTICATION_FAILED.message,
			)
	}
}
