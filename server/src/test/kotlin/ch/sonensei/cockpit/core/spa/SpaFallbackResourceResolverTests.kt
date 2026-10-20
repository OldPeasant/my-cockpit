package ch.sonensei.cockpit.core.spa

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.web.servlet.resource.ResourceResolverChain
import java.nio.file.Files
import java.nio.file.Path

/** The resolver on its own, without a Spring context. */
class SpaFallbackResourceResolverTests {
	private val resolver = SpaFallbackResourceResolver()

	private fun resolve(
		path: String,
		location: Path,
	): Resource? = resolver.resolveResource(null, path, listOf(FileSystemResource("$location/")), NoChain)

	private fun build(
		dir: Path,
		withIndex: Boolean = true,
	): Path =
		dir.resolve("web").also {
			Files.createDirectories(it)
			if (withIndex) Files.writeString(it.resolve("index.html"), "<app-root></app-root>")
			Files.writeString(it.resolve("main-ABC123.js"), "console.log('fixture')")
		}

	@Test
	fun `existing file resolves to itself`(
		@TempDir dir: Path,
	) {
		resolve("main-ABC123.js", build(dir)).shouldNotBeNull().filename shouldBe "main-ABC123.js"
	}

	@Test
	fun `client route resolves to index html`(
		@TempDir dir: Path,
	) {
		resolve("projects/some-id", build(dir)).shouldNotBeNull().filename shouldBe "index.html"
	}

	@Test
	fun `server paths and missing files resolve to nothing`(
		@TempDir dir: Path,
	) {
		val web = build(dir)
		resolve("api/v1/tasks", web).shouldBeNull()
		resolve("actuator/", web).shouldBeNull()
		resolve("main-GONE.js", web).shouldBeNull()
	}

	@Test
	fun `client route without a web build resolves to nothing`(
		@TempDir dir: Path,
	) {
		// A server started without the Angular build (e.g. `bootRun` in dev) answers 404, not an error.
		resolve("inbox", build(dir, withIndex = false)).shouldBeNull()
	}

	private object NoChain : ResourceResolverChain {
		override fun resolveResource(
			request: HttpServletRequest?,
			requestPath: String,
			locations: List<Resource>,
		): Resource? = null

		override fun resolveUrlPath(
			resourcePath: String,
			locations: List<Resource>,
		): String? = null
	}
}
