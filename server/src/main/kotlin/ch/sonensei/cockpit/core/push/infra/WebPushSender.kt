package ch.sonensei.cockpit.core.push.infra

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Web Push (`cockpit.push.*`). */
@ConfigurationProperties("cockpit.push")
class PushProperties(
	/** Who runs this server, for the push services (VAPID `sub` claim): a `mailto:` or `https:` URL. */
	@DefaultValue("https://localhost") val subject: String,
	/** How long a push service keeps a message for a browser that is offline. */
	@DefaultValue("12h") val ttl: Duration,
	@DefaultValue("5s") val connectTimeout: Duration,
	/** Time budget for one request to a push service. */
	@DefaultValue("10s") val timeout: Duration,
) {
	init {
		require(subject.startsWith("mailto:") || subject.startsWith("https://")) {
			"cockpit.push.subject must be a mailto: or https: URL"
		}
		require(!ttl.isNegative) { "cockpit.push.ttl must not be negative" }
	}
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PushProperties::class)
class PushConfiguration

/** How soon the push service should deliver (RFC 8030 §5.3). */
enum class PushUrgency(
	val header: String,
) {
	NORMAL("normal"),
	HIGH("high"),
}

/** Posts encrypted messages to push services (RFC 8030 §5). */
@Component
class WebPushSender(
	private val properties: PushProperties,
) {
	private val http: HttpClient =
		HttpClient
			.newBuilder()
			.connectTimeout(properties.connectTimeout)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build()

	/**
	 * Sends one [body] (already encrypted, [ch.sonensei.cockpit.core.push.domain.WebPushEncryption]) to [endpoint] with the VAPID [authorization].
	 *
	 * @return the push service's HTTP status (201 when accepted, 404/410 when the subscription is gone)
	 * @throws java.io.IOException when the push service cannot be reached
	 */
	fun send(
		endpoint: URI,
		body: ByteArray,
		authorization: String,
		urgency: PushUrgency,
	): Int {
		val request =
			HttpRequest
				.newBuilder(endpoint)
				.timeout(properties.timeout)
				.header("Authorization", authorization)
				.header("TTL", properties.ttl.seconds.toString())
				.header("Urgency", urgency.header)
				.header("Content-Encoding", "aes128gcm")
				.header("Content-Type", "application/octet-stream")
				.POST(HttpRequest.BodyPublishers.ofByteArray(body))
				.build()
		return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
	}
}
