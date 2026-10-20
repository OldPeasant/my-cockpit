package ch.sonensei.cockpit.core.settings

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.settings.domain.CoreSettings
import ch.sonensei.cockpit.core.settings.domain.SettingDefinition
import ch.sonensei.cockpit.core.settings.domain.SettingsChangedEvent
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.core.settings.domain.UserSettings
import ch.sonensei.cockpit.core.settings.infra.UserSettingsRepository
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpHeaders.ETAG
import org.springframework.http.HttpHeaders.IF_MATCH
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import tools.jackson.databind.json.JsonMapper
import java.util.stream.Stream

/** Edge cases of the settings API and service beyond the main flows in [SettingsWebTests] and [SettingsServiceTests]. */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
@RecordApplicationEvents
class SettingsEdgeCaseTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val settings: SettingsService,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	fun noSettingsChanged() {
		jdbc.update("delete from user_settings")
	}

	private fun patch(
		body: String,
		ifMatch: String? = "\"0\"",
	): ResultActionsDsl =
		mockMvc.patch(URL) {
			with(csrf())
			if (ifMatch != null) header(IF_MATCH, ifMatch)
			contentType = MediaType.APPLICATION_JSON
			content = body
		}

	@Test
	fun `every core key can be set over the API and is returned normalized`() {
		patch(
			"""
			{
			  "weekStart": "SUNDAY",
			  "creativityLabels": {"ROUTINE": " Chores ", "MIXED": "Some", "CREATIVE": "Flow"},
			  "mailImagePolicy": "allow",
			  "mailImageAllowedSenders": [" newsletter@example.org ", "example.com"]
			}
			""".trimIndent(),
		).andExpect {
			status { isOk() }
			header { string(ETAG, "\"1\"") }
			jsonPath("$.weekStart") { value("SUNDAY") }
			jsonPath("$.creativityLabels.ROUTINE") { value("Chores") }
			jsonPath("$.creativityLabels.CREATIVE") { value("Flow") }
			jsonPath("$.mailImagePolicy") { value("allow") }
			jsonPath("$.mailImageAllowedSenders[0]") { value("newsletter@example.org") }
			jsonPath("$.mailImageAllowedSenders[1]") { value("example.com") }
		}
		settings.get(CoreSettings.MAIL_IMAGE_ALLOWED_SENDERS) shouldContainExactly listOf("newsletter@example.org", "example.com")
	}

	@Test
	fun `PATCH accepts the any-version If-Match and rejects weak or listed stale tags`() {
		patch("""{"theme": "dark"}""", ifMatch = "W/\"0\"").andExpect { status { isEqualTo(412) } }
		patch("""{"theme": "dark"}""", ifMatch = "*").andExpect {
			status { isOk() }
			header { string(ETAG, "\"1\"") }
		}
		patch("""{"theme": "light"}""", ifMatch = "\"0\", \"1\"").andExpect {
			status { isOk() }
			header { string(ETAG, "\"2\"") }
		}
	}

	@Test
	fun `precondition errors are problem details and change nothing`() {
		patch("""{"theme": "dark"}""", ifMatch = null).andExpect {
			status { isEqualTo(428) }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		patch("""{"theme": "dark"}""", ifMatch = "\"5\"").andExpect {
			status { isEqualTo(412) }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		mockMvc.get(URL).andExpect {
			header { string(ETAG, "\"0\"") }
			jsonPath("$.theme") { value("system") }
		}
	}

	@Test
	fun `an invalid body is checked before the If-Match`() {
		// Validation runs first: a client with a stale ETag still learns that its body is wrong.
		patch("""{"theme": "sepia"}""", ifMatch = "\"9\"").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("theme") }
			jsonPath("$.errors[0].message") { value("must be one of light, dark, system") }
		}
	}

	@Test
	fun `null for an unknown key is still an unknown key`() {
		patch("""{"fontSize": null}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("fontSize") }
			jsonPath("$.errors[0].message") { value("is not a known setting") }
		}
	}

	@Test
	fun `values of the wrong JSON type are rejected per key`() {
		patch(
			"""{"theme": true, "timeZone": 1, "energyLabels": "Low", "mailImageAllowedSenders": [1], "weekStart": ["MONDAY"]}""",
		).andExpect {
			status { isBadRequest() }
			jsonPath("$.errors.length()") { value(5) }
			jsonPath("$.errors[0].field") { value("energyLabels") }
			jsonPath("$.errors[1].field") { value("mailImageAllowedSenders") }
			jsonPath("$.errors[2].field") { value("theme") }
			jsonPath("$.errors[3].field") { value("timeZone") }
			jsonPath("$.errors[4].field") { value("weekStart") }
		}
		jdbc.queryForObject("select count(*) from user_settings", Long::class.java) shouldBe 0
	}

	@Test
	fun `an empty, null or malformed body is a 400`() {
		patch("").andExpect { status { isBadRequest() } }
		patch("null").andExpect { status { isBadRequest() } }
		patch("""{"theme": """).andExpect { status { isBadRequest() } }
		patch("\"dark\"").andExpect { status { isBadRequest() } }
	}

	@Test
	fun `an empty object changes nothing and keeps the ETag`() {
		patch("{}").andExpect {
			status { isOk() }
			header { string(ETAG, "\"0\"") }
			jsonPath("$.theme") { value("system") }
		}
	}

	@Test
	fun `setting a key to the value it already has keeps the ETag`() {
		patch("""{"theme": "dark"}""").andExpect { header { string(ETAG, "\"1\"") } }
		patch("""{"theme": "dark"}""", "\"1\"").andExpect {
			status { isOk() }
			header { string(ETAG, "\"1\"") }
		}
	}

	@Test
	fun `a failed or unchanged update publishes no event`(events: ApplicationEvents) {
		shouldThrow<InvalidRequestException> { settings.update(mapOf("theme" to "sepia")) }
		settings.update(mapOf("theme" to "system"))
		settings.update(mapOf("theme" to null))
		events.stream(SettingsChangedEvent::class.java).toList().shouldBeEmpty()
	}

	@Test
	fun `resetting a key publishes it as changed`(events: ApplicationEvents) {
		settings.update(mapOf("mailImagePolicy" to "allow"))
		settings.update(mapOf("mailImagePolicy" to null))
		events.stream(SettingsChangedEvent::class.java).toList() shouldContainExactly
			listOf(SettingsChangedEvent(setOf("mailImagePolicy")), SettingsChangedEvent(setOf("mailImagePolicy")))
		settings.get(CoreSettings.MAIL_IMAGE_POLICY) shouldBe "block"
	}

	@Test
	fun `resetting a stored value its definition no longer accepts removes it`(events: ApplicationEvents) {
		settings.update(mapOf("theme" to "dark"))
		jdbc.update("update user_settings set overrides = '{\"theme\": \"sepia\"}'::jsonb")
		settings.update(mapOf("theme" to null))
		jdbc.queryForObject("select overrides::text from user_settings", String::class.java) shouldBe "{}"
		events.stream(SettingsChangedEvent::class.java).count() shouldBe 1
	}

	@Test
	fun `stored keys of settings that are no longer registered survive other changes`() {
		settings.update(mapOf("theme" to "dark"))
		jdbc.update("update user_settings set overrides = overrides || '{\"retiredModuleKey\": true}'::jsonb")
		settings.update(mapOf("weekStart" to "SATURDAY"))
		jdbc.queryForObject("select overrides ->> 'retiredModuleKey' from user_settings", String::class.java) shouldBe "true"
		settings.current().values.containsKey("retiredModuleKey") shouldBe false
	}

	@Test
	fun `there is at most one settings row`() {
		settings.update(mapOf("theme" to "dark"))
		shouldThrow<DataIntegrityViolationException> {
			jdbc.update(
				"insert into user_settings (id, created_at, updated_at, version, overrides) " +
					"values ('01890000-0000-7000-8000-000000000001', now(), now(), 0, '{}'::jsonb)",
			)
		}
		jdbc.queryForObject("select count(*) from user_settings", Long::class.java) shouldBe 1
	}

	@Test
	fun `a concurrently created settings row is a conflict`() {
		val repository =
			mockk<UserSettingsRepository> {
				every { findFirstBy() } returns null
				every { saveAndFlush(any<UserSettings>()) } throws DataIntegrityViolationException("user_settings_single_row")
			}
		val definitions =
			mockk<ObjectProvider<SettingDefinition<*>>> {
				every { orderedStream() } returns Stream.empty()
			}
		val service = SettingsService(repository, JsonMapper(), mockk<ApplicationEventPublisher>(), definitions)
		shouldThrow<ConflictException> { service.update(mapOf("theme" to "dark")) }
	}

	@Test
	fun `a concurrent change of the existing settings is a conflict and changes nothing`() {
		settings.update(mapOf("theme" to "dark"))
		shouldThrow<OptimisticLockingFailureException> {
			settings.update(mapOf("theme" to "light")) {
				// Another client changes the row between the If-Match check and the save.
				jdbc.update("update user_settings set version = version + 1")
			}
		}
		settings.current().version shouldBe 1
		settings.get(CoreSettings.THEME) shouldBe "dark"
	}

	@Test
	fun `the stored overrides must be a JSON object`() {
		settings.update(mapOf("theme" to "dark"))
		for (document in listOf("[]", "null", "\"dark\"")) {
			shouldThrow<DataIntegrityViolationException> {
				jdbc.update("update user_settings set overrides = ?::jsonb", document)
			}
		}
		settings.get(CoreSettings.THEME) shouldBe "dark"
	}

	@Test
	fun `the documented patch allows null for the enum settings`() {
		mockMvc.get("/v3/api-docs").andExpect {
			status { isOk() }
			for (key in listOf("theme", "weekStart", "mailImagePolicy")) {
				jsonPath("$.components.schemas.SettingsPatch.properties.$key.enum[-1]") { value(null) }
			}
		}
	}

	private companion object {
		const val URL = "/api/v1/settings"
	}
}
