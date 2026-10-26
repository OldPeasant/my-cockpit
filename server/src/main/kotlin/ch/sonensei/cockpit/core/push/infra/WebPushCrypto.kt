package ch.sonensei.cockpit.core.push.infra

import java.math.BigInteger
import java.net.URI
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

/**
 * The cryptography of Web Push with the JDK's providers only: P-256 key pairs and the VAPID authorization header
 * (RFC 8292). Keys travel as unpadded base64url, like the Push API in the browser hands them out. Messages are
 * encrypted with [ch.sonensei.cockpit.core.push.domain.WebPushEncryption].
 */
object WebPushCrypto {
	/** Length of an uncompressed P-256 point: `0x04 | X | Y`. */
	const val PUBLIC_KEY_BYTES = 65
	const val AUTH_SECRET_BYTES = 16
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
