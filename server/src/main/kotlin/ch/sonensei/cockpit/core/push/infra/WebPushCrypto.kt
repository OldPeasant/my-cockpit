package ch.sonensei.cockpit.core.push.infra

import java.math.BigInteger
import java.net.URI
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography of Web Push with the JDK's providers only: P-256 key pairs, message encryption (RFC 8291,
 * `aes128gcm` content coding of RFC 8188) and the VAPID authorization header (RFC 8292). Keys travel as unpadded
 * base64url, like the Push API in the browser hands them out.
 */
object WebPushCrypto {
	/** Length of an uncompressed P-256 point: `0x04 | X | Y`. */
	const val PUBLIC_KEY_BYTES = 65
	const val AUTH_SECRET_BYTES = 16

	/** Record size announced in the header; one record carries the whole message. */
	private const val RECORD_SIZE = 4096
	private const val SALT_BYTES = 16
	private const val KEY_BYTES = 16
	private const val NONCE_BYTES = 12
	private const val TAG_BITS = 128
	private const val COORDINATE_BYTES = 32

	private val random = SecureRandom()
	private val base64Url = Base64.getUrlEncoder().withoutPadding()
	private val p256: ECParameterSpec =
		AlgorithmParameters
			.getInstance("EC")
			.apply { init(ECGenParameterSpec("secp256r1")) }
			.getParameterSpec(ECParameterSpec::class.java)

	fun generateKeyPair(): KeyPair =
		KeyPairGenerator
			.getInstance("EC")
			.apply {
				initialize(ECGenParameterSpec("secp256r1"), random)
			}.genKeyPair()

	fun encode(bytes: ByteArray): String = base64Url.encodeToString(bytes)

	/** Decodes base64url with or without padding (browsers and libraries differ); also accepts standard base64. */
	fun decode(value: String): ByteArray =
		Base64.getUrlDecoder().decode(
			value
				.trim()
				.trimEnd('=')
				.replace('+', '-')
				.replace('/', '_'),
		)

	/** The uncompressed point of [key], as the browser's `applicationServerKey` expects it. */
	fun rawPublicKey(key: ECPublicKey): ByteArray = byteArrayOf(0x04) + unsigned(key.w.affineX) + unsigned(key.w.affineY)

