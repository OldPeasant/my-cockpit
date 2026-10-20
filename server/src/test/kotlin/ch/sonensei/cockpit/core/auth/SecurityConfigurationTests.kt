package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** The setup flow is public; everything else outside the health endpoint stays protected. */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigurationTests(
	@Autowired private val mockMvc: MockMvc,
) : PostgresTestBase() {
	@Test
	fun `setup status and health are public`() {
		mockMvc.get("/api/v1/setup/status").andExpect { status { isOk() } }
		mockMvc.get("/actuator/health").andExpect { status { isOk() } }
	}

	@Test
	fun `other API endpoints answer 401 without authentication`() {
		mockMvc.get("/api/v1/anything").andExpect { status { isUnauthorized() } }
		mockMvc.get("/v3/api-docs").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `state-changing requests outside setup still require a CSRF token`() {
		mockMvc.post("/api/v1/anything").andExpect { status { isForbidden() } }
	}
}
