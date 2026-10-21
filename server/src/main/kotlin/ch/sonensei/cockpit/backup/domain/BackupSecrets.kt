package ch.sonensei.cockpit.backup.domain

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `data/secrets.enc.json` (SPEC.md §10.2): the secrets of a backup made with a passphrase, encrypted with AES-256-GCM
 * under a key derived from the passphrase with Argon2id. The KDF parameters are stored with the salt, so stronger ones
 * can be used later without a new format version; all fields but [ciphertext] are authenticated as associated data.
 */
data class SecretsEnvelope(
	val formatVersion: Int,
	val kdf: KdfParameters,
	val cipher: String,
	/** Base64, 96 bits. */
	val nonce: String,
	/** Base64: the encrypted canonical JSON object of all secrets (name → value), with the 128-bit tag at the end. */
	val ciphertext: String,
)

data class KdfParameters(
	val algorithm: String,
	/** Base64, 128 bits. */
	val salt: String,
	val memoryKiB: Int,
	val iterations: Int,
	val parallelism: Int,
)

/** The passphrase does not decrypt the backup's secrets (or `secrets.enc.json` was tampered with). */
class WrongPassphraseException : InvalidBackupException(listOf(WRONG_PASSPHRASE)) {
	companion object {
		const val WRONG_PASSPHRASE = "The passphrase is wrong"
	}
}

/** Encrypts and decrypts [SecretsEnvelope]s. Never logs or puts the passphrase, the key or a secret in a message. */
object BackupSecrets {
	const val PATH = "${BackupFormat.DATA_DIR}secrets.enc.json"

	/** Record count of the secrets file in the manifest: the number of secret names. */
	const val COUNT_NAME = "secrets"

	const val FORMAT_VERSION = 1
	const val ALGORITHM = "argon2id"
	const val CIPHER = "AES-256-GCM"

	/** RFC 9106's second recommended option with one lane: 64 MiB, 3 passes. */
	val DEFAULT_KDF = Kdf(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)

	// Bounds for a backup's own parameters, so a crafted file cannot make a restore allocate gigabytes or run for hours.
	private const val MAX_MEMORY_KIB = 1024 * 1024
	private const val MAX_ITERATIONS = 20
	private const val MAX_PARALLELISM = 16

	private const val SALT_BYTES = 16
	private const val NONCE_BYTES = 12
	private const val KEY_BYTES = 32
	private const val TAG_BITS = 128

	private val random = SecureRandom()

	data class Kdf(
		val memoryKiB: Int,
		val iterations: Int,
		val parallelism: Int,
	)

	fun encrypt(
		plaintext: ByteArray,
		passphrase: String,
		kdf: Kdf = DEFAULT_KDF,
	): SecretsEnvelope {
		val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
		val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
		val parameters = KdfParameters(ALGORITHM, b64(salt), kdf.memoryKiB, kdf.iterations, kdf.parallelism)
		val envelope = SecretsEnvelope(FORMAT_VERSION, parameters, CIPHER, b64(nonce), "")
		val key = deriveKey(passphrase, salt, kdf)
		val ciphertext = cipher(Cipher.ENCRYPT_MODE, key, nonce, envelope).doFinal(plaintext)
		return envelope.copy(ciphertext = b64(ciphertext))
	}

	/**
	 * @throws WrongPassphraseException when [passphrase] does not decrypt [envelope]
	 * @throws InvalidBackupException when the envelope has an unknown format or parameters out of bounds
	 */
	fun decrypt(
		envelope: SecretsEnvelope,
		passphrase: String,
	): ByteArray {
		val kdf = envelope.kdf
		val invalid = InvalidBackupException(listOf("$PATH is not valid"))
		if (envelope.formatVersion != FORMAT_VERSION || envelope.cipher != CIPHER || kdf.algorithm != ALGORITHM) throw invalid
		if (kdf.memoryKiB !in 8..MAX_MEMORY_KIB || kdf.iterations !in 1..MAX_ITERATIONS || kdf.parallelism !in 1..MAX_PARALLELISM) {
			throw invalid
		}
		val salt = unb64(kdf.salt) ?: throw invalid
		val nonce = unb64(envelope.nonce)?.takeIf { it.size == NONCE_BYTES } ?: throw invalid
		val ciphertext = unb64(envelope.ciphertext) ?: throw invalid
		if (salt.isEmpty()) throw invalid
		val key = deriveKey(passphrase, salt, Kdf(kdf.memoryKiB, kdf.iterations, kdf.parallelism))
		return try {
			cipher(Cipher.DECRYPT_MODE, key, nonce, envelope.copy(ciphertext = "")).doFinal(ciphertext)
		} catch (_: GeneralSecurityException) {
			throw WrongPassphraseException()
		}
	}

	private fun deriveKey(
		passphrase: String,
		salt: ByteArray,
		kdf: Kdf,
	): SecretKeySpec {
		val parameters =
			Argon2Parameters
				.Builder(Argon2Parameters.ARGON2_id)
				.withVersion(Argon2Parameters.ARGON2_VERSION_13)
				.withSalt(salt)
				.withMemoryAsKB(kdf.memoryKiB)
				.withIterations(kdf.iterations)
				.withParallelism(kdf.parallelism)
				.build()
		val key = ByteArray(KEY_BYTES)
		Argon2BytesGenerator().apply { init(parameters) }.generateBytes(passphrase.toByteArray(Charsets.UTF_8), key)
		return SecretKeySpec(key, "AES")
	}

	/** The envelope without its ciphertext, as associated data: the parameters cannot be changed unnoticed. */
	private fun cipher(
		mode: Int,
		key: SecretKeySpec,
		nonce: ByteArray,
		header: SecretsEnvelope,
	): Cipher =
		Cipher.getInstance("AES/GCM/NoPadding").apply {
			init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
			val kdf = header.kdf
			updateAAD(
				"${header.formatVersion}|${header.cipher}|${kdf.algorithm}|${kdf.salt}|${kdf.memoryKiB}|${kdf.iterations}|${kdf.parallelism}|${header.nonce}"
					.toByteArray(Charsets.UTF_8),
			)
		}

	private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

	private fun unb64(text: String): ByteArray? =
		try {
			Base64.getDecoder().decode(text)
		} catch (_: IllegalArgumentException) {
			null
		}
}
