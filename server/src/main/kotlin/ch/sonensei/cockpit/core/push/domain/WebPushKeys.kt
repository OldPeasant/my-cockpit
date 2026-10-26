package ch.sonensei.cockpit.core.push.domain

import java.util.Base64

/**
 * A push subscriber's Web Push keys (RFC 8291 §3.2), base64url without padding as clients send them: [p256dh] is its
 * P-256 public key as uncompressed point (65 bytes), [auth] its 16-byte authentication secret. The constructor checks
 * only the encoding and sizes; [WebPushEncryption] checks that the point is on the curve.
 */
data class WebPushKeys(
	val p256dh: String,
	val auth: String,
) {
	val publicKeyBytes: ByteArray = decode(p256dh, "p256dh")
	val authBytes: ByteArray = decode(auth, "auth")

	init {
		require(publicKeyBytes.size == PUBLIC_KEY_SIZE && publicKeyBytes[0] == UNCOMPRESSED) {
			"p256dh must be an uncompressed P-256 point ($PUBLIC_KEY_SIZE bytes)"
		}
		require(authBytes.size == AUTH_SIZE) { "auth must be $AUTH_SIZE bytes" }
	}

	// The keys are not secret, but they are long: show only that they exist.
	override fun toString() = "WebPushKeys(…)"

	companion object {
		const val PUBLIC_KEY_SIZE = 65
		const val AUTH_SIZE = 16
		private const val UNCOMPRESSED: Byte = 0x04

		private fun decode(
			value: String,
			name: String,
		): ByteArray =
			try {
				Base64.getUrlDecoder().decode(value.trimEnd('='))
			} catch (_: IllegalArgumentException) {
				throw IllegalArgumentException("$name must be base64url")
			}
	}
}
