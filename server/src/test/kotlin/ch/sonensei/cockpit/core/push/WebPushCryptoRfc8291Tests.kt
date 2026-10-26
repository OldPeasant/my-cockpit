package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.push.infra.WebPushCrypto
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.ECPrivateKeySpec
import javax.crypto.KeyAgreement

/**
 * Known-answer test of the message encryption against the test vector of RFC 8291 Appendix A, so the implementation is
 * pinned to an external reference and not only to [PushTestBrowser] (written from the same reading of the RFC).
 */
class WebPushCryptoRfc8291Tests {
	@Test
	fun `encrypting the RFC 8291 example gives exactly the RFC's message`() {
		val body =
			WebPushCrypto.encrypt(
				PLAINTEXT.toByteArray(Charsets.US_ASCII),
				WebPushCrypto.decode(UA_PUBLIC),
				WebPushCrypto.decode(AUTH_SECRET),
				WebPushCrypto.decode(SALT),
				keyPair(AS_PUBLIC, AS_PRIVATE),
			)

		WebPushCrypto.encode(body) shouldBe ENCRYPTED
	}

	@Test
	fun `the RFC 8291 example decrypts with the user agent's private key`() {
		val browser = PushTestBrowser(keyPair(UA_PUBLIC, UA_PRIVATE), WebPushCrypto.decode(AUTH_SECRET))

		String(browser.decrypt(WebPushCrypto.decode(ENCRYPTED)), Charsets.US_ASCII) shouldBe PLAINTEXT
	}

	@Test
	fun `both sides of the RFC 8291 example agree on its ECDH secret`() {
		ecdh(keyPair(AS_PUBLIC, AS_PRIVATE), UA_PUBLIC) shouldBe ECDH_SECRET
		ecdh(keyPair(UA_PUBLIC, UA_PRIVATE), AS_PUBLIC) shouldBe ECDH_SECRET
	}

	private fun ecdh(
		own: KeyPair,
		otherPublic: String,
	): String =
		WebPushCrypto.encode(
			KeyAgreement
				.getInstance("ECDH")
				.apply {
					init(own.private)
					doPhase(WebPushCrypto.publicKey(WebPushCrypto.decode(otherPublic)), true)
				}.generateSecret(),
		)

	/** A P-256 key pair from the RFC's raw values: uncompressed public point and private scalar, base64url. */
	private fun keyPair(
		publicKey: String,
		privateKey: String,
	): KeyPair {
		val public = WebPushCrypto.publicKey(WebPushCrypto.decode(publicKey))
		val private =
			KeyFactory
				.getInstance("EC")
				.generatePrivate(ECPrivateKeySpec(BigInteger(1, WebPushCrypto.decode(privateKey)), public.params))
		return KeyPair(public, private)
	}

	private companion object {
		// RFC 8291 Appendix A (record size 4096), values as printed there (base64url).
		const val PLAINTEXT = "When I grow up, I want to be a watermelon"
		const val AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"
		const val AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"
		const val UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
		const val UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94"
		const val SALT = "DGv6ra1nlYgDCS1FRnbzlw"
		const val AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg"
		const val ECDH_SECRET = "kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs"
		const val ENCRYPTED =
			"DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK" +
				"6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"
	}
}
