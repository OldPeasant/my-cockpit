package ch.sonensei.cockpit.core.auth

import ch.sonensei.cockpit.support.PostgresTestBase
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.head
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Which requests need a login: the API by default; health, setup, login/logout and the SPA stay public.
 * Runs with a fake web build, so the SPA fallback is really served.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigurationTests(
	@Autowired private val mockMvc: MockMvc,
) : PostgresTestBase() {
	companion object {
		private const val INDEX_HTML = "<!doctype html><title>Cockpit fixture</title><app-root></app-root>"

		private val webBuild: Path =
			Files.createTempDirectory("cockpit-security-spa").also {
				Files.writeString(it.resolve("index.html"), INDEX_HTML)
				Files.writeString(it.resolve("main-ABC123.js"), "console.log('fixture')")
			}

		@JvmStatic
		@DynamicPropertySource
		fun spaLocation(registry: DynamicPropertyRegistry) {
			registry.add("cockpit.spa.location") { webBuild.toUri().toString() }
		}
	}

	@Test
	fun `setup status and health are public`() {
		mockMvc.get("/api/v1/setup/status").andExpect { status { isOk() } }
		mockMvc.get("/actuator/health").andExpect { status { isOk() } }
	}

	@Test
	fun `the SPA, its files and the index fallback for client routes are public`() {
		mockMvc.get("/").andExpect {
			status { isOk() }
			forwardedUrl("/index.html")
		}
		mockMvc.get("/index.html").andExpect {
			status { isOk() }
			content { string(INDEX_HTML) }
		}
		mockMvc.get("/main-ABC123.js").andExpect { status { isOk() } }
		mockMvc.get("/inbox").andExpect {
			status { isOk() }
			content { string(INDEX_HTML) }
		}
		mockMvc.head("/settings/devices").andExpect { status { isOk() } }
		mockMvc.get("/missing-XYZ.js").andExpect { status { isNotFound() } }
	}

	@Test
	fun `API endpoints, actuator and API docs answer 401 without authentication`() {
		for (path in listOf("/api/v1/anything", "/api/v1/auth/me", "/api", "/actuator/info", "/actuator", "/v3/api-docs", "/v3/api-docs.yaml")) {
			mockMvc.get(path).andExpect { status { isUnauthorized() } }
		}
		mockMvc.post("/api/v1/anything") { with(csrf()) }.andExpect { status { isUnauthorized() } }
		mockMvc.put("/api/v1/anything") { with(csrf()) }.andExpect { status { isUnauthorized() } }
		mockMvc.delete("/api/v1/anything") { with(csrf()) }.andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `state-changing requests outside the API need a login too`() {
		mockMvc.post("/inbox") { with(csrf()) }.andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `state-changing requests outside setup require a CSRF token`() {
		mockMvc.post("/api/v1/anything").andExpect { status { isForbidden() } }
	}

	@Test
	fun `the server creates no servlet session`() {
		mockMvc.get("/api/v1/auth/me").andExpect { request { sessionAttributeDoesNotExist("SPRING_SECURITY_CONTEXT") } }
		mockMvc.get("/inbox").andExpect { cookie { doesNotExist("JSESSIONID") } }
	}

	@Nested
	@WithCockpitUser
	inner class AsCockpitUser {
		@Test
		fun `WithCockpitUser authenticates API requests as the owner`() {
			mockMvc.get("/api/v1/auth/me").andExpect {
				status { isOk() }
				jsonPath("$.username") { value(TestCockpitUser.USERNAME) }
			}
			// Authenticated, so an unknown path is a 404, not a 401.
			mockMvc.get("/api/v1/anything").andExpect { status { isNotFound() } }
		}

		@Test
		@WithCockpitUser(username = "john.roe")
		fun `WithCockpitUser takes another username`() {
			mockMvc.get("/api/v1/auth/me").andExpect { jsonPath("$.username") { value("john.roe") } }
		}
	}

	@Test
	fun `cockpitUser authenticates a single request`() {
		mockMvc.get("/api/v1/auth/me") { with(cockpitUser()) }.andExpect {
			status { isOk() }
			jsonPath("$.username") { value(TestCockpitUser.USERNAME) }
		}
		mockMvc.get("/api/v1/auth/me").andExpect { status { isUnauthorized() } }
	}
}
