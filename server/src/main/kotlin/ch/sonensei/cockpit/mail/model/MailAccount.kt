package ch.sonensei.cockpit.mail.model

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

/**
 * A mail account (SPEC.md §7.1). [MailAccountType.IMAP_SMTP] accounts log in with [username] and [password] (stored
 * encrypted, never returned by the API); Google and Microsoft accounts use XOAUTH2 with the access token of
 * [oauthConnectionId] (a core OAuth connection, no foreign key) and never have a password. The database rejects
 * mixing the two.
 */
@Entity
@Table(name = "mail_account")
class MailAccount(
	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, updatable = false)
	val type: MailAccountType,
	@Column(name = "display_name", nullable = false)
	var displayName: String,
	@Column(name = "email_address", nullable = false)
	var emailAddress: String,
	@Column(name = "imap_host", nullable = false)
	var imapHost: String,
	@Column(name = "imap_port", nullable = false)
	var imapPort: Int,
	@Enumerated(EnumType.STRING)
	@Column(name = "imap_security", nullable = false)
	var imapSecurity: MailSecurity,
	@Column(name = "smtp_host", nullable = false)
	var smtpHost: String,
	@Column(name = "smtp_port", nullable = false)
	var smtpPort: Int,
	@Enumerated(EnumType.STRING)
	@Column(name = "smtp_security", nullable = false)
	var smtpSecurity: MailSecurity,
	/** IMAP/SMTP login; for OAuth accounts the mailbox address used in the XOAUTH2 string. */
	@Column(name = "username", nullable = false)
	var username: String,
	id: UUID? = null,
) : BaseEntity(id) {
	@Column(name = "password")
	var password: EncryptedSecret? = null

	@Column(name = "oauth_connection_id")
	var oauthConnectionId: UUID? = null

	/** Markdown; rendered to HTML when composing. Identities may override it. */
	@Column(name = "signature_md")
	var signatureMd: String? = null

	/** Show a notification for new mail in this account's Inbox. */
	@Column(name = "notify_new_mail", nullable = false)
	var notifyNewMail: Boolean = true

	/** How far back headers are cached on the initial sync; older mail is loaded on demand. */
	@Column(name = "sync_window_months", nullable = false)
	var syncWindowMonths: Int = DEFAULT_SYNC_WINDOW_MONTHS

	val usesOAuth: Boolean get() = type != MailAccountType.IMAP_SMTP

	companion object {
		const val DEFAULT_SYNC_WINDOW_MONTHS = 12
	}
}
