package ch.sonensei.cockpit.core.push.domain

import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.push.infra.PushProperties
import ch.sonensei.cockpit.core.push.infra.PushSubscriptionRepository
import ch.sonensei.cockpit.core.push.infra.PushUrgency
import ch.sonensei.cockpit.core.push.infra.WebPushCrypto
import ch.sonensei.cockpit.core.push.infra.WebPushSender
import ch.sonensei.cockpit.core.web.InvalidRequestException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.time.Clock
import java.time.Duration

/**
 * Web Push (SPEC.md §4.6): the browsers subscribed to it, and sending them messages, so reminders and new mail reach
 * the user without an open tab. A message is the JSON `{"event": <SSE event name>, "data": <SSE event data>}`, which the
 * web app's service worker turns into a notification; when that would exceed [MAX_MESSAGE_BYTES], the data is left out
 * and the service worker shows a generic text.
 */
@Service
class PushService(
	private val subscriptions: PushSubscriptionRepository,
	private val vapidKeys: VapidKeyService,
	private val sender: WebPushSender,
	private val properties: PushProperties,
	private val jsonMapper: JsonMapper,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** The public key browsers subscribe with (`applicationServerKey`), unpadded base64url. */
	fun publicKey(): String = vapidKeys.current().publicKeyBase64Url

	/**
	 * Registers a browser's subscription; subscribing again with the same [endpoint] replaces its keys.
	 *
	 * @throws InvalidRequestException (400) for an endpoint that is not an absolute `https` URL, or keys that are not a
	 *   P-256 public key and a 16-byte secret
	 */
	@Transactional
	fun subscribe(
		endpoint: String,
		p256dh: String,
		auth: String,
	): PushSubscription {
		validateEndpoint(endpoint)
		val publicKey = decodeOrNull(p256dh)
		if (publicKey == null || runCatching { WebPushCrypto.publicKey(publicKey) }.isFailure) {
			throw InvalidRequestException("keys.p256dh", "must be an uncompressed P-256 public key (base64url)")
		}
		if (decodeOrNull(auth)?.size != WebPushCrypto.AUTH_SECRET_BYTES) {
			throw InvalidRequestException("keys.auth", "must be a ${WebPushCrypto.AUTH_SECRET_BYTES}-byte secret (base64url)")
		}
		// One upsert statement instead of find-then-insert: two tabs subscribing at once must not hit the unique endpoint.
		subscriptions.upsert(UuidV7.generate(), endpoint, p256dh, auth, clock.instant())
		return checkNotNull(subscriptions.findByEndpoint(endpoint))
	}

	/** Removes a browser's subscription; nothing happens when there is none. */
	@Transactional
	fun unsubscribe(endpoint: String) {
		subscriptions.deleteByEndpoint(endpoint)
	}

	/** Number of subscribed browsers. */
	@Transactional(readOnly = true)
	fun count(): Long = subscriptions.count()

	/**
	 * Sends the SSE event [event] with [data] to every subscribed browser, one after the other, outside any transaction
	 * (it waits for the push services). A subscription the push service reports gone (404, 410) is removed; other
	 * failures are logged and the message is dropped for that browser.
	 *
	 * @return the number of browsers the push services accepted the message for
	 */
	fun send(
		event: String,
		data: Any,
		urgency: PushUrgency = PushUrgency.NORMAL,
	): Int {
		val targets = subscriptions.findAll()
		if (targets.isEmpty()) return 0
		val message = message(event, data)
		val keys = vapidKeys.current()
		val expiresAt = clock.instant().plus(VAPID_VALIDITY)
		var delivered = 0
		targets.forEach { subscription ->
			try {
				val endpoint = URI(subscription.endpoint)
				val body = WebPushCrypto.encrypt(message, WebPushCrypto.decode(subscription.p256dh), WebPushCrypto.decode(subscription.auth))
				val authorization = WebPushCrypto.vapidAuthorization(endpoint, properties.subject, expiresAt, keys.privateKey, keys.publicKey)
				when (val status = sender.send(endpoint, body, authorization, urgency)) {
					in 200..299 -> delivered++
					404, 410 -> {
						log.info("Removing push subscription {}: the push service reports it gone ({})", subscription.id, status)
						subscriptions.deleteByEndpoint(subscription.endpoint)
					}
					else -> log.warn("Push service rejected the {} message for subscription {} with {}", event, subscription.id, status)
				}
			} catch (e: IOException) {
				log.warn("Could not reach the push service of subscription {}: {}", subscription.id, e.toString())
			} catch (e: InterruptedException) {
				Thread.currentThread().interrupt()
				return delivered
			} catch (e: RuntimeException) {
				// E.g. keys that no longer decode; the other subscriptions still get the message.
				log.warn("Sending the {} message to push subscription {} failed", event, subscription.id, e)
			}
		}
		return delivered
	}

	/** The plaintext message; its data is left out when the whole would not fit into one push message. */
	internal fun message(
		event: String,
		data: Any,
	): ByteArray {
		val full = jsonMapper.writeValueAsBytes(mapOf("event" to event, "data" to data))
		return if (full.size <= MAX_MESSAGE_BYTES) full else jsonMapper.writeValueAsBytes(mapOf("event" to event))
	}

	private fun validateEndpoint(endpoint: String) {
		val uri =
			try {
				URI(endpoint)
			} catch (_: URISyntaxException) {
				null
			}
		if (endpoint.length > MAX_ENDPOINT_LENGTH ||
			uri == null ||
			!uri.isAbsolute ||
			!uri.scheme.equals("https", ignoreCase = true) ||
			uri.host.isNullOrEmpty() ||
			uri.rawUserInfo != null
		) {
			throw InvalidRequestException("endpoint", "must be an https URL")
		}
	}

	private fun decodeOrNull(value: String): ByteArray? =
		try {
			WebPushCrypto.decode(value)
		} catch (_: IllegalArgumentException) {
			null
		}

	companion object {
		/** Largest plaintext message: push services accept 4096 bytes of body, of which encryption takes 103. */
		const val MAX_MESSAGE_BYTES = 3072
		const val MAX_ENDPOINT_LENGTH = 2048

		/** VAPID tokens may be valid for at most 24 hours (RFC 8292 §2). */
		private val VAPID_VALIDITY = Duration.ofHours(12)
	}
}
