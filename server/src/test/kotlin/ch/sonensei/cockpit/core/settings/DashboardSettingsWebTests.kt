package ch.sonensei.cockpit.core.settings

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

/** The dashboard settings (`DashboardSettings`) through `/api/v1/settings`. */
@SpringBootTest
@AutoConfigureMockMvc
@WithCockpitUser
class DashboardSettingsWebTests(
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
	fun `dashboard widgets are empty by default and keep the saved order`() {
		mockMvc.get(URL).andExpect {
			status { isOk() }
			jsonPath("$.dashboardWidgets") { isEmpty() }
		}
		patch("""{"dashboardWidgets": ["inbox", "today:hidden", "pinnedNotes"]}""").andExpect {
			status { isOk() }
			jsonPath("$.dashboardWidgets[0]") { value("inbox") }
			jsonPath("$.dashboardWidgets[1]") { value("today:hidden") }
			jsonPath("$.dashboardWidgets[2]") { value("pinnedNotes") }
		}
	}

	@Test
	fun `dashboard widgets must be distinct strings`() {
		patch("""{"dashboardWidgets": ["inbox", "inbox"]}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("dashboardWidgets") }
		}
		patch("""{"dashboardWidgets": [{"id": "inbox"}]}""").andExpect {
			status { isBadRequest() }
			jsonPath("$.errors[0].field") { value("dashboardWidgets") }
		}
	}

	private companion object {
		const val URL = "/api/v1/settings"
	}
}