	/** @throws IllegalArgumentException when [raw] is not an uncompressed P-256 point on the curve */
	fun publicKey(raw: ByteArray): ECPublicKey {
		require(raw.size == PUBLIC_KEY_BYTES && raw[0] == 0x04.toByte()) { "not an uncompressed P-256 public key" }
		val point =
			ECPoint(
				BigInteger(1, raw.copyOfRange(1, 1 + COORDINATE_BYTES)),
				BigInteger(1, raw.copyOfRange(1 + COORDINATE_BYTES, PUBLIC_KEY_BYTES)),
			)
		require(onCurve(point)) { "point is not on P-256" }
		return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, p256)) as ECPublicKey
	}

	fun privateKey(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

	/**
	 * Encrypts [plaintext] for one subscription (RFC 8291 §3.4): an ephemeral ECDH key with the browser's [uaPublic]
	 * key, mixed with its [authSecret]. Returns the request body: `salt | record size | key id length | ephemeral
	 * public key | ciphertext`.
	 */
	fun encrypt(
		plaintext: ByteArray,
		uaPublic: ByteArray,
		authSecret: ByteArray,
	): ByteArray = encrypt(plaintext, uaPublic, authSecret, ByteArray(SALT_BYTES).also(random::nextBytes), generateKeyPair())

	/** [encrypt] with a given [salt] and [ephemeral] key pair, for the known-answer test of RFC 8291 Appendix A. */
	internal fun encrypt(
		plaintext: ByteArray,
		uaPublic: ByteArray,
		authSecret: ByteArray,
		salt: ByteArray,
		ephemeral: KeyPair,
	): ByteArray {
		require(authSecret.size == AUTH_SECRET_BYTES) { "auth secret must be $AUTH_SECRET_BYTES bytes" }
		require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
		val asPublic = rawPublicKey(ephemeral.public as ECPublicKey)
		val ecdhSecret =
			KeyAgreement
				.getInstance("ECDH")
				.apply {
					init(ephemeral.private)
					doPhase(publicKey(uaPublic), true)
				}.generateSecret()
		val keyInfo = "WebPush: info".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + uaPublic + asPublic
		val ikm = hkdf(salt = authSecret, ikm = ecdhSecret, info = keyInfo, length = 32)
		val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm".toByteArray(Charsets.US_ASCII) + byteArrayOf(0), KEY_BYTES)
		val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".toByteArray(Charsets.US_ASCII) + byteArrayOf(0), NONCE_BYTES)
		// 0x02 marks the last (and only) record; no further padding.
		val padded = plaintext + byteArrayOf(2)
		require(padded.size + TAG_BITS / Byte.SIZE_BITS <= RECORD_SIZE) { "push message too large" }
		val ciphertext =
			Cipher
				.getInstance("AES/GCM/NoPadding")
				.apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(TAG_BITS, nonce)) }
				.doFinal(padded)
		return ByteBuffer
			.allocate(SALT_BYTES + 4 + 1 + asPublic.size + ciphertext.size)
			.put(salt)
			.putInt(RECORD_SIZE)
			.put(asPublic.size.toByte())
			.put(asPublic)
			.put(ciphertext)
			.array()
	}

	/**
	 * The `Authorization` header value for a push to [endpoint] (RFC 8292 §3): a JWT signed with the server's VAPID
	 * key, for the endpoint's origin, valid until [expiresAt], naming the operator [subject] (`mailto:` or `https:`).
	 */
	fun vapidAuthorization(
		endpoint: URI,
		subject: String,
		expiresAt: Instant,
		privateKey: PrivateKey,
		publicKey: ByteArray,
	): String {
		val audience = "${endpoint.scheme}://${endpoint.rawAuthority}"
		val header = encode("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
		val claims =
			encode("""{"aud":${json(audience)},"exp":${expiresAt.epochSecond},"sub":${json(subject)}}""".toByteArray())
		val signature =
			Signature
				.getInstance("SHA256withECDSAinP1363Format")
				.apply {
					initSign(privateKey)
					update("$header.$claims".toByteArray(Charsets.US_ASCII))
				}.sign()
		return "vapid t=$header.$claims.${encode(signature)}, k=${encode(publicKey)}"
	}

	/** HKDF-SHA256 (RFC 5869) for outputs of at most one block, which is all Web Push needs. */
	internal fun hkdf(
		salt: ByteArray,
		ikm: ByteArray,
		info: ByteArray,
		length: Int,
	): ByteArray {
		val prk = hmac(salt, ikm)
		return hmac(prk, info + byteArrayOf(1)).copyOf(length)
	}

	private fun hmac(
		key: ByteArray,
		data: ByteArray,
	): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

	private fun unsigned(value: BigInteger): ByteArray {
		val bytes = value.toByteArray()
		return when {
			bytes.size == COORDINATE_BYTES -> bytes
			bytes.size > COORDINATE_BYTES -> bytes.copyOfRange(bytes.size - COORDINATE_BYTES, bytes.size)
			else -> ByteArray(COORDINATE_BYTES - bytes.size) + bytes
		}
	}

	private fun onCurve(point: ECPoint): Boolean {
		val curve = p256.curve
		val p = (curve.field as ECFieldFp).p
		val x = point.affineX
		val y = point.affineY
		if (x.signum() < 0 || x >= p || y.signum() < 0 || y >= p) return false
		return y.pow(2).mod(p) ==
			x
				.pow(3)
				.add(curve.a.multiply(x))
				.add(curve.b)
				.mod(p)
	}

	/** A JSON string literal; [value] comes from configuration and the subscription, so it is escaped. */
	private fun json(value: String): String =
		buildString {
			append('"')
			value.forEach { c ->
				when {
					c == '"' || c == '\\' -> append('\\').append(c)
					c < ' ' -> append("\\u%04x".format(c.code))
					else -> append(c)
				}
			}
			append('"')
		}
}
