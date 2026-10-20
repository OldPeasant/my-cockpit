package ch.sonensei.cockpit.mail.model

import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A cached IMAP folder (SPEC.md §7.2). [path] is unique per account, a [role] at most once per account. The sync
 * state ([uidValidity], [uidNext], [highestModSeq]) is what the server reported at [lastSyncedAt]; a changed
 * UIDVALIDITY invalidates every cached UID of the folder.
 */
@Entity
@Table(name = "mail_folder")
class MailFolder(
	@Column(name = "account_id", nullable = false, updatable = false)
	val accountId: UUID,
	@Column(name = "path", nullable = false)
	var path: String,
	@Column(name = "name", nullable = false)
	var name: String,
	id: UUID? = null,
) : BaseEntity(id) {
	/** Hierarchy delimiter reported by the server (e.g. `/` or `.`); null for flat namespaces. */
	@Column(name = "delimiter")
	var delimiter: String? = null

	@Enumerated(EnumType.STRING)
	@Column(name = "role")
	var role: MailFolderRole? = null

	/** The owner chose [role]; SPECIAL-USE detection must not change it. */
	@Column(name = "role_overridden", nullable = false)
	var roleOverridden: Boolean = false

	/** `\Noselect` folders only group children and hold no messages. */
	@Column(name = "selectable", nullable = false)
	var selectable: Boolean = true

	@Column(name = "uidvalidity")
	var uidValidity: Long? = null

	@Column(name = "uidnext")
	var uidNext: Long? = null

	/** CONDSTORE/QRESYNC state; null when the server does not support it. */
	@Column(name = "highestmodseq")
	var highestModSeq: Long? = null

	@Column(name = "last_synced_at")
	var lastSyncedAt: Instant? = null
}
