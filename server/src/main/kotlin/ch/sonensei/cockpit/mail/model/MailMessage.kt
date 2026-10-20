package ch.sonensei.cockpit.mail.model

import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Cached headers of one message (SPEC.md §7.2) and the link target of an EMAIL item (SPEC.md §4.3).
 *
 * [folderId] + [uid] are where the message currently is; the row is located ([isLocated]) only when both are set.
 * When the sync no longer finds it there (moved, expunged, UIDVALIDITY changed) it may keep the row with [uid] `null`
 * (see [detach]) so links to it survive; [MailMessageLocator] then finds the message again by [accountId] +
 * [messageIdHeader]. Deleting its folder also keeps the row: the database sets [folderId] to `null` and a [uid] left
 * behind is stale and means nothing. [threadKey] groups a conversation (References / In-Reply-To, fallback normalized
 * subject), computed by the sync.
 */
@Entity
@Table(name = "mail_message")
class MailMessage(
	@Column(name = "account_id", nullable = false, updatable = false)
	val accountId: UUID,
	folderId: UUID?,
	uid: Long?,
	id: UUID? = null,
) : BaseEntity(id) {
	@Column(name = "folder_id")
	var folderId: UUID? = folderId
		protected set

	@Column(name = "uid")
	var uid: Long? = uid
		protected set

	init {
		requireLocation(folderId, uid)
	}

	@Column(name = "modseq")
	var modSeq: Long? = null

	/** The `Message-ID` header including angle brackets, e.g. `<abc@example.com>`; null when the message has none. */
	@Column(name = "message_id_header")
	var messageIdHeader: String? = null

	@Column(name = "in_reply_to")
	var inReplyTo: String? = null

	/** The raw `References` header (space separated Message-IDs). */
	@Column(name = "references_header")
	var referencesHeader: String? = null

	@Column(name = "thread_key")
	var threadKey: String? = null

	@Column(name = "subject")
	var subject: String? = null

	@Convert(converter = MailAddressListConverter::class)
	@Column(name = "from_addresses")
	var from: List<MailAddress> = emptyList()

	@Convert(converter = MailAddressListConverter::class)
	@Column(name = "to_addresses")
	var to: List<MailAddress> = emptyList()

	@Convert(converter = MailAddressListConverter::class)
	@Column(name = "cc_addresses")
	var cc: List<MailAddress> = emptyList()

	/** Only known for messages we sent (Sent, Drafts). */
	@Convert(converter = MailAddressListConverter::class)
	@Column(name = "bcc_addresses")
	var bcc: List<MailAddress> = emptyList()

	@Convert(converter = MailAddressListConverter::class)
	@Column(name = "reply_to_addresses")
	var replyTo: List<MailAddress> = emptyList()

	/** The `Date` header. */
	@Column(name = "sent_at")
	var sentAt: Instant? = null

	/** The IMAP INTERNALDATE (arrival in the mailbox); lists sort by it. */
	@Column(name = "received_at")
	var receivedAt: Instant? = null

	@Column(name = "seen", nullable = false)
	var seen: Boolean = false

	@Column(name = "flagged", nullable = false)
	var flagged: Boolean = false

	@Column(name = "answered", nullable = false)
	var answered: Boolean = false

	@Column(name = "draft", nullable = false)
	var draft: Boolean = false

	/** IMAP keywords other than the system flags above (e.g. `$Forwarded`, `$Junk`). */
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "keywords", nullable = false)
	var keywords: Array<String> = emptyArray()

	/** RFC822.SIZE in bytes. */
	@Column(name = "size_bytes", nullable = false)
	var sizeBytes: Long = 0

	@Column(name = "has_attachments", nullable = false)
	var hasAttachments: Boolean = false

	/** Start of the plain text body for lists (short, no markup). */
	@Column(name = "snippet")
	var snippet: String? = null

	/**
	 * Whether the message is currently at [folderId]/[uid] on the server, as far as the cache knows. Needs both: a [uid]
	 * whose folder was deleted ([folderId] `null`) is stale.
	 */
	val isLocated: Boolean get() = folderId != null && uid != null

	/** Records the message's (new) location, e.g. after a move or a UIDVALIDITY resync. */
	fun locate(
		folderId: UUID,
		uid: Long,
	) {
		requireLocation(folderId, uid)
		this.folderId = folderId
		this.uid = uid
	}

	/** The message is no longer at its location; the row stays as a link target (re-resolved by Message-ID). */
	fun detach() {
		uid = null
		modSeq = null
	}

	private fun requireLocation(
		folderId: UUID?,
		uid: Long?,
	) {
		require(uid == null || (uid > 0 && folderId != null)) { "A UID needs a folder and must be positive" }
	}
}
