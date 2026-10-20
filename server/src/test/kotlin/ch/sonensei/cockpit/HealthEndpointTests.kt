package ch.sonensei.cockpit

import ch.sonensei.cockpit.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointTests(
	@Autowired private val mockMvc: MockMvc,
) : PostgresTestBase() {
	@Test
	fun `health endpoint reports UP`() {
		mockMvc.get("/actuator/health").andExpect {
			status { isOk() }
			jsonPath("$.status") { value("UP") }
		}
	}
}
