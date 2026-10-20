package ch.sonensei.cockpit.mail.model

/** How an account authenticates (SPEC.md §7.1). Stored by name. */
enum class MailAccountType {
	/** Any IMAP/SMTP server with username and (app) password. */
	IMAP_SMTP,

	/** Gmail via IMAP/SMTP with XOAUTH2 through a Google OAuth connection. */
	GOOGLE,

	/** Outlook/M365 via IMAP/SMTP with XOAUTH2 through a Microsoft OAuth connection. */
	MICROSOFT,
}

/** Transport security of an IMAP or SMTP connection. */
enum class MailSecurity {
	/** TLS from the first byte (IMAPS 993, SMTPS 465). */
	SSL_TLS,

	/** Plain connection upgraded with STARTTLS (IMAP 143, submission 587). */
	STARTTLS,

	/** Unencrypted; only for local test servers. */
	NONE,
}

/** The special purpose of a folder: detected via SPECIAL-USE (RFC 6154), overridable per folder. */
enum class MailFolderRole {
	INBOX,
	SENT,
	DRAFTS,
	TRASH,
	ARCHIVE,
	SPAM,
}
