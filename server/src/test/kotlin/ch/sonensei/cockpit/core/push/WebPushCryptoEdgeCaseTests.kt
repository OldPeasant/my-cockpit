package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.push.infra.WebPushCrypto
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.net.URI
import java.security.KeyFactory
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPrivateKeySpec
import java.time.Instant
import java.util.HexFormat
import javax.crypto.KeyAgreement

/**
 * Web Push crypto against published test vectors (RFC 5869, RFC 8291 Appendix A) and at its edges: key encodings,
 * size limits and the escaping of VAPID claims.
 */
class WebPushCryptoEdgeCaseTests {
	private fun hex(value: String): ByteArray = HexFormat.of().parseHex(value)

	private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

	@Test
	fun `HKDF matches the RFC 5869 test case 1 for one block of output`() {
		val okm =
			WebPushCrypto.hkdf(
				salt = hex("000102030405060708090a0b0c"),
				ikm = hex("0b".repeat(22)),
				info = hex("f0f1f2f3f4f5f6f7f8f9"),
				length = 32,
			)

		okm shouldBe hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf")
	}

	@Test
	fun `the key derivation matches the RFC 8291 Appendix A example`() {
		val uaPublic = WebPushCrypto.decode(Rfc8291.UA_PUBLIC)
		val asPublic = WebPushCrypto.decode(Rfc8291.AS_PUBLIC)
		val ecdh =
			KeyAgreement
				.getInstance("ECDH")
				.apply {
					init(privateKey(Rfc8291.AS_PRIVATE))
					doPhase(WebPushCrypto.publicKey(uaPublic), true)
				}.generateSecret()
		ecdh shouldBe WebPushCrypto.decode(Rfc8291.ECDH_SECRET)

		val keyInfo = ascii("WebPush: info") + byteArrayOf(0) + uaPublic + asPublic
		val ikm = WebPushCrypto.hkdf(WebPushCrypto.decode(Rfc8291.AUTH_SECRET), ecdh, keyInfo, 32)
		ikm shouldBe WebPushCrypto.decode(Rfc8291.IKM)
		val salt = WebPushCrypto.decode(Rfc8291.SALT)
		WebPushCrypto.hkdf(salt, ikm, ascii("Content-Encoding: aes128gcm") + byteArrayOf(0), 16) shouldBe
			WebPushCrypto.decode(Rfc8291.CEK)
		WebPushCrypto.hkdf(salt, ikm, ascii("Content-Encoding: nonce") + byteArrayOf(0), 12) shouldBe
			WebPushCrypto.decode(Rfc8291.NONCE)
	}

	@Test
	fun `the test browser decrypts the RFC 8291 example, so the round-trip tests check the RFC format`() {
		val uaPrivate = privateKey(Rfc8291.UA_PRIVATE)
		val uaPublic = WebPushCrypto.publicKey(WebPushCrypto.decode(Rfc8291.UA_PUBLIC))
		val browser = PushTestBrowser(KeyPair(uaPublic, uaPrivate), WebPushCrypto.decode(Rfc8291.AUTH_SECRET))

		val body = WebPushCrypto.decode(Rfc8291.HEADER) + WebPushCrypto.decode(Rfc8291.CIPHERTEXT)

		String(browser.decrypt(body)) shouldBe "When I grow up, I want to be a watermelon"
	}

	@Test
	fun `encryption rejects an auth secret of the wrong size and an invalid browser key`() {
		val browser = PushTestBrowser()
		val message = "x".toByteArray()

		shouldThrow<IllegalArgumentException> { WebPushCrypto.encrypt(message, browser.publicKey, ByteArray(15)) }
		shouldThrow<IllegalArgumentException> { WebPushCrypto.encrypt(message, browser.publicKey, ByteArray(17)) }
		shouldThrow<IllegalArgumentException> { WebPushCrypto.encrypt(message, ByteArray(65), browser.authSecret) }
	}

	@Test
	fun `one record carries at most 4079 bytes of plaintext`() {
		val browser = PushTestBrowser()
		// record size 4096 = plaintext + 1 delimiter byte + 16 tag bytes
		val largest = ByteArray(4079) { 'a'.code.toByte() }

		browser.decrypt(WebPushCrypto.encrypt(largest, browser.publicKey, browser.authSecret)) shouldBe largest
		shouldThrow<IllegalArgumentException> {
			WebPushCrypto.encrypt(ByteArray(4080), browser.publicKey, browser.authSecret)
		}
	}

