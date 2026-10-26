package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.push.domain.WebPushEncryption
import ch.sonensei.cockpit.core.push.domain.WebPushKeys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.ECPrivateKeySpec
import java.util.Base64

/** RFC 8291 message encryption, checked against the RFC's example (Appendix A). */
class WebPushEncryptionTests {
	private fun b64(value: String) = Base64.getUrlDecoder().decode(value)

	private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

	private val uaPublic = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
	private val authSecret = "BTBZMqHH6r4Tts7J_aSIgg"

	private fun serverKeys(): KeyPair {
		val public = WebPushEncryption.decodePoint(b64("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"))
		val private =
			KeyFactory
				.getInstance("EC")
				.generatePrivate(ECPrivateKeySpec(BigInteger(1, b64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")), WebPushEncryption.P256))
		return KeyPair(public, private)
	}

	@Test
	fun `encrypts the RFC 8291 example message exactly as the RFC does`() {
		val body =
			WebPushEncryption.encrypt(
				"When I grow up, I want to be a watermelon".toByteArray(),
				WebPushKeys(uaPublic, authSecret),
				b64("DGv6ra1nlYgDCS1FRnbzlw"),
				serverKeys(),
			)

		b64(body) shouldBe
			"DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl" +
			"7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"
	}

	@Test
	fun `every message gets a fresh salt and server key`() {
		val keys = WebPushKeys(uaPublic, authSecret)
		val first = WebPushEncryption.encrypt("x".toByteArray(), keys)
		val second = WebPushEncryption.encrypt("x".toByteArray(), keys)

		first.copyOf(86).contentEquals(second.copyOf(86)) shouldBe false
		first.size shouldNotBe 0
	}

	@Test
	fun `a public key that is not on the curve is rejected`() {
		val offCurve = b64(uaPublic).also { it[64] = (it[64] + 1).toByte() }

		shouldThrow<IllegalArgumentException> {
			WebPushEncryption.encrypt("x".toByteArray(), WebPushKeys(b64(offCurve), authSecret))
		}
	}
}
