package ch.sonensei.cockpit.mail.api

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.context.annotation.Configuration
import org.springframework.util.unit.DataSize
import java.time.Duration

/** Limits of reading mail (`cockpit.mail.read.*`). The defaults suit production. */
@ConfigurationProperties("cockpit.mail.read")
class MailReadProperties(
	/** Longest text/plain or text/html part that is read (and cached); the rest is cut off. */
	@DefaultValue("5MB") val maxTextSize: DataSize,
	@DefaultValue val imageProxy: ImageProxy,
) {
	init {
		require(maxTextSize.toBytes() in 1..Int.MAX_VALUE) { "cockpit.mail.read.max-text-size must be between 1 byte and 2 GB" }
	}

	/** The remote image proxy ([RemoteImageFetcher]). */
	class ImageProxy(
		/** Largest image that is proxied. */
		@DefaultValue("5MB") val maxSize: DataSize,
		@DefaultValue("5s") val connectTimeout: Duration,
		/** Time budget for the whole fetch of one image, redirects included. */
		@DefaultValue("10s") val timeout: Duration,
		@DefaultValue("3") val maxRedirects: Int,
		/**
		 * Lifts the SSRF protection: private, loopback and link-local addresses and ports other than 80/443 are
		 * allowed. Only for tests against a local server; never in production.
		 */
		@DefaultValue("false") val allowPrivateNetworks: Boolean,
	) {
		init {
			require(maxSize.toBytes() in 1..Int.MAX_VALUE) { "cockpit.mail.read.image-proxy.max-size must be between 1 byte and 2 GB" }
			require(maxRedirects >= 0) { "cockpit.mail.read.image-proxy.max-redirects must not be negative" }
		}
	}
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailReadProperties::class)
class MailReadConfiguration
