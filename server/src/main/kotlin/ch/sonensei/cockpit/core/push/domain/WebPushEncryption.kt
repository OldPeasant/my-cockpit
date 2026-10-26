package ch.sonensei.cockpit.core.push.domain

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Message encryption for Web Push (RFC 8291) in the `aes128gcm` content coding (RFC 8188), as UnifiedPush distributors
 * relay it to apps that registered [WebPushKeys]: one record, no padding, a fresh ephemeral P-256 key and salt per
 * message. The result is the request body, sent with `Content-Encoding: aes128gcm`.
 */
object WebPushEncryption {
	private const val RECORD_SIZE = 4096
	private const val SALT_SIZE = 16
	private const val KEY_SIZE = 16
	private const val NONCE_SIZE = 12
	private const val TAG_BITS = 128
	private const val COORDINATE_SIZE = 32
	private const val LAST_RECORD: Byte = 0x02

	private val random = SecureRandom()

	/** The P-256 curve parameters. */
	val P256: ECParameterSpec =
		AlgorithmParameters
			.getInstance("EC")
			.apply { init(ECGenParameterSpec("secp256r1")) }
			.getParameterSpec(ECParameterSpec::class.java)

	/**
	 * Encrypts [plaintext] for the subscriber with [keys].
	 *
	 * @throws IllegalArgumentException if the subscriber's public key is not a valid P-256 point
	 */
	fun encrypt(
		plaintext: ByteArray,
		keys: WebPushKeys,
	): ByteArray = encrypt(plaintext, keys, ByteArray(SALT_SIZE).also(random::nextBytes), newKeyPair())

	/** [encrypt] with a given [salt] and ephemeral [serverKeys] (for the RFC 8291 test vector). */
	internal fun encrypt(
		plaintext: ByteArray,
		keys: WebPushKeys,
		salt: ByteArray,
		serverKeys: KeyPair,
	): ByteArray {
		require(plaintext.size + 1 + TAG_BITS / 8 <= RECORD_SIZE) { "The message does not fit into one record" }
		val uaPublic = keys.publicKeyBytes
		val asPublic = encodePoint(serverKeys.public as ECPublicKey)
		val ecdhSecret =
			try {
				KeyAgreement.getInstance("ECDH").run {
					init(serverKeys.private)
					doPhase(decodePoint(uaPublic), true)
					generateSecret()
				}
			} catch (e: java.security.GeneralSecurityException) {
				throw IllegalArgumentException("p256dh is not a valid P-256 public key", e)
			}
		// RFC 8291 §3.3/§3.4: combine the ECDH secret with the auth secret, then derive key and nonce (RFC 8188 §2.2).
		val prkKey = hmac(keys.authBytes, ecdhSecret)
		val ikm = hmac(prkKey, "WebPush: info".toByteArray() + 0 + uaPublic + asPublic + 1)
		val prk = hmac(salt, ikm)
		val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(KEY_SIZE)
		val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(NONCE_SIZE)
		val ciphertext =
			Cipher.getInstance("AES/GCM/NoPadding").run {
				init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
				doFinal(plaintext + LAST_RECORD)
			}
		// RFC 8188 §2.1 header: salt, record size, key id length, key id (the server's public key).
		return ByteBuffer
			.allocate(SALT_SIZE + 4 + 1 + asPublic.size + ciphertext.size)
			.put(salt)
			.putInt(RECORD_SIZE)
			.put(asPublic.size.toByte())
			.put(asPublic)
			.put(ciphertext)
			.array()
	}

	private fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(P256) }.generateKeyPair()

	private fun hmac(
		key: ByteArray,
		data: ByteArray,
	): ByteArray =
		Mac.getInstance("HmacSHA256").run {
			init(SecretKeySpec(key, "HmacSHA256"))
			doFinal(data)
		}

	private operator fun ByteArray.plus(byte: Int): ByteArray = this + byte.toByte()

	/** An uncompressed point (`0x04 || X || Y`) as public key; the key agreement rejects points not on the curve. */
	fun decodePoint(bytes: ByteArray): ECPublicKey {
		val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORDINATE_SIZE))
		val y = BigInteger(1, bytes.copyOfRange(1 + COORDINATE_SIZE, 1 + 2 * COORDINATE_SIZE))
		return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256)) as ECPublicKey
	}

	/** [key] as uncompressed point (`0x04 || X || Y`, 65 bytes). */
	fun encodePoint(key: ECPublicKey): ByteArray = byteArrayOf(0x04) + key.w.affineX.fixed() + key.w.affineY.fixed()

	private fun BigInteger.fixed(): ByteArray {
		val bytes = toByteArray()
		return when {
			bytes.size == COORDINATE_SIZE -> bytes
			bytes.size > COORDINATE_SIZE -> bytes.copyOfRange(bytes.size - COORDINATE_SIZE, bytes.size)
			else -> ByteArray(COORDINATE_SIZE - bytes.size) + bytes
		}
	}
}
