package ch.sonensei.cockpit.core.spa

import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.file.Files
import java.nio.file.Path

@SpringBootTest
@AutoConfigureMockMvc
// Until p0-auth-login makes the SPA public, Boot's default security answers anonymous requests with 401.
@WithMockUser
class SpaFallbackTests(
	@Autowired private val mockMvc: MockMvc,
) : PostgresTestBase() {
	companion object {
		private const val INDEX_HTML = "<!doctype html><title>Cockpit fixture</title><app-root></app-root>"

		// A fake web build in a temp dir, so the test does not depend on `ng build` output.
		private val webBuild: Path =
			Files.createTempDirectory("cockpit-spa").also {
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
	fun `root serves index html`() {
		// MockMvc records the forward instead of following it.
		mockMvc.get("/").andExpect {
			status { isOk() }
			forwardedUrl("/index.html")
		}
		mockMvc.get("/index.html").andExpect {
			status { isOk() }
			content { string(INDEX_HTML) }
		}
	}

	@Test
	fun `client route falls back to index html`() {
		for (path in listOf("/inbox", "/projects/0192a4c0-0000-7000-8000-000000000001", "/settings/devices/")) {
			mockMvc.get(path).andExpect {
				status { isOk() }
				content { string(INDEX_HTML) }
			}
		}
	}

	@Test
	fun `existing static file is served as is`() {
		mockMvc.get("/main-ABC123.js").andExpect {
			status { isOk() }
			content { string("console.log('fixture')") }
			header { string("Cache-Control", "no-cache") }
		}
	}

	@Test
	fun `missing file with extension is 404, not index html`() {
		mockMvc.get("/main-GONE.js").andExpect { status { isNotFound() } }
	}

	@Test
	fun `unknown api path is 404, not index html`() {
		mockMvc.get("/api/v1/does-not-exist").andExpect { status { isNotFound() } }
		mockMvc.get("/api").andExpect { status { isNotFound() } }
	}

	@Test
	fun `actuator is not shadowed by the fallback`() {
		mockMvc.get("/actuator/health").andExpect {
			status { isOk() }
			jsonPath("$.status") { value("UP") }
		}
		mockMvc.get("/actuator/nope").andExpect { status { isNotFound() } }
	}

	@Test
	fun `client route detection`() {
		SpaFallbackResourceResolver.isClientRoute("") shouldBe true
		SpaFallbackResourceResolver.isClientRoute("inbox") shouldBe true
		SpaFallbackResourceResolver.isClientRoute("notes/a.b/edit") shouldBe true
		SpaFallbackResourceResolver.isClientRoute("apis") shouldBe true
		SpaFallbackResourceResolver.isClientRoute("favicon.ico") shouldBe false
		SpaFallbackResourceResolver.isClientRoute("api") shouldBe false
		SpaFallbackResourceResolver.isClientRoute("api/v1/tasks") shouldBe false
		SpaFallbackResourceResolver.isClientRoute("actuator/health") shouldBe false
	}
}
