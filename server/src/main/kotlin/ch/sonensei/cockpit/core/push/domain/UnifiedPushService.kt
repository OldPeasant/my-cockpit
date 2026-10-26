package ch.sonensei.cockpit.core.push.domain

import ch.sonensei.cockpit.core.push.infra.UnifiedPushRegistrationRepository
import ch.sonensei.cockpit.core.web.InvalidRequestException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.net.URISyntaxException

/** The UnifiedPush endpoints of the owner's app installations. */
@Service
@Transactional
class UnifiedPushService(
	private val registrations: UnifiedPushRegistrationRepository,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * Stores [endpoint] (and [keys]) for [instanceId], replacing what the instance registered before.
	 *
	 * @throws InvalidRequestException (400) for an endpoint that is not an absolute http(s) URL or keys that are not a
	 *   P-256 public key
	 */
	fun register(
		instanceId: String,
		endpoint: String,
		keys: WebPushKeys?,
	) {
		checkEndpoint(endpoint)
		if (keys != null) {
			try {
				WebPushEncryption.encrypt(ByteArray(0), keys)
			} catch (_: IllegalArgumentException) {
				throw InvalidRequestException("p256dh", "must be a P-256 public key")
			}
		}
		val existing = registrations.findByInstanceId(instanceId)
		if (existing == null) {
			registrations.save(UnifiedPushRegistration(instanceId, endpoint, keys))
			log.info("UnifiedPush instance {} registered", instanceId)
		} else {
			existing.update(endpoint, keys)
		}
	}

	/** Forgets [instanceId]'s endpoint; nothing happens if it has none. */
	fun unregister(instanceId: String) {
		if (registrations.deleteByInstance(instanceId) > 0) log.info("UnifiedPush instance {} unregistered", instanceId)
	}

	@Transactional(readOnly = true)
	fun all(): List<UnifiedPushRegistration> = registrations.findAll()

	/** Removes [registration] because its distributor said the endpoint is gone, unless it changed since. */
	fun removeGone(registration: UnifiedPushRegistration) {
		if (registrations.deleteIfEndpoint(registration.id, registration.endpoint) > 0) {
			log.info("UnifiedPush instance {} removed: its endpoint is gone", registration.instanceId)
		}
	}

	private fun checkEndpoint(endpoint: String) {
		val uri =
			try {
				URI(endpoint)
			} catch (_: URISyntaxException) {
				null
			}
		if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) {
			throw InvalidRequestException("endpoint", "must be an absolute http or https URL")
		}
	}
}
