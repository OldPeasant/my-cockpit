package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.push.domain.WebPushEncryption
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A device's Web Push keys for tests, and the device side of RFC 8291 to decrypt what the server sent. */
object PushTestKeys {
	private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(WebPushEncryption.P256) }.generateKeyPair()
	private val authBytes = ByteArray(16) { it.toByte() }

	val P256DH: String = encode(WebPushEncryption.encodePoint(pair.public as ECPublicKey))
	val AUTH: String = encode(authBytes)

	/** Decrypts an `aes128gcm` push body (one record) for these keys. */
	fun decrypt(body: ByteArray): String {
		val buffer = ByteBuffer.wrap(body)
		val salt = ByteArray(16).also(buffer::get)
		buffer.getInt()
		val asPublic = ByteArray(buffer.get().toInt()).also(buffer::get)
		val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
		val ecdh =
			KeyAgreement.getInstance("ECDH").run {
				init(pair.private)
				doPhase(WebPushEncryption.decodePoint(asPublic), true)
				generateSecret()
			}
		val uaPublic = WebPushEncryption.encodePoint(pair.public as ECPublicKey)
		val ikm = hmac(hmac(authBytes, ecdh), "WebPush: info".toByteArray() + 0 + uaPublic + asPublic + 1)
		val prk = hmac(salt, ikm)
		val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + 0 + 1).copyOf(16)
		val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + 0 + 1).copyOf(12)
		val plain =
			Cipher.getInstance("AES/GCM/NoPadding").run {
				init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
				doFinal(ciphertext)
			}
		check(plain.last() == 0x02.toByte()) { "not the last record" }
		return plain.copyOf(plain.size - 1).decodeToString()
	}

	private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

	private fun hmac(
		key: ByteArray,
		data: ByteArray,
	) = Mac.getInstance("HmacSHA256").run {
		init(SecretKeySpec(key, "HmacSHA256"))
		doFinal(data)
	}

	private operator fun ByteArray.plus(byte: Int): ByteArray = this + byte.toByte()
}
