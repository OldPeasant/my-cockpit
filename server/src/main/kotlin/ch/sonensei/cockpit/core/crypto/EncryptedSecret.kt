package ch.sonensei.cockpit.core.crypto

import com.fasterxml.jackson.annotation.JsonValue

/**
 * An external credential (IMAP password, OAuth refresh token, ...). Persisted encrypted by
 * [EncryptedSecretConverter]; `toString` and JSON serialization only ever show [REDACTED].
 * Read the value with [reveal] where it is actually needed (e.g. to log in to the mail server).
 */
class EncryptedSecret(
	private val value: String,
) {
	fun reveal(): String = value

	@JsonValue
	fun redacted(): String = REDACTED

	override fun toString(): String = "EncryptedSecret($REDACTED)"

	override fun equals(other: Any?): Boolean = other is EncryptedSecret && other.value == value

	override fun hashCode(): Int = value.hashCode()

	companion object {
		const val REDACTED = "******"
	}
}
