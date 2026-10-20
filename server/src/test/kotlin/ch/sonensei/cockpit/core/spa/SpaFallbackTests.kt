package ch.sonensei.cockpit.core.spa

import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.head
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import org.springframework.web.servlet.resource.CachingResourceResolver
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler
import java.nio.file.Files
import java.nio.file.Path

@SpringBootTest
@AutoConfigureMockMvc
// Until p0-auth-login makes the SPA public, Boot's default security answers anonymous requests with 401.
@WithMockUser
@Import(SpaFallbackTests.ProbeApi::class)
class SpaFallbackTests(
	@Autowired private val mockMvc: MockMvc,
) : PostgresTestBase() {
	companion object {
		private const val INDEX_HTML = "<!doctype html><title>Cockpit fixture</title><app-root></app-root>"
		private const val LOGO_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>"

		private const val SECRET = "fictitious-secret-outside-the-web-build"

		// A fake web build in a temp dir, so the test does not depend on `ng build` output. A file next to
		// it (outside the served location) checks that path traversal cannot reach it.
		private val webRoot: Path =
			Files.createTempDirectory("cockpit-spa").also {
				Files.writeString(it.resolve("secret.txt"), SECRET)
			}
		private val webBuild: Path =
			Files.createDirectory(webRoot.resolve("web")).also {
				Files.writeString(it.resolve("index.html"), INDEX_HTML)
				Files.writeString(it.resolve("main-ABC123.js"), "console.log('fixture')")
				Files.createDirectories(it.resolve("media"))
				Files.writeString(it.resolve("media/logo-XYZ.svg"), LOGO_SVG)
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

	@Test
	fun `fallback index html is served as no-cache html`() {
		// A cached index.html would keep pointing at hashed bundles that a later deploy deleted.
		mockMvc.get("/inbox").andExpect {
			status { isOk() }
			content { contentTypeCompatibleWith("text/html") }
			header { string("Cache-Control", "no-cache") }
		}
	}

	@Test
	fun `file in a subdirectory is served and a missing one is 404`() {
		mockMvc.get("/media/logo-XYZ.svg").andExpect {
			status { isOk() }
			content { string(LOGO_SVG) }
		}
		mockMvc.get("/media/logo-GONE.svg").andExpect { status { isNotFound() } }
	}

	@Test
	fun `head on a client route answers like get`() {
		mockMvc.head("/inbox").andExpect { status { isOk() } }
	}

	@Test
	fun `existing api endpoint is not shadowed by the catch-all handler`() {
		mockMvc.get("/api/v1/spa-probe").andExpect {
			status { isOk() }
			jsonPath("$.ok") { value(true) }
		}
	}

	@Test
	fun `path traversal cannot read files outside the web build`() {
		for (path in listOf("/../secret.txt", "/media/../../secret.txt", "/%2e%2e/secret.txt", "/..%2fsecret.txt")) {
			val response = mockMvc.get(path).andReturn().response
			withClue(path) {
				response.status shouldNotBe 200
				response.contentAsString shouldNotContain SECRET
			}
		}
	}

	@Test
	fun `api root with trailing slash is 404, not index html`() {
		mockMvc.get("/api/").andExpect { status { isNotFound() } }
	}

	@Test
	fun `resource chain keeps no unbounded resolver cache`(
		@Autowired @Qualifier("resourceHandlerMapping") mapping: HandlerMapping,
	) {
		// A CachingResourceResolver would keep one entry per distinct client-route URL, forever.
		val handler = (mapping as SimpleUrlHandlerMapping).handlerMap["/**"] as ResourceHttpRequestHandler
		handler.resourceResolvers.any { it is CachingResourceResolver } shouldBe false
		handler.resourceResolvers.any { it is SpaFallbackResourceResolver } shouldBe true
	}

	// A test-only API endpoint: controllers must still win over the catch-all resource handler.
	@TestConfiguration
	class ProbeApi {
		@RestController
		class Probe {
			@GetMapping("/api/v1/spa-probe")
			fun probe() = mapOf("ok" to true)
		}
	}
}
