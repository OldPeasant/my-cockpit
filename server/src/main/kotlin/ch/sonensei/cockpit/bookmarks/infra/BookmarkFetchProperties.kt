package ch.sonensei.cockpit.bookmarks.infra

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.context.annotation.Configuration
import org.springframework.util.unit.DataSize
import java.time.Duration

/** Fetching the pages of bookmarks for their title, description and favicon (`cockpit.bookmarks.fetch.*`). */
@ConfigurationProperties("cockpit.bookmarks.fetch")
class BookmarkFetchProperties(
	/** How much of a page is read (the metadata is in its head); the rest is not downloaded. */
	@DefaultValue("1MB") val maxSize: DataSize,
	@DefaultValue("5s") val connectTimeout: Duration,
	/** Time for a whole fetch: from the first request, through any redirects, to the end of the last body. */
	@DefaultValue("15s") val timeout: Duration,
	@DefaultValue("5") val maxRedirects: Int,
	/**
	 * Lifts the SSRF protection: private, loopback and link-local addresses and ports other than 80 and 443 are allowed.
	 * For tests and for installations that bookmark pages on the local network.
	 */
	@DefaultValue("false") val allowPrivateNetworks: Boolean,
	/** How many pages are fetched at the same time; more requests wait in a queue. */
	@DefaultValue("2") val threads: Int,
	/** Requests beyond this many waiting are dropped (the bookmark's metadata stays `PENDING` until refreshed). */
	@DefaultValue("500") val queueSize: Int,
) {
	init {
		require(maxSize.toBytes() in 1..Int.MAX_VALUE) { "cockpit.bookmarks.fetch.max-size must be between 1 byte and 2 GB" }
		require(maxRedirects >= 0) { "cockpit.bookmarks.fetch.max-redirects must not be negative" }
		require(timeout.isPositive) { "cockpit.bookmarks.fetch.timeout must be positive" }
		require(connectTimeout.isPositive) { "cockpit.bookmarks.fetch.connect-timeout must be positive" }
		require(threads >= 1) { "cockpit.bookmarks.fetch.threads must be at least 1" }
		require(queueSize >= 1) { "cockpit.bookmarks.fetch.queue-size must be at least 1" }
	}
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BookmarkFetchProperties::class)
class BookmarkFetchConfiguration
