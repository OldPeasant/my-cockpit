package ch.sonensei.cockpit.core.spa

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver

/**
 * Serves the Angular build (copied into `classpath:/static/` by the Dockerfile) under `/` (SPEC.md §11).
 *
 * Client-side routes such as `/inbox` have no file, so they get `index.html` and the Angular router takes
 * over. Paths under `/api/` and `/actuator/` never fall back: an unknown API path stays a 404 instead of HTML.
 * Everything is sent with `Cache-Control: no-cache`, so a deploy never leaves a stale `index.html`
 * pointing at deleted hashed bundles (unchanged files cost only a 304).
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
			.resourceChain(true)
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
		private val SERVER_PREFIXES = listOf("api", "actuator")

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
