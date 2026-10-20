package ch.sonensei.cockpit.mail.model

import ch.sonensei.cockpit.core.persistence.UuidV7
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * The size-bounded LRU cache of message bodies (SPEC.md §7.2, default 2 GB, `cockpit.mail.body-cache-max-bytes`).
 * Size is accounted in UTF-8 bytes of the plain and HTML parts ([MailBody.sizeBytes]). Storing a body evicts the least
 * recently used others until the total fits again; the body just stored always stays. Bodies are removed with their
 * message (foreign key cascade).
 */
@Component
class MailBodyCache(
	private val bodies: MailBodyRepository,
	private val clock: Clock,
	@param:Value("\${cockpit.mail.body-cache-max-bytes:$DEFAULT_MAX_BYTES}") val maxBytes: Long,
) {
	init {
		require(maxBytes >= 0) { "cockpit.mail.body-cache-max-bytes must not be negative" }
	}

	/** The cached body of a message, marked as recently used; `null` when it is not cached. */
	@Transactional
	fun get(messageId: UUID): MailBody? {
		if (bodies.touch(messageId, clock.instant()) == 0) return null
		return bodies.findByMessageId(messageId)
	}

	/** Caches (or replaces) the body of an existing message, then evicts down to [maxBytes]. */
	@Transactional
	fun put(
		messageId: UUID,
		textPlain: String?,
		textHtml: String?,
	) {
		bodies.upsert(UuidV7.generate(), messageId, textPlain, textHtml, MailBody.sizeOf(textPlain, textHtml), clock.instant())
		bodies.evictBeyond(maxBytes, keep = messageId)
	}

	/** Evicts down to [maxBytes] (e.g. after the limit was lowered). Returns the number of evicted bodies. */
	@Transactional
	fun evict(): Int = bodies.evictBeyond(maxBytes, keep = NO_MESSAGE)

	@Transactional(readOnly = true)
	fun totalSizeBytes(): Long = bodies.totalSizeBytes()

	companion object {
		const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024

		/** The nil UUID: never a message id (ids are UUIDv7), so nothing is exempt from eviction. */
		private val NO_MESSAGE = UUID(0, 0)
	}
}
