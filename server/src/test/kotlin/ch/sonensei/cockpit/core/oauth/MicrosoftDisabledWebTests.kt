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
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
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
