package ch.sonensei.cockpit.core.oauth.domain

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant

/** The OAuth providers the owner can connect (SPEC.md §7.1). [slug] is the path segment of `/api/v1/oauth/{provider}`. */
enum class OAuthProvider(
	val slug: String,
) {
	GOOGLE("google"),
	MICROSOFT("microsoft"),
	;

	companion object {
		fun fromSlug(slug: String): OAuthProvider? = entries.firstOrNull { it.slug == slug }
	}
}

/**
 * What the owner was connecting the account for. Every connection gets the combined scopes of all purposes; the
 * purpose only tells the client where to continue after the callback (e.g. adding a calendar source).
 */
enum class OAuthPurpose {
	CALENDAR,
	MAIL,
	CONTACTS,
}

/**
 * Which API an access token is for. Google issues one token for all its APIs. Microsoft issues a token per resource,
 * so Graph (calendar, contacts) and Outlook IMAP/SMTP (mail, XOAUTH2) need separate tokens from the same refresh token.
 */
enum class OAuthResource {
	/** Google Calendar/People APIs, Microsoft Graph. */
	API,

	/** IMAP/SMTP with XOAUTH2. */
	MAIL,
}

enum class OAuthConnectionStatus {
	/** The refresh token works (as far as we know). */
	CONNECTED,

	/** The refresh token was rejected or is missing: the owner has to connect the account again. */
	RECONNECT_REQUIRED,
}

/**
 * One connected provider account (`oauth_connection`, one per [provider] and [subject]). The [refreshToken] is stored
 * encrypted and never returned by the API; access tokens come from [OAuthTokenProvider].
 */
@Entity
@Table(name = "oauth_connection")
class OAuthConnection(
	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, updatable = false)
	val provider: OAuthProvider,
	/** The provider's stable id of the account (`sub` of the ID token). */
	@Column(name = "subject", nullable = false, updatable = false)
	val subject: String,
	@Column(name = "email")
	var email: String?,
	@Column(name = "display_name")
	var displayName: String?,
	/** Granted scopes, space-separated as the provider returned them. */
	@Column(name = "scopes", nullable = false)
	var scopes: String,
	@Column(name = "refresh_token")
	var refreshToken: EncryptedSecret?,
) : BaseEntity() {
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	var status: OAuthConnectionStatus = OAuthConnectionStatus.CONNECTED

	/** Why the last refresh failed (provider error code, never a token or a response body). */
	@Column(name = "last_error")
	var lastError: String? = null

	@Column(name = "last_refreshed_at")
	var lastRefreshedAt: Instant? = null

	val scopeList: List<String>
		get() = scopes.split(' ').filter { it.isNotEmpty() }

	fun markConnected(
		refreshToken: EncryptedSecret,
		now: Instant,
	) {
		this.refreshToken = refreshToken
		status = OAuthConnectionStatus.CONNECTED
		lastError = null
		lastRefreshedAt = now
	}

	fun markReconnectRequired(error: String) {
		refreshToken = null
		status = OAuthConnectionStatus.RECONNECT_REQUIRED
		lastError = error
	}
}
