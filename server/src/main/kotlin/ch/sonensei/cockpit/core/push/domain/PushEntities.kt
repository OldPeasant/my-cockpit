package ch.sonensei.cockpit.core.push.domain

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * The server's VAPID key pair (RFC 8292), at most one: [publicKey] as unpadded base64url of the uncompressed P-256
 * point (the browser's `applicationServerKey`), [privateKey] as base64 PKCS#8, encrypted with the master key.
 */
@Entity
@Table(name = "push_vapid_key")
class VapidKey(
	@Column(name = "public_key", nullable = false, updatable = false)
	val publicKey: String,
	@Column(name = "private_key", nullable = false, updatable = false)
	val privateKey: EncryptedSecret,
	id: UUID? = null,
) : BaseEntity(id) {
	/** Always true; its unique constraint keeps a second key pair out, also when two instances start at once. */
	@Column(name = "singleton", nullable = false, updatable = false)
	val singleton: Boolean = true
}

/**
 * A browser subscribed to Web Push: its push service [endpoint] and the keys to encrypt messages for it ([p256dh]:
 * the browser's P-256 public key, [auth]: its authentication secret, both base64url as the Push API returns them).
 */
@Entity
@Table(name = "push_subscription")
class PushSubscription(
	@Column(name = "endpoint", nullable = false, updatable = false)
	val endpoint: String,
	p256dh: String,
	auth: String,
	id: UUID? = null,
) : BaseEntity(id) {
	@Column(name = "p256dh", nullable = false)
	var p256dh: String = p256dh
		protected set

	@Column(name = "auth", nullable = false)
	var auth: String = auth
		protected set

	/** A browser that subscribes again with the same endpoint may hand out new keys. */
	fun updateKeys(
		p256dh: String,
		auth: String,
	) {
		this.p256dh = p256dh
		this.auth = auth
	}
}
