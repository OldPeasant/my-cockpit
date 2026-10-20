package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.support.PostgresTestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders.IF_MATCH
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch

/** The GTD settings (`GtdSettings`) through `/api/v1/settings`. */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class GtdSettingsWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@BeforeEach
	fun noSettingsChanged() {
		jdbc.update("delete from user_settings")
	}

	private fun patch(body: String): ResultActionsDsl =
		mockMvc.patch(URL) {
			with(csrf())
			header(IF_MATCH, "\"0\"")
			contentType = MediaType.APPLICATION_JSON
			content = body
		}

	@Test
	fun `next action presets are empty by default and keep what the client saved`() {
		mockMvc.get(URL).andExpect {
			status { isOk() }
			jsonPath("$.nextActionPresets") { isEmpty() }
		}
		val preset = """{\"name\":\"Calls\",\"filter\":{\"context\":\"0192f0c4-0000-7000-8000-000000000001\",\"maxMinutes\":15}}"""
		patch("""{"nextActionPresets": ["$preset"]}""").andExpect {
			status { isOk() }
			jsonPath("$.nextActionPresets.length()") { value(1) }
			jsonPath("$.nextActionPresets[0]") { value(preset.replace("\\\"", "\"")) }
		}
	}

	@Test
	fun `next action presets must be a list of strings`() {
		patch("""{"nextActionPresets": [{"name": "Calls"}]}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("nextActionPresets") }
		}
	}

	@Test
	fun `next action presets accept 50 entries and refuse a 51st`() {
		val presets = (1..51).map { "\"{\\\"name\\\":\\\"Preset $it\\\",\\\"filter\\\":{}}\"" }
		patch("""{"nextActionPresets": [${presets.joinToString(",")}]}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("nextActionPresets") }
		}
		patch("""{"nextActionPresets": [${presets.take(50).joinToString(",")}]}""").andExpect {
			status { isOk() }
			jsonPath("$.nextActionPresets.length()") { value(50) }
		}
	}

	@Test
	fun `a preset longer than 2000 characters is refused`() {
		val name = "x".repeat(2001)
		patch("""{"nextActionPresets": ["$name"]}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("nextActionPresets") }
		}
	}

	private companion object {
		const val URL = "/api/v1/settings"
	}
}
