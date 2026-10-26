package ch.sonensei.cockpit.core.push.infra

import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/**
 * POSTs push messages to UnifiedPush endpoints (the distributor's push server, e.g. ntfy), in the Web Push protocol
 * (RFC 8030) that UnifiedPush servers accept. Redirects are not followed. Endpoint URLs never appear in exceptions this
 * class throws.
 */
@Component
class UnifiedPushClient {
	private val rest: RestClient =
		RestClient
			.builder()
			.requestFactory(
				JdkClientHttpRequestFactory(
					HttpClient
						.newBuilder()
						.connectTimeout(CONNECT_TIMEOUT)
						.followRedirects(HttpClient.Redirect.NEVER)
						.build(),
				).apply { setReadTimeout(READ_TIMEOUT) },
			).build()

	/**
	 * Sends [body] to [endpoint]; [encrypted] bodies are `aes128gcm` (RFC 8291), others JSON. Returns the HTTP status.
	 *
	 * @throws PushDeliveryException if the endpoint cannot be reached
	 */
	fun send(
		endpoint: String,
		body: ByteArray,
		encrypted: Boolean,
		urgency: String,
	): Int =
		try {
			rest
				.post()
				.uri(java.net.URI(endpoint))
				.header("TTL", TTL.toSeconds().toString())
				.header("Urgency", urgency)
				.apply {
					if (encrypted) {
						header("Content-Encoding", "aes128gcm")
						contentType(MediaType.APPLICATION_OCTET_STREAM)
					} else {
						contentType(MediaType.APPLICATION_JSON)
					}
				}.body(body)
				.exchange { _, response -> response.statusCode.value() }
		} catch (e: Exception) {
			// The message may contain the URL; keep only the type.
			throw PushDeliveryException(e.javaClass.simpleName)
		}

	companion object {
		private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
		private val READ_TIMEOUT: Duration = Duration.ofSeconds(10)

		/** How long the push server keeps an undelivered message: a day-old reminder or mail push is no use. */
		val TTL: Duration = Duration.ofDays(1)
	}
}

/** A push could not be delivered (connection failed, timeout); [message] names only the cause's type. */
class PushDeliveryException(
	message: String,
) : RuntimeException(message)
