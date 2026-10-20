package ch.sonensei.cockpit.mail.model

import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * A sending identity of an account (SPEC.md §7.1): the From name and address to compose with. [email] is unique per
 * account regardless of case; at most one identity per account [isDefault].
 */
@Entity
@Table(name = "mail_identity")
class MailIdentity(
	@Column(name = "account_id", nullable = false, updatable = false)
	val accountId: UUID,
	@Column(name = "name", nullable = false)
	var name: String,
	@Column(name = "email", nullable = false)
	var email: String,
	id: UUID? = null,
) : BaseEntity(id) {
	@Column(name = "reply_to")
	var replyTo: String? = null

	/** Markdown; overrides [MailAccount.signatureMd] when set. */
	@Column(name = "signature_md")
	var signatureMd: String? = null

	@Column(name = "is_default", nullable = false)
	var isDefault: Boolean = false

	@Column(name = "position", nullable = false)
	var position: Int = 0
}
