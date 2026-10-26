package ch.sonensei.cockpit.core.push.domain

import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.util.UUID

/**
 * The UnifiedPush endpoint of one app installation's UnifiedPush instance [instanceId] (chosen by the app). [p256dh] and
 * [auth] are the instance's Web Push keys (base64url, RFC 8291), both set or both `null`; with them pushes are
 * encrypted. The endpoint URL is a capability (whoever knows it can push to the device): it is never logged.
 */
@Entity
@Table(name = "push_unifiedpush_registration")
class UnifiedPushRegistration(
	@Column(name = "instance_id", nullable = false, updatable = false)
	val instanceId: String,
	endpoint: String,
	keys: WebPushKeys?,
	id: UUID? = null,
) : BaseEntity(id) {
	@Column(name = "endpoint", nullable = false)
	var endpoint: String = endpoint
		protected set

	@Column(name = "p256dh")
	var p256dh: String? = keys?.p256dh
		protected set

	@Column(name = "auth")
	var auth: String? = keys?.auth
		protected set

	/** The stored keys; `null` when the instance gets unencrypted pushes. */
	val keys: WebPushKeys? get() = p256dh?.let { p -> auth?.let { a -> WebPushKeys(p, a) } }

	fun update(
		endpoint: String,
		keys: WebPushKeys?,
	) {
		this.endpoint = endpoint
		p256dh = keys?.p256dh
		auth = keys?.auth
	}
}
