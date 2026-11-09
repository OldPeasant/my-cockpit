package ch.sonensei.cockpit.core.spa

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
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
import java.nio.file.attribute.FileTime
import java.time.Instant

@SpringBootTest
@AutoConfigureMockMvc
// Anonymous on purpose: the SPA is public (SecurityConfiguration), only the API needs a login.
@Import(SpaFallbackTests.ProbeApi::class)
class SpaFallbackTests(
	@Autowired private val mockMvc: MockMvc,
) : PostgresTestBase() {
	companion object {
		private const val INDEX_HTML = "<!doctype html><title>Cockpit fixture</title><app-root></app-root>"
		private const val LOGO_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>"

		private val JAR_TIME: FileTime = FileTime.from(Instant.parse("1980-02-01T00:00:00Z"))
		private const val JAR_DATE = "Fri, 01 Feb 1980 00:00:00 GMT"

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
				// Like the reproducible boot jar: every entry has the same 1980 timestamp.
				Files.walk(it).use { files -> files.forEach { f -> Files.setLastModifiedTime(f, JAR_TIME) } }
			}

		/** Runs also when a test failed, so no run leaves its fake web build behind. */
		@JvmStatic
		@AfterAll
		fun deleteWebRoot() {
			webRoot.toFile().deleteRecursively()
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
	// Logged in, so the API answers with its own status instead of 401.
	@WithCockpitUser
	fun `unknown api path is 404, not index html`() {
		mockMvc.get("/api/v1/does-not-exist").andExpect { status { isNotFound() } }
		mockMvc.get("/api").andExpect { status { isNotFound() } }
	}

	@Test
	// Logged in, so the API answers with its own status instead of 401.
	@WithCockpitUser
	fun `actuator is not shadowed by the fallback`() {
		mockMvc.get("/actuator/health").andExpect {
			status { isOk() }
			jsonPath("$.status") { value("UP") }
		}
		mockMvc.get("/actuator/nope").andExpect { status { isNotFound() } }
	}

	@Test
	// Logged in, so the request gets past security; anonymous it would be a 401 anyway.
	@WithCockpitUser
	fun `unknown download path is 404, not index html`() {
		for (path in listOf("/download/ios", "/download/", "/download")) {
			withClue(path) {
				mockMvc.get(path).andExpect { status { isNotFound() } }
			}
		}
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
		SpaFallbackResourceResolver.isClientRoute("download/ios") shouldBe false
		SpaFallbackResourceResolver.isClientRoute("downloads") shouldBe true
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
	// Logged in, so the API answers with its own status instead of 401.
	@WithCockpitUser
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
	// Logged in, so the API answers with its own status instead of 401.
	@WithCockpitUser
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

	private fun etagOf(path: String): String =
		mockMvc
			.get(path)
			.andReturn()
			.response
			.getHeader("ETag")
			.shouldNotBeNull()

	/** Replaces index.html with same-size content and the same 1980 timestamp, runs [block], restores it. */
	private fun withDeployedIndex(
		html: String,
		block: () -> Unit,
	) {
		val index = webBuild.resolve("index.html")
		try {
			Files.writeString(index, html)
			Files.setLastModifiedTime(index, JAR_TIME)
			block()
		} finally {
			Files.writeString(index, INDEX_HTML)
			Files.setLastModifiedTime(index, JAR_TIME)
		}
	}

	@Test
	fun `changed index html is sent again despite the validators of the old one`() {
		val oldEtag =
			mockMvc
				.get("/index.html")
				.andReturn()
				.response
				.getHeader("ETag") ?: "\"old-build\""
		// Same length and timestamp as the old file: only the content tells them apart.
		val newHtml = INDEX_HTML.replace("fixture", "deploy2")
		withDeployedIndex(newHtml) {
			for (path in listOf("/index.html", "/inbox", "/projects/0192a4c0-0000-7000-8000-000000000001")) {
				withClue(path) {
					mockMvc
						.get(path) {
							header("If-None-Match", oldEtag)
							header("If-Modified-Since", JAR_DATE)
						}.andExpect {
							status { isOk() }
							content { string(newHtml) }
						}
					// A browser that only kept the date (no ETag yet) gets the new file too.
					mockMvc.get(path) { header("If-Modified-Since", JAR_DATE) }.andExpect {
						status { isOk() }
						content { string(newHtml) }
					}
				}
			}
		}
	}

	@Test
	fun `root forwards a conditional request to the current index html`() {
		// The forward is not followed by MockMvc; `/index.html` with the same headers is covered above.
		mockMvc.get("/") { header("If-Modified-Since", JAR_DATE) }.andExpect {
			status { isOk() }
			forwardedUrl("/index.html")
		}
	}

	@Test
	fun `unchanged files revalidate by content etag and send no last-modified`() {
		for (path in listOf("/index.html", "/inbox", "/main-ABC123.js")) {
			withClue(path) {
				val response = mockMvc.get(path).andReturn().response
				response.getHeader("Last-Modified").shouldBeNull()
				response.getHeader("Cache-Control") shouldBe "no-cache"
				val etag = response.getHeader("ETag").shouldNotBeNull()
				mockMvc.get(path) { header("If-None-Match", etag) }.andExpect {
					status { isNotModified() }
				}
			}
		}
		etagOf("/inbox") shouldBe etagOf("/index.html")
		etagOf("/main-ABC123.js") shouldNotBe etagOf("/index.html")
	}

	@Test
	fun `missing bundle stays 404 with old validators`() {
		mockMvc.get("/main-GONE.js") { header("If-Modified-Since", JAR_DATE) }.andExpect {
			status { isNotFound() }
		}
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
