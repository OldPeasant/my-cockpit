package ch.sonensei.cockpit.core.spa

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import jakarta.servlet.ServletContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import org.springframework.core.io.AbstractResource
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.support.GenericWebApplicationContext
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * The resource handler [SpaWebConfiguration] registers, without a running server: which location gets cached
 * digests (`classpath:`, production) and which is hashed per request (`file:`, dev and tests).
 */
class SpaWebConfigurationTests {
	private fun sha256(text: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

	/** Exposes the protected handler mapping the registry builds. */
	private class ExposingRegistry(
		context: ApplicationContext,
		servletContext: ServletContext,
	) : ResourceHandlerRegistry(context, servletContext) {
		fun mapping() = handlerMapping
	}

	private fun handlerFor(location: String): ResourceHttpRequestHandler {
		val servletContext = MockServletContext()
		val context = GenericWebApplicationContext(servletContext).apply { refresh() }
		val registry = ExposingRegistry(context, servletContext)
		SpaWebConfiguration(location).addResourceHandlers(registry)
		val mapping = registry.mapping().shouldNotBeNull() as SimpleUrlHandlerMapping
		return mapping.urlMap["/**"] as ResourceHttpRequestHandler
	}

	@Test
	fun `classpath location caches the content digest per url`() {
		val generator = handlerFor("classpath:/static/").etagGenerator.shouldNotBeNull()
		val index = CountingResource("jar:file:/fictitious/app.jar!/BOOT-INF/classes/static/index.html", "<title>build-1</title>")

		generator.apply(index) shouldBe sha256("<title>build-1</title>")
		generator.apply(index) shouldBe sha256("<title>build-1</title>")
		index.reads shouldBe 1
	}

	@Test
	fun `file location hashes the content on every request`(
		@TempDir dir: Path,
	) {
		val generator = handlerFor(dir.toUri().toString()).etagGenerator.shouldNotBeNull()
		val index = CountingResource("file:/fictitious/web/index.html", "<title>build-1</title>")

		generator.apply(index) shouldBe sha256("<title>build-1</title>")
		index.content = "<title>build-2</title>"
		generator.apply(index) shouldBe sha256("<title>build-2</title>")
		index.reads shouldBe 2
	}

	@Test
	fun `handler sends no last-modified and no-cache for every location`(
		@TempDir dir: Path,
	) {
		for (location in listOf("classpath:/static/", dir.toUri().toString())) {
			val handler = handlerFor(location)
			handler.isUseLastModified shouldBe false
			handler.cacheControl.shouldNotBeNull().headerValue shouldBe "no-cache"
		}
	}

	/** A resource that counts how often its content is read. */
	private class CountingResource(
		private val url: String,
		var content: String,
	) : AbstractResource() {
		var reads = 0

		override fun getDescription(): String = "counting $url"

		override fun getURL(): URL = URI.create(url).toURL()

		override fun getInputStream(): InputStream {
			reads++
			return ByteArrayInputStream(content.toByteArray())
		}
	}
}
