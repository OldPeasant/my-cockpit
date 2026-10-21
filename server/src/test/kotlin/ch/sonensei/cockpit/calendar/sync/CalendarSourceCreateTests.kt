package ch.sonensei.cockpit.calendar.sync

import ch.sonensei.cockpit.calendar.api.CalendarApi
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.id
import ch.sonensei.cockpit.calendar.api.CalendarApi.Companion.isProblem
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnection
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.infra.OAuthConnectionRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** `POST /api/v1/calendar/sources`: adding CalDAV, ICS and Google/Microsoft sources. */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class CalendarSourceCreateTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val sources: CalendarSourceRepository,
	@Autowired private val connections: OAuthConnectionRepository,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private val api = CalendarApi(mockMvc, jsonMapper)
	private lateinit var google: UUID
	private lateinit var microsoft: UUID

	@BeforeEach
	fun reset() {
		CalendarApi.resetCalendarTables(jdbc)
		jdbc.update("delete from mail_account")
		jdbc.update("delete from oauth_connection")
		google = connections.save(OAuthConnection(OAuthProvider.GOOGLE, "subject-g", "jane@gmail.example", "Jane", "openid", null)).id
		microsoft = connections.save(OAuthConnection(OAuthProvider.MICROSOFT, "subject-m", null, "Jane Doe", "openid", null)).id
	}

	private fun post(body: String) = api.postJson(SOURCES, body)

	@Test
	fun `a CalDAV source stores URL and user in its config and the password encrypted, and is listed without them`() {
		val created =
			api.read(
				post("""{"type":"CALDAV","url":" https://dav.example.test/remote.php/dav ","username":"jane","password":"fictitious-pw"}""")
					.andExpect { status { isCreated() } },
			)

		created.get("type").asString() shouldBe "CALDAV"
		created.get("name").asString() shouldBe "dav.example.test"
		created.get("syncable").asBoolean() shouldBe true
		created.get("sync").get("state").asString() shouldBe "NEVER"
		val source = sources.findById(UUID.fromString(created.id())).get()
		jsonMapper.readTree(source.config).get("url").asString() shouldBe "https://dav.example.test/remote.php/dav"
		jsonMapper.readTree(source.config).get("username").asString() shouldBe "jane"
		source.credentials!!.reveal() shouldBe "fictitious-pw"
		jdbc.queryForObject("select credentials from calendar_source where id = ?", String::class.java, source.id) shouldNotContain
			"fictitious-pw"
		val listed =
			mockMvc
				.get(SOURCES)
				.andReturn()
				.response.contentAsString
		listed shouldNotContain "fictitious-pw"
		listed shouldNotContain "remote.php"
	}

	@Test
	fun `a CalDAV source needs an http URL, a user and a password`() {
		post("""{"type":"CALDAV","url":"ftp://dav.example.test"}""").isProblem(400, "url", "username", "password")
		post("""{"type":"CALDAV","url":"https://jane:pw@dav.example.test","username":"jane","password":"x"}""").isProblem(400, "url")
		sources.count() shouldBe 0
	}

	@Test
	fun `an ICS feed takes a webcal URL and an own name`() {
		val created =
			api.read(
				post("""{"type":"ICS_FEED","name":"Holidays","url":"webcal://feeds.example.test/holidays.ics?token=abc"}""")
					.andExpect { status { isCreated() } },
			)

		created.get("name").asString() shouldBe "Holidays"
		val source = sources.findById(UUID.fromString(created.id())).get()
		jsonMapper.readTree(source.config).get("url").asString() shouldBe "webcal://feeds.example.test/holidays.ics?token=abc"
		source.credentials shouldBe null
		post("""{"type":"ICS_FEED","url":"https://feeds.example.test/a.ics","password":"x"}""").isProblem(400, "password")
		post("""{"type":"ICS_FEED"}""").isProblem(400, "url")
	}

	@Test
	fun `a Google source links a Google connection once, named by its email`() {
		val created = api.read(post("""{"type":"GOOGLE","oauthConnectionId":"$google"}""").andExpect { status { isCreated() } })

		created.get("name").asString() shouldBe "jane@gmail.example"
		jsonMapper.readTree(sources.findById(UUID.fromString(created.id())).get().config).get("connectionId").asString() shouldBe
			google.toString()
		val again = api.read(post("""{"type":"GOOGLE","oauthConnectionId":"$google"}""").andExpect { status { isOk() } })
		again.id() shouldBe created.id()
		sources.count() shouldBe 1
	}

	@Test
	fun `a Microsoft source without email is named by the account, and needs a Microsoft connection`() {
		api
			.read(post("""{"type":"MICROSOFT","oauthConnectionId":"$microsoft"}""").andExpect { status { isCreated() } })
			.get("name")
			.asString() shouldBe "Jane Doe"

		post("""{"type":"MICROSOFT","oauthConnectionId":"$google"}""").isProblem(400, "oauthConnectionId")
		post("""{"type":"GOOGLE","oauthConnectionId":"${UUID.randomUUID()}"}""").isProblem(400, "oauthConnectionId")
		post("""{"type":"GOOGLE"}""").isProblem(400, "oauthConnectionId")
	}

	@Test
	fun `LOCAL and missing types are refused`() {
		post("""{"type":"LOCAL","name":"Mine"}""").isProblem(400, "type")
		post("""{"name":"Mine"}""").isProblem(400, "type")
		sources.findAll().none { it.type == CalendarSourceType.LOCAL } shouldBe true
	}

	companion object {
		private const val SOURCES = "/api/v1/calendar/sources"
	}
}