	@Test
	fun `public keys must be uncompressed points with coordinates inside the field`() {
		val raw = WebPushCrypto.rawPublicKey(WebPushCrypto.generateKeyPair().public as ECPublicKey)

		shouldThrow<IllegalArgumentException> { WebPushCrypto.publicKey(raw.copyOf().also { it[0] = 0x02 }) }
		shouldThrow<IllegalArgumentException> { WebPushCrypto.publicKey(raw + byteArrayOf(0)) }
		shouldThrow<IllegalArgumentException> { WebPushCrypto.publicKey(ByteArray(0)) }
		// x = y = 2^256 - 1 is larger than the field prime p
		shouldThrow<IllegalArgumentException> { WebPushCrypto.publicKey(byteArrayOf(4) + ByteArray(64) { 0xff.toByte() }) }
	}

	@Test
	fun `raw public keys keep 32-byte coordinates also when a coordinate has leading zero bytes`() {
		val pair =
			generateSequence { WebPushCrypto.generateKeyPair() }
				.take(5000)
				.first { candidate ->
					// A coordinate whose two's-complement form is shorter than 32 bytes, so it needs left-padding.
					val point = (candidate.public as ECPublicKey).w
					point.affineX.toByteArray().size < 32 || point.affineY.toByteArray().size < 32
				}

		val raw = WebPushCrypto.rawPublicKey(pair.public as ECPublicKey)

		raw.size shouldBe WebPushCrypto.PUBLIC_KEY_BYTES
		WebPushCrypto.publicKey(raw) shouldBe pair.public
	}

	@Test
	fun `decoding tolerates surrounding whitespace and padding, and rejects other characters`() {
		WebPushCrypto.decode("  -_8=  ") shouldBe byteArrayOf(-5, -1)
		WebPushCrypto.decode("+/8=") shouldBe byteArrayOf(-5, -1)
		shouldThrow<IllegalArgumentException> { WebPushCrypto.decode("not base64 !") }
	}

	@Test
	fun `the VAPID audience leaves out the path and a default port, and claim values are JSON-escaped`() {
		val pair = WebPushCrypto.generateKeyPair()
		val publicKey = WebPushCrypto.rawPublicKey(pair.public as ECPublicKey)
		val expiresAt = Instant.parse("2026-10-26T12:00:00Z")
		val subject = "mailto:\"owner\"\\\n@example.test"

		val header =
			WebPushCrypto.vapidAuthorization(
				URI("https://fcm.example.test/fcm/send/fictitious"),
				subject,
				expiresAt,
				pair.private,
				publicKey,
			)

		val claims = header.removePrefix("vapid t=").substringBefore(", k=").split(".")[1]
		val expected =
			"{\"aud\":\"https://fcm.example.test\",\"exp\":${expiresAt.epochSecond}," +
				"\"sub\":\"mailto:\\\"owner\\\"\\\\\\u000a@example.test\"}"
		String(WebPushCrypto.decode(claims)) shouldBe expected
	}

	private fun privateKey(base64Url: String) =
		KeyFactory.getInstance("EC").generatePrivate(
			ECPrivateKeySpec(
				BigInteger(1, WebPushCrypto.decode(base64Url)),
				(WebPushCrypto.generateKeyPair().public as ECPublicKey).params,
			),
		)

	/** The example of RFC 8291 Appendix A. */
	private object Rfc8291 {
		const val AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"
		const val AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"
		const val UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
		const val UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"
		const val AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg"
		const val SALT = "DGv6ra1nlYgDCS1FRnbzlw"
		const val ECDH_SECRET = "kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs"
		const val IKM = "S4lYMb_L0FxCeq0WhDx813KgSYqU26kOyzWUdsXYyrg"
		const val CEK = "oIhVW04MRdy2XN9CiKLxTg"
		const val NONCE = "4h_95klXJ5E_qnoN"
		const val HEADER =
			"DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"
		const val CIPHERTEXT = "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ"
	}
}
