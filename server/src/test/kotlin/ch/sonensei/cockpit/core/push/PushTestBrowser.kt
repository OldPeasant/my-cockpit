package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.push.infra.WebPushCrypto
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A browser's side of Web Push in tests: its subscription keys, and decrypting what the server sent (RFC 8291 from
 * the user agent's view, written out step by step instead of reusing the server's helpers).
 */
class PushTestBrowser {
	private val keyPair: KeyPair = WebPushCrypto.generateKeyPair()
	val authSecret: ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)
	val publicKey: ByteArray = WebPushCrypto.rawPublicKey(keyPair.public as ECPublicKey)

	val p256dh: String get() = WebPushCrypto.encode(publicKey)
	val auth: String get() = WebPushCrypto.encode(authSecret)

	fun decrypt(body: ByteArray): ByteArray {
		val buffer = ByteBuffer.wrap(body)
		val salt = ByteArray(16).also(buffer::get)
		buffer.int // record size
		val idLength = buffer.get().toInt()
		val serverPublic = ByteArray(idLength).also(buffer::get)
		val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)

		val ecdhSecret =
			KeyAgreement
				.getInstance("ECDH")
				.apply {
					init(keyPair.private)
					doPhase(WebPushCrypto.publicKey(serverPublic), true)
				}.generateSecret()
		val prkKey = hmac(authSecret, ecdhSecret)
		val keyInfo = "WebPush: info\u0000".toByteArray() + publicKey + serverPublic
		val ikm = hmac(prkKey, keyInfo + byteArrayOf(1))
		val prk = hmac(salt, ikm)
		val cek = hmac(prk, "Content-Encoding: aes128gcm\u0000".toByteArray() + byteArrayOf(1)).copyOf(16)
		val nonce = hmac(prk, "Content-Encoding: nonce\u0000".toByteArray() + byteArrayOf(1)).copyOf(12)
		val padded =
			Cipher
				.getInstance("AES/GCM/NoPadding")
				.apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce)) }
				.doFinal(ciphertext)
		val end = padded.indexOfLast { it != 0.toByte() }
		check(padded[end] == 2.toByte()) { "last record must end with the 0x02 delimiter" }
		return padded.copyOf(end)
	}

	private fun hmac(
		key: ByteArray,
		data: ByteArray,
	): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
