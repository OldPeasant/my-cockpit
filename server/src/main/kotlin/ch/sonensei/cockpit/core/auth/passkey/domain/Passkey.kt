package ch.sonensei.cockpit.core.auth.passkey.domain

import ch.sonensei.cockpit.core.auth.domain.UserAccount
import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

/**
 * A registered passkey (WebAuthn credential, SPEC.md §4.1). Holds only public data: [credentialId] and
 * [attestedCredentialData] (AAGUID, credential id and COSE public key, in webauthn4j's binary form). [signCount] and
 * the backup flags are the values of the last successful ceremony.
 */
@Entity
@Table(name = "auth_passkey")
class Passkey(
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	val user: UserAccount,
	@Column(name = "name", nullable = false)
	var name: String,
	@Column(name = "credential_id", nullable = false, updatable = false)
	val credentialId: ByteArray,
	@Column(name = "attested_credential_data", nullable = false, updatable = false)
	val attestedCredentialData: ByteArray,
	@Column(name = "sign_count", nullable = false)
	var signCount: Long,
	@Column(name = "uv_initialized", nullable = false)
	var uvInitialized: Boolean,
	@Column(name = "backup_eligible", nullable = false, updatable = false)
	val backupEligible: Boolean,
	@Column(name = "backup_state", nullable = false)
	var backupState: Boolean,
	@Column(name = "transports", updatable = false)
	val transports: String?,
) : BaseEntity() {
	@Column(name = "last_used_at")
	var lastUsedAt: Instant? = null
		protected set

	/** Records a successful authentication with the authenticator's new [signCount] and backup state. */
	fun recordUse(
		signCount: Long,
		userVerified: Boolean,
		backupState: Boolean,
		at: Instant,
	) {
		this.signCount = signCount
		this.uvInitialized = uvInitialized || userVerified
		this.backupState = backupState
		lastUsedAt = at
	}
}
