package ch.sonensei.cockpit.core.oauth

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.sync.CalendarSyncService
import ch.sonensei.cockpit.calendar.sync.SourceSyncStatusRepository
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.oauth.domain.MicrosoftAvailability
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnection
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.infra.OAuthConnectionRepository
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailSecurity
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID

/**
 * Microsoft switched off (`cockpit.microsoft.enabled=false`, the production default; the test configuration turns it
 * on for the Microsoft tests): the API neither offers nor accepts Microsoft, and Microsoft sources don't sync.
 * [DirtiesContext] closes this class's own context (and its connection pool) afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@TestPropertySource(properties = ["cockpit.microsoft.enabled=false"])
@DirtiesContext
class MicrosoftDisabledWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val connections: OAuthConnectionRepository,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val statuses: SourceSyncStatusRepository,
	@Autowired private val accounts: MailAccountRepository,
	@Autowired private val sync: CalendarSyncService,
	@Autowired private val microsoft: MicrosoftAvailability,
) : PostgresTestBase() {
	private lateinit var microsoftConnection: UUID

	@BeforeEach
	fun reset() {
		CalendarApi.resetCalendarTables(jdbc)
		jdbc.update("delete from mail_account")
		jdbc.update("delete from oauth_authorization")
		jdbc.update("delete from oauth_connection")
		microsoftConnection =
			connections.save(OAuthConnection(OAuthProvider.MICROSOFT, "subject-m", "jane@outlook.example", "Jane", "openid", null)).id
	}

	@Test
	fun `the setting is read as off`() {
		microsoft.enabled.shouldBeFalse()
	}

	@Test
	fun `providers leaves Microsoft out`() {
		mockMvc.get("/api/v1/oauth/providers").andExpect {
			status { isOk() }
			jsonPath("$.length()") { value(1) }
			jsonPath("$[0].provider") { value("GOOGLE") }
		}
	}

	@Test
	fun `starting a Microsoft connection is refused, Google still starts`() {
		mockMvc.get("/api/v1/oauth/microsoft/start") { param("purpose", "MAIL") }.isNotEnabled("provider")
		jdbc.queryForObject("select count(*) from oauth_authorization", Int::class.java) shouldBe 0
		// Google passes the Microsoft check (and fails later for the missing browser session).
		mockMvc.get("/api/v1/oauth/google/start") { param("purpose", "MAIL") }.andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `a Microsoft calendar source is refused`() {
		postJson("/api/v1/calendar/sources", """{"type":"MICROSOFT","oauthConnectionId":"$microsoftConnection"}""").isNotEnabled("type")
		sources.findAll().none { it.type == CalendarSourceType.MICROSOFT } shouldBe true
	}

	@Test
	fun `a Microsoft mail account is refused`() {
		postJson(
			"/api/v1/mail/accounts",
			"""{"type":"MICROSOFT","emailAddress":"jane@outlook.example","oauthConnectionId":"$microsoftConnection"}""",
		).isNotEnabled("type")
		accounts.count() shouldBe 0
	}

	@Test
	fun `an existing Microsoft source is neither synced by the scheduler nor on demand`() {
		val source =
			sources.saveAndFlush(
				CalendarSource(CalendarSourceType.MICROSOFT, "Outlook").apply { config = """{"connectionId":"$microsoftConnection"}""" },
			)

		sync.syncDue() shouldBe 0
		statuses.findById(source.id).isPresent.shouldBeFalse()
		postJson("/api/v1/calendar/sources/${source.id}/sync", "{}").isNotEnabled("type")
		statuses.findById(source.id).isPresent.shouldBeFalse()
	}

	@Test
	fun `other sources still sync on schedule while Microsoft sources are skipped`() {
		val microsoftSource =
			sources.saveAndFlush(
				CalendarSource(CalendarSourceType.MICROSOFT, "Outlook").apply { config = """{"connectionId":"$microsoftConnection"}""" },
			)
		// A loopback feed is refused before any network access, but the attempt is recorded like any sync run.
		val feed =
			sources.saveAndFlush(
				CalendarSource(CalendarSourceType.ICS_FEED, "Team holidays").apply { config = """{"url":"http://127.0.0.1:9/cal.ics"}""" },
			)

		sync.syncDue() shouldBe 1

		statuses.findById(feed.id).isPresent shouldBe true
		statuses.findById(microsoftSource.id).isPresent.shouldBeFalse()
	}

	@Test
	fun `Google calendar sources and mail accounts are still accepted`() {
		val google = connections.save(OAuthConnection(OAuthProvider.GOOGLE, "subject-g", "jane@gmail.example", "Jane", "openid", null)).id

		postJson("/api/v1/calendar/sources", """{"type":"GOOGLE","oauthConnectionId":"$google"}""")
			.andExpect { status { isCreated() } }
		postJson(
			"/api/v1/mail/accounts",
			"""{"type":"GOOGLE","emailAddress":"jane@gmail.example","oauthConnectionId":"$google"}""",
		).andExpect { status { isCreated() } }

		sources.findAll().count { it.type == CalendarSourceType.GOOGLE } shouldBe 1
		accounts.count() shouldBe 1
	}

	@Test
	fun `an existing Microsoft mail account can still be changed`() {
		// Deliberately not gated (task assumption): existing Microsoft data is kept and stays editable.
		val account =
			accounts.saveAndFlush(
				MailAccount(
					MailAccountType.MICROSOFT,
					"Outlook",
					"jane@outlook.example",
					"outlook.office365.com",
					993,
					MailSecurity.SSL_TLS,
					"smtp.office365.com",
					587,
					MailSecurity.STARTTLS,
					"jane@outlook.example",
				).apply { oauthConnectionId = microsoftConnection },
			)

		mockMvc
			.patch("/api/v1/mail/accounts/${account.id}") {
				with(csrf())
				contentType = MediaType.APPLICATION_JSON
				content = """{"displayName":"Outlook (old)","oauthConnectionId":"$microsoftConnection"}"""
				header(HttpHeaders.IF_MATCH, "\"${account.version}\"")
			}.andExpect {
				status { isOk() }
				jsonPath("$.displayName") { value("Outlook (old)") }
				jsonPath("$.type") { value("MICROSOFT") }
			}
	}

	private fun postJson(
		path: String,
		body: String,
	) = mockMvc.post(path) {
		with(csrf())
		contentType = MediaType.APPLICATION_JSON
		content = body
	}

	private fun ResultActionsDsl.isNotEnabled(field: String) =
		andExpect {
			status { isBadRequest() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.detail") { value(MicrosoftAvailability.NOT_ENABLED) }
			jsonPath("$.errors[0].field") { value(field) }
		}
}
