package ch.sonensei.cockpit.mail.model

import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A cached message body (SPEC.md §7.2), at most one per [MailMessage]. [textHtml] is the original, unsanitized HTML
 * part: sanitize it before it reaches a client. Managed through [MailBodyCache], which keeps [sizeBytes] and
 * [lastAccessedAt] for the size-bounded LRU eviction.
 */
@Entity
@Table(name = "mail_body")
class MailBody(
	@Column(name = "message_id", nullable = false, updatable = false)
	val messageId: UUID,
	textPlain: String?,
	textHtml: String?,
	@Column(name = "last_accessed_at", nullable = false)
	var lastAccessedAt: Instant,
	id: UUID? = null,
) : BaseEntity(id) {
	@Column(name = "text_plain")
	var textPlain: String? = textPlain
		protected set

	@Column(name = "text_html")
	var textHtml: String? = textHtml
		protected set

	/** UTF-8 bytes of [textPlain] + [textHtml]: what the body costs in the cache budget. */
	@Column(name = "size_bytes", nullable = false)
	var sizeBytes: Long = sizeOf(textPlain, textHtml)
		protected set

	fun replace(
		textPlain: String?,
		textHtml: String?,
	) {
		this.textPlain = textPlain
		this.textHtml = textHtml
		sizeBytes = sizeOf(textPlain, textHtml)
	}

	companion object {
		fun sizeOf(
			textPlain: String?,
			textHtml: String?,
		): Long = utf8Length(textPlain) + utf8Length(textHtml)

		private fun utf8Length(text: String?): Long = text?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0
	}
}
