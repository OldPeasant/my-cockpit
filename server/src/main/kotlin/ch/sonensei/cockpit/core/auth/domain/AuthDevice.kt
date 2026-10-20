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
 * A device of the Android app (SPEC.md §4.1) with its current token pair. Only SHA-256 hashes of the tokens are
 * stored. The access token lives [ACCESS_LIFETIME]; the refresh token lives [REFRESH_LIFETIME] from its last
 * rotation. Each refresh replaces both ([rotate]); the replaced refresh token is kept as an [AuthDeviceRetiredToken].
 */
@Entity
@Table(name = "auth_device")
class AuthDevice(
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	val user: UserAccount,
	@Column(name = "name", nullable = false, updatable = false)
	val name: String,
	accessTokenHash: String,
	refreshTokenHash: String,
	now: Instant,
) : BaseEntity() {
	@Column(name = "access_token_hash", nullable = false)
	var accessTokenHash: String = accessTokenHash
		protected set

	@Column(name = "access_expires_at", nullable = false)
	var accessExpiresAt: Instant = now.plus(ACCESS_LIFETIME)
		protected set

	@Column(name = "refresh_token_hash", nullable = false)
	var refreshTokenHash: String = refreshTokenHash
		protected set

	@Column(name = "refresh_expires_at", nullable = false)
	var refreshExpiresAt: Instant = now.plus(REFRESH_LIFETIME)
		protected set

	@Column(name = "last_seen_at", nullable = false)
	var lastSeenAt: Instant = now
		protected set

	fun isAccessExpiredAt(now: Instant): Boolean = !now.isBefore(accessExpiresAt)

	fun isRefreshExpiredAt(now: Instant): Boolean = !now.isBefore(refreshExpiresAt)

	fun rotate(
		accessTokenHash: String,
		refreshTokenHash: String,
		now: Instant,
	) {
		this.accessTokenHash = accessTokenHash
		this.refreshTokenHash = refreshTokenHash
		accessExpiresAt = now.plus(ACCESS_LIFETIME)
		refreshExpiresAt = now.plus(REFRESH_LIFETIME)
		lastSeenAt = now
	}

	companion object {
		/** Lifetime of an access token (SPEC.md §4.1). */
		val ACCESS_LIFETIME: Duration = Duration.ofMinutes(15)

		/** A device that does not refresh for this long is logged out. */
		val REFRESH_LIFETIME: Duration = Duration.ofDays(90)
	}
}

/** A refresh token that was replaced by a rotation. Presenting it again revokes its device. */
@Entity
@Table(name = "auth_device_retired_token")
class AuthDeviceRetiredToken(
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "device_id", nullable = false, updatable = false)
	val device: AuthDevice,
	@Column(name = "token_hash", nullable = false, updatable = false)
	val tokenHash: String,
) : BaseEntity()
