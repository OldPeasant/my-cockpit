package ch.sonensei.cockpit.core.crypto

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Raised when a stored secret cannot be decrypted (tampered, wrong key, unknown format). Never contains the value. */
class SecretDecryptionException(
	message: String,
	cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Encrypts external credentials with the master key (SPEC.md §4.1): AES-256-GCM with a random 96-bit nonce
 * and a 128-bit tag.
 *
 * Stored format (base64): `version (1 byte) | nonce (12 bytes) | ciphertext + tag`. The version byte is
 * authenticated as associated data, so a later format or key rotation can tell old values apart.
 */
class SecretCipher(
	masterKey: ByteArray,
) {
	private val key =
		SecretKeySpec(
			masterKey.also { require(it.size == MasterKey.KEY_BYTES) { "AES-256 needs a ${MasterKey.KEY_BYTES}-byte key" } },
			"AES",
		)
	private val random = SecureRandom()

	fun encrypt(plaintext: String): String {
		val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
		val cipher = cipher(Cipher.ENCRYPT_MODE, nonce)
		val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
		val out =
			ByteBuffer
				.allocate(1 + NONCE_BYTES + ciphertext.size)
				.put(VERSION_1)
				.put(nonce)
				.put(ciphertext)
		return Base64.getEncoder().encodeToString(out.array())
	}

	fun decrypt(encoded: String): String {
		val bytes =
			try {
				Base64.getDecoder().decode(encoded)
			} catch (e: IllegalArgumentException) {
				throw SecretDecryptionException("Encrypted secret is not valid base64", e)
			}
		if (bytes.size < 1 + NONCE_BYTES + TAG_BITS / Byte.SIZE_BITS) {
			throw SecretDecryptionException("Encrypted secret is too short")
		}
		if (bytes[0] != VERSION_1) {
			throw SecretDecryptionException("Unsupported encrypted secret format version ${bytes[0]}")
		}
		val nonce = bytes.copyOfRange(1, 1 + NONCE_BYTES)
		return try {
			val plaintext = cipher(Cipher.DECRYPT_MODE, nonce).doFinal(bytes, 1 + NONCE_BYTES, bytes.size - 1 - NONCE_BYTES)
			String(plaintext, Charsets.UTF_8)
		} catch (e: GeneralSecurityException) {
			throw SecretDecryptionException("Encrypted secret failed authentication (tampered or wrong master key)", e)
		}
	}

	private fun cipher(
		mode: Int,
		nonce: ByteArray,
	): Cipher =
		Cipher.getInstance(TRANSFORMATION).apply {
			init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
			updateAAD(byteArrayOf(VERSION_1))
		}

	companion object {
		const val VERSION_1: Byte = 1
		const val NONCE_BYTES = 12
		const val TAG_BITS = 128
		private const val TRANSFORMATION = "AES/GCM/NoPadding"
	}
}
