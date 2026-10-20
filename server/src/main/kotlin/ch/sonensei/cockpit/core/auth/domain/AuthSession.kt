package ch.sonensei.cockpit.core.auth.domain

import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Duration
import java.time.Instant

/**
 * A web login session (SPEC.md §4.1). The browser holds a random token in the session cookie; only its SHA-256
 * [tokenHash] is stored, so a database dump does not contain usable sessions. [expiresAt] slides forward on use by
 * [idleLifetime]: 30 days for a remembered device, [BROWSER_SESSION_IDLE] otherwise.
 */
@Entity
@Table(name = "auth_session")
class AuthSession(
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	val user: UserAccount,
	@Column(name = "token_hash", nullable = false, updatable = false)
	val tokenHash: String,
	@Column(name = "remember_device", nullable = false, updatable = false)
	val rememberDevice: Boolean,
	@Column(name = "last_seen_at", nullable = false)
	var lastSeenAt: Instant,
	@Column(name = "expires_at", nullable = false)
	var expiresAt: Instant,
) : BaseEntity() {
	val idleLifetime: Duration
		get() = idleLifetime(rememberDevice)

	fun isExpiredAt(now: Instant): Boolean = !now.isBefore(expiresAt)

	companion object {
		/** Sliding lifetime of a session with "remember device" (SPEC.md §4.1). */
		val REMEMBERED_LIFETIME: Duration = Duration.ofDays(30)

		/** Idle timeout of a session without "remember device"; its cookie also ends with the browser session. */
		val BROWSER_SESSION_IDLE: Duration = Duration.ofHours(12)

		fun idleLifetime(rememberDevice: Boolean): Duration = if (rememberDevice) REMEMBERED_LIFETIME else BROWSER_SESSION_IDLE
	}
}
