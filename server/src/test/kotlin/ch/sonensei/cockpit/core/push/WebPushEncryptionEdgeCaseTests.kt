package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.push.domain.WebPushEncryption
import ch.sonensei.cockpit.core.push.domain.WebPushKeys
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey

/** Limits of the one-record `aes128gcm` encryption and the fixed-size point encoding. */
class WebPushEncryptionEdgeCaseTests {
	private val keys = WebPushKeys(PushTestKeys.P256DH, PushTestKeys.AUTH)
	private val salt = ByteArray(16) { (it * 7).toByte() }

	@Test
	fun `a message filling exactly one record is encrypted, one byte more is rejected`() {
		// A record holds the plaintext, the 0x02 delimiter and the 16-byte tag: 4096 - 17 = 4079 bytes.
		val fits = ByteArray(4079) { 'a'.code.toByte() }

		PushTestKeys.decrypt(WebPushEncryption.encrypt(fits, keys)) shouldBe fits.decodeToString()
		shouldThrow<IllegalArgumentException> { WebPushEncryption.encrypt(ByteArray(4080), keys) }
	}

	@Test
	fun `an empty message is encrypted and decrypts to an empty string`() {
		PushTestKeys.decrypt(WebPushEncryption.encrypt(ByteArray(0), keys)) shouldBe ""
	}

	@Test
	fun `a server key with a short coordinate is still encoded as a 65-byte point and decrypts`() {
		val generator = KeyPairGenerator.getInstance("EC").apply { initialize(WebPushEncryption.P256) }
		// About 1 in 128 keys has a coordinate below 2^247 (so it encodes to fewer than 32 bytes); try until one does.
		val short: KeyPair =
			generateSequence { generator.generateKeyPair() }
				.take(20_000)
				.first { pair ->
					val point = (pair.public as ECPublicKey).w
					point.affineX.bitLength() <= 247 || point.affineY.bitLength() <= 247
				}

		val encoded = WebPushEncryption.encodePoint(short.public as ECPublicKey)
		encoded.size shouldBe 65
		WebPushEncryption.decodePoint(encoded).w shouldBe (short.public as ECPublicKey).w

		val body = WebPushEncryption.encrypt("""{"kind":"mail"}""".toByteArray(), keys, salt, short)
		body[20].toInt() shouldBe 65
		PushTestKeys.decrypt(body) shouldBe """{"kind":"mail"}"""
	}

	@Test
	fun `keys never show their values in toString`() {
		keys.toString() shouldNotContain PushTestKeys.P256DH
		keys.toString() shouldNotContain PushTestKeys.AUTH
	}
}
