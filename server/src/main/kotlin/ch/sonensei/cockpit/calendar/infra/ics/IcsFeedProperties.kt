package ch.sonensei.cockpit.calendar.infra.ics

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.context.annotation.Configuration
import org.springframework.util.unit.DataSize
import java.time.Duration

/** Fetching ICS feeds (`cockpit.calendar.ics.*`). The defaults suit production. */
@ConfigurationProperties("cockpit.calendar.ics")
class IcsFeedProperties(
	/** How often a feed is fetched. */
	@DefaultValue("15m") val pollInterval: Duration,
	/** Largest feed that is read (after decompression). */
	@DefaultValue("10MB") val maxSize: DataSize,
	@DefaultValue("10s") val connectTimeout: Duration,
	/**
	 * Time for a whole fetch: from sending the first request, through any redirects, to the end of the last body.
	 * (Resolving the host name for the SSRF check is not part of it.)
	 */
	@DefaultValue("60s") val timeout: Duration,
	@DefaultValue("5") val maxRedirects: Int,
	/**
	 * Lifts the SSRF protection for every host: private, loopback and link-local addresses are allowed. For tests and
	 * for installations whose feeds are all on the local network.
	 */
	@DefaultValue("false") val allowPrivateNetworks: Boolean,
	/** Host names (exactly as in the URL, case-insensitive) whose feeds may be on a private address, e.g. a NAS. */
	@DefaultValue val allowedPrivateHosts: List<String>,
) {
	init {
		require(maxSize.toBytes() in 1..Int.MAX_VALUE) { "cockpit.calendar.ics.max-size must be between 1 byte and 2 GB" }
		require(maxRedirects >= 0) { "cockpit.calendar.ics.max-redirects must not be negative" }
		require(!pollInterval.isNegative && !pollInterval.isZero) { "cockpit.calendar.ics.poll-interval must be positive" }
	}
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IcsFeedProperties::class)
class IcsFeedConfiguration
