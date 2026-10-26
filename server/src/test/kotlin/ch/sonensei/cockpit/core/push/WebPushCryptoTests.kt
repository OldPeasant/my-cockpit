package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.push.infra.WebPushCrypto
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.net.URI
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.Base64

/** Message encryption (RFC 8291) and VAPID (RFC 8292) without Spring. */
class WebPushCryptoTests {
	@Test
	fun `a browser decrypts what the server encrypted for it`() {
		val browser = PushTestBrowser()
		val message = """{"event":"reminder","data":{"title":"Call plumber"}}""".toByteArray()

		val body = WebPushCrypto.encrypt(message, browser.publicKey, browser.authSecret)

		browser.decrypt(body) shouldBe message
		// salt (16) | record size (4) | key id length (1) | key (65) | ciphertext (message + delimiter + tag)
		body.size shouldBe 16 + 4 + 1 + 65 + message.size + 1 + 16
	}

	@Test
	fun `every message uses a fresh salt and ephemeral key`() {
		val browser = PushTestBrowser()
		val message = "same".toByteArray()

		val first = WebPushCrypto.encrypt(message, browser.publicKey, browser.authSecret)
		val second = WebPushCrypto.encrypt(message, browser.publicKey, browser.authSecret)

		first.copyOf(86).contentEquals(second.copyOf(86)) shouldBe false
	}

	@Test
	fun `public keys round-trip and points off the curve are rejected`() {
		val pair = WebPushCrypto.generateKeyPair()
		val raw = WebPushCrypto.rawPublicKey(pair.public as ECPublicKey)

		raw.size shouldBe 65
		WebPushCrypto.publicKey(raw) shouldBe pair.public
		shouldThrow<IllegalArgumentException> { WebPushCrypto.publicKey(raw.copyOf().also { it[64] = (it[64] + 1).toByte() }) }
		shouldThrow<IllegalArgumentException> { WebPushCrypto.publicKey(raw.copyOf(33)) }
	}

	@Test
	fun `base64url decoding accepts padding and standard alphabet`() {
		val bytes = byteArrayOf(-5, -1, 0x10)
		WebPushCrypto.decode(WebPushCrypto.encode(bytes)) shouldBe bytes
		WebPushCrypto.decode(Base64.getEncoder().encodeToString(bytes)) shouldBe bytes
	}

	@Test
	fun `the VAPID header carries a token for the endpoint origin signed with the server key`() {
		val pair = WebPushCrypto.generateKeyPair()
		val publicKey = WebPushCrypto.rawPublicKey(pair.public as ECPublicKey)
		val expiresAt = Instant.parse("2026-10-26T12:00:00Z")

		val header =
			WebPushCrypto.vapidAuthorization(
				URI("https://push.example.test:8443/send/abc?x=1"),
				"mailto:owner@example.test",
				expiresAt,
				pair.private,
				publicKey,
			)

		header shouldStartWith "vapid t="
		val (token, key) = header.removePrefix("vapid t=").split(", k=")
		WebPushCrypto.decode(key) shouldBe publicKey
		val (h, c, s) = token.split(".")
		String(WebPushCrypto.decode(h)) shouldBe """{"typ":"JWT","alg":"ES256"}"""
		String(WebPushCrypto.decode(c)) shouldBe
			"""{"aud":"https://push.example.test:8443","exp":${expiresAt.epochSecond},"sub":"mailto:owner@example.test"}"""
		val verifier =
			Signature.getInstance("SHA256withECDSAinP1363Format").apply {
				initVerify(pair.public)
				update("$h.$c".toByteArray())
			}
		verifier.verify(WebPushCrypto.decode(s)) shouldBe true
	}
}
