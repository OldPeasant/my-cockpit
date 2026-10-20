package ch.sonensei.cockpit.core.auth.domain

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Instant

/**
 * The single owner account (SPEC.md §4.1). It is created by the setup flow and becomes active once the TOTP
 * enrollment is confirmed ([totpConfirmedAt] set). The TOTP secret is stored encrypted (core.crypto), the password
 * and recovery codes only as Argon2id hashes. `toString` never shows any of them.
 */
@Entity
@Table(name = "app_user")
class UserAccount(
	@Column(name = "username", nullable = false)
	var username: String,
	@Column(name = "password_hash", nullable = false)
	var passwordHash: String,
	@Column(name = "totp_secret", nullable = false)
	var totpSecret: EncryptedSecret,
) : BaseEntity() {
	@Column(name = "totp_confirmed_at")
	var totpConfirmedAt: Instant? = null
		protected set

	@OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
	val recoveryCodes: MutableList<RecoveryCode> = mutableListOf()

	/** Active means setup is complete: the TOTP enrollment was confirmed. */
	val isActive: Boolean
		get() = totpConfirmedAt != null

	fun confirmTotp(at: Instant) {
		check(!isActive) { "TOTP is already confirmed" }
		totpConfirmedAt = at
	}

	fun addRecoveryCode(codeHash: String) {
		recoveryCodes += RecoveryCode(this, codeHash)
	}
}

/** One recovery code of [UserAccount], stored only as an Argon2id hash. [usedAt] marks a consumed code. */
@Entity
@Table(name = "recovery_code")
class RecoveryCode(
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	val user: UserAccount,
	@Column(name = "code_hash", nullable = false, updatable = false)
	val codeHash: String,
) : BaseEntity() {
	@Column(name = "used_at")
	var usedAt: Instant? = null
		protected set

	val isUsed: Boolean
		get() = usedAt != null

	/** Consumes the code: a recovery code works only once. */
	fun markUsed(at: Instant) {
		check(!isUsed) { "Recovery code is already used" }
		usedAt = at
	}
}
