package ch.sonensei.cockpit.core.oauth.domain

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.time.Instant

/**
 * A started authorization-code flow (`oauth_authorization`), consumed once by the callback. Only the SHA-256 of the
 * `state` is stored; [sessionBinding] ties it to the browser session that started it, so a callback carrying a state
 * from another session (login CSRF, a leaked link) is rejected. [codeVerifier] is the PKCE secret.
 */
@Entity
@Table(name = "oauth_authorization")
class OAuthAuthorization(
	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, updatable = false)
	val provider: OAuthProvider,
	@Enumerated(EnumType.STRING)
	@Column(name = "purpose", nullable = false, updatable = false)
	val purpose: OAuthPurpose,
	@Column(name = "state_hash", nullable = false, updatable = false)
	val stateHash: String,
	@Column(name = "session_binding", nullable = false, updatable = false)
	val sessionBinding: String,
	@Column(name = "code_verifier", nullable = false, updatable = false)
	val codeVerifier: EncryptedSecret,
	@Column(name = "expires_at", nullable = false, updatable = false)
	val expiresAt: Instant,
) : BaseEntity() {
	fun isExpiredAt(now: Instant): Boolean = !now.isBefore(expiresAt)
}
