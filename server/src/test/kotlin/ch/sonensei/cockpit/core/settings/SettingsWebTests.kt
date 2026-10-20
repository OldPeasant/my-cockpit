package ch.sonensei.cockpit.core.settings

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.support.PostgresTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders.ETAG
import org.springframework.http.HttpHeaders.IF_MATCH
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch

@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class SettingsWebTests(
	@Autowired private val mockMvc: MockMvc,
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
	fun `GET returns every setting with its default and an ETag`() {
		mockMvc.get(URL).andExpect {
			status { isOk() }
			header { string(ETAG, "\"0\"") }
			jsonPath("$.timeZone") { value("Europe/Zurich") }
			jsonPath("$.weekStart") { value("MONDAY") }
			jsonPath("$.theme") { value("system") }
			jsonPath("$.energyLabels.LOW") { value("Low") }
			jsonPath("$.energyLabels.HIGH") { value("High") }
			jsonPath("$.creativityLabels.CREATIVE") { value("Creative") }
			jsonPath("$.mailImagePolicy") { value("block") }
			jsonPath("$.mailImageAllowedSenders") { isEmpty() }
		}
	}

	@Test
	fun `PATCH changes the given keys, keeps the others and returns a new ETag`() {
		patch("""{"theme": "dark", "timeZone": "America/New_York"}""").andExpect {
			status { isOk() }
			header { string(ETAG, "\"1\"") }
			jsonPath("$.theme") { value("dark") }
			jsonPath("$.timeZone") { value("America/New_York") }
			jsonPath("$.weekStart") { value("MONDAY") }
		}
		mockMvc.get(URL).andExpect {
			header { string(ETAG, "\"1\"") }
			jsonPath("$.theme") { value("dark") }
		}
		patch("""{"theme": null, "energyLabels": {"LOW": "Tired", "MEDIUM": "Okay", "HIGH": "Fresh"}}""", "\"1\"").andExpect {
			status { isOk() }
			header { string(ETAG, "\"2\"") }
			jsonPath("$.theme") { value("system") }
			jsonPath("$.timeZone") { value("America/New_York") }
			jsonPath("$.energyLabels.LOW") { value("Tired") }
		}
	}

	@Test
	fun `an unknown key or an invalid value is a 400 per key and changes nothing`() {
		patch("""{"theme": "dark", "fontSize": 12, "weekStart": "monday", "timeZone": "Mars/Olympus"}""").andExpect {
			status { isBadRequest() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.errors.length()") { value(3) }
			jsonPath("$.errors[0].field") { value("fontSize") }
			jsonPath("$.errors[0].message") { value("is not a known setting") }
			jsonPath("$.errors[1].field") { value("timeZone") }
			jsonPath("$.errors[2].field") { value("weekStart") }
			jsonPath("$.errors[2].message") { value("must be one of MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY") }
		}
		patch("""{"mailImageAllowedSenders": "example.org"}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("mailImageAllowedSenders") }
		}
		mockMvc.get(URL).andExpect {
			header { string(ETAG, "\"0\"") }
			jsonPath("$.theme") { value("system") }
		}
	}

	@Test
	fun `a body that is not a JSON object is a 400`() {
		patch("""["theme"]""").andExpect { status { isBadRequest() } }
	}

	@Test
	fun `PATCH needs a current If-Match`() {
		patch("""{"theme": "dark"}""", ifMatch = null).andExpect { status { isEqualTo(428) } }
		patch("""{"theme": "dark"}""", ifMatch = "\"7\"").andExpect { status { isEqualTo(412) } }
		patch("""{"theme": "dark"}""").andExpect { status { isOk() } }
		patch("""{"theme": "light"}""").andExpect { status { isEqualTo(412) } }
	}

	@Test
	@WithAnonymousUser
	fun `settings need a login`() {
		mockMvc.get(URL).andExpect { status { isUnauthorized() } }
		patch("""{"theme": "dark"}""").andExpect { status { isUnauthorized() } }
	}

	private companion object {
		const val URL = "/api/v1/settings"
	}
}
