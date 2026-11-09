package ch.sonensei.cockpit.core.spa

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.util.ResourceUtils
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves the Angular build (copied into `classpath:/static/` by the Dockerfile) under `/` (SPEC.md §11).
 *
 * Client-side routes such as `/inbox` have no file, so they get `index.html` and the Angular router takes
 * over. Paths under `/api/`, `/actuator/` and `/download/` (the Android app) never fall back: an unknown server
 * path stays a 404 instead of HTML.
 *
 * Caching: everything (`index.html`, the client-route fallback and the hashed bundles) is sent with
 * `Cache-Control: no-cache`, so the browser revalidates on every use. The validator is a strong `ETag` computed
 * from the file content ([ContentEtags]); `Last-Modified` is not sent and `If-Modified-Since` is ignored, because
 * every entry of the reproducible boot jar has the same 1980 timestamp and a date check would answer 304 even
 * after a deploy changed `index.html`. A changed file therefore gets a 200 with the new content, an unchanged
 * one a cheap 304 for a matching `If-None-Match`.
 * Digests are cached for a `classpath:` location (the default): classpath content never changes while the server
 * runs, whether it is a packed jar or the unpacked jar the Dockerfile runs (where the files are real files under
 * `BOOT-INF/classes/static/`). Any other location (a `file:` `cockpit.spa.location` in dev and tests) is hashed on
 * every request, since its files can change in place.
 * The chain runs without Spring's caching resolver: its cache has no size limit, and the fallback would add
 * an entry for every distinct client-route URL.
 */
@Configuration
class SpaWebConfiguration(
	@Value("\${cockpit.spa.location:classpath:/static/}") private val location: String,
) : WebMvcConfigurer {
	override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
		registry
			.addResourceHandler("/**")
			.addResourceLocations(location)
			.setCacheControl(CacheControl.noCache())
			.setUseLastModified(false)
			.setEtagGenerator(ContentEtags(cacheDigests = location.startsWith(ResourceUtils.CLASSPATH_URL_PREFIX))::etag)
			.resourceChain(false)
			.addResolver(SpaFallbackResourceResolver())
	}

	// The resource handler ignores an empty path, so `/` needs its own forward.
	override fun addViewControllers(registry: ViewControllerRegistry) {
		registry.addViewController("/").setViewName("forward:/index.html")
	}
}

/** Resolves existing files as usual and answers client routes with `index.html`. */
class SpaFallbackResourceResolver : PathResourceResolver() {
	override fun getResource(
		resourcePath: String,
		location: Resource,
	): Resource? {
		super.getResource(resourcePath, location)?.let { return it }
		return if (isClientRoute(resourcePath)) super.getResource(INDEX, location) else null
	}

	companion object {
		private const val INDEX = "index.html"
		private val SERVER_PREFIXES = listOf("api", "actuator", "download")

		/**
		 * True for paths the Angular router owns: not under a server prefix and without a file extension in
		 * the last segment (a missing `/main-XYZ.js` must stay a 404). [path] has no leading slash.
		 */
		fun isClientRoute(path: String): Boolean {
			val firstSegment = path.substringBefore('/')
			if (firstSegment in SERVER_PREFIXES) return false
			return '.' !in path.substringAfterLast('/')
		}
	}
}

/**
 * Strong ETags from the content of the served files (SHA-256, hex), so revalidation detects any change.
 *
 * With [cacheDigests] each digest is computed once and kept per resource URL. The caller sets it for content that
 * never changes while the server runs: a `classpath:` location, both inside a packed boot jar and in the unpacked
 * jar the Dockerfile runs, whose files are real files on disk. The map is bounded by the number of files in the
 * build, because client routes all resolve to the same `index.html`.
 * Without it (a `file:` `cockpit.spa.location` in dev and tests) every request hashes the file again: such files can
 * change in place without a reliable size or date change.
 */
class ContentEtags(
	private val cacheDigests: Boolean,
) {
	private val digests = ConcurrentHashMap<String, String>()

	/** The ETag value of [resource]; a read error propagates uncached, as serving the file would fail as well. */
	fun etag(resource: Resource): String {
		if (!cacheDigests) return digest(resource)
		return digests.computeIfAbsent(resource.url.toString()) { digest(resource) }
	}

	private fun digest(resource: Resource): String {
		val sha = MessageDigest.getInstance("SHA-256")
		resource.inputStream.use { input ->
			val buffer = ByteArray(BUFFER_SIZE)
			while (true) {
				val read = input.read(buffer)
				if (read < 0) break
				sha.update(buffer, 0, read)
			}
		}
		return HexFormat.of().formatHex(sha.digest())
	}

	private companion object {
		const val BUFFER_SIZE = 8192
	}
}
