package ch.sonensei.cockpit.core.crypto

import java.util.Base64

/** Name of the environment variable holding the master key (SPEC.md §4.1, §11). */
const val MASTER_KEY_ENV = "COCKPIT_MASTER_KEY"

/** Raised when `COCKPIT_MASTER_KEY` is missing or invalid. The message never contains the configured value. */
class InvalidMasterKeyException(
	message: String,
) : IllegalStateException(message)

/** Decodes and validates the AES-256 master key (base64, exactly 32 bytes). */
object MasterKey {
	const val KEY_BYTES = 32

	private const val HOW_TO_FIX = "Set $MASTER_KEY_ENV to a base64-encoded $KEY_BYTES-byte key, e.g. `openssl rand -base64 32`."

	fun decode(encoded: String?): ByteArray {
		if (encoded.isNullOrBlank()) {
			throw InvalidMasterKeyException("$MASTER_KEY_ENV is not set. $HOW_TO_FIX")
		}
		val key =
			try {
				Base64.getDecoder().decode(encoded.trim())
			} catch (_: IllegalArgumentException) {
				throw InvalidMasterKeyException("$MASTER_KEY_ENV is not valid base64. $HOW_TO_FIX")
			}
		if (key.size != KEY_BYTES) {
			throw InvalidMasterKeyException(
				"$MASTER_KEY_ENV must decode to $KEY_BYTES bytes, but has ${key.size}. $HOW_TO_FIX",
			)
		}
		return key
	}
}
