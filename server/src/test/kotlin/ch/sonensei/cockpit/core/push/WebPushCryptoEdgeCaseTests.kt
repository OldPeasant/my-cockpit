package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.push.domain.WebPushEncryption
import ch.sonensei.cockpit.core.push.domain.WebPushKeys
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

/**
 * Web Push crypto against the published test vector of RFC 8291 Appendix A and at its edges: key encodings, size
 * limits and the escaping of VAPID claims.
 */
class WebPushCryptoEdgeCaseTests {
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

		fun encrypt(
			publicKey: ByteArray,
			authSecret: ByteArray,
		) = WebPushEncryption.encrypt(message, WebPushKeys(WebPushCrypto.encode(publicKey), WebPushCrypto.encode(authSecret)))

		shouldThrow<IllegalArgumentException> { encrypt(browser.publicKey, ByteArray(15)) }
		shouldThrow<IllegalArgumentException> { encrypt(browser.publicKey, ByteArray(17)) }
		shouldThrow<IllegalArgumentException> { encrypt(ByteArray(65), browser.authSecret) }
		shouldThrow<IllegalArgumentException> { encrypt(byteArrayOf(4) + ByteArray(64), browser.authSecret) }
	}

	@Test
	fun `one record carries at most 4079 bytes of plaintext`() {
		val browser = PushTestBrowser()
		// record size 4096 = plaintext + 1 delimiter byte + 16 tag bytes
		val largest = ByteArray(4079) { 'a'.code.toByte() }

		val keys = WebPushKeys(browser.p256dh, browser.auth)

		browser.decrypt(WebPushEncryption.encrypt(largest, keys)) shouldBe largest
		shouldThrow<IllegalArgumentException> { WebPushEncryption.encrypt(ByteArray(4080), keys) }
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
		const val UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
		const val UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"
		const val AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg"
		const val HEADER =
			"DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"
		const val CIPHERTEXT = "8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ"
	}
}
