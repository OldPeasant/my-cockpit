package ch.sonensei.cockpit.core.push.domain

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.push.infra.VapidKeyRepository
import ch.sonensei.cockpit.core.push.infra.WebPushCrypto
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.util.Base64

/** The VAPID key pair in usable form: [publicKey] is the raw uncompressed point. */
class VapidKeyPair(
	val publicKey: ByteArray,
	val privateKey: PrivateKey,
) {
	/** The public key as unpadded base64url, the browser's `applicationServerKey`. */
	val publicKeyBase64Url: String get() = WebPushCrypto.encode(publicKey)

	override fun toString(): String = "VapidKeyPair($publicKeyBase64Url)"
}

/**
 * The server's VAPID key pair (SPEC.md §4.6): generated at first start and kept in the database, the private key
 * encrypted with the master key. A restore empties the table; the next use then generates a new pair, and browsers
 * subscribe again with it.
 */
@Service
class VapidKeyService(
	private val keys: VapidKeyRepository,
	transactions: PlatformTransactionManager,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val tx = TransactionTemplate(transactions)
	private val newTx =
		TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

	/** Push is optional: a key pair that can't be read (e.g. a different master key) must not stop the server. */
	@EventListener(ApplicationReadyEvent::class)
	fun generateAtStartup() {
		try {
			current()
		} catch (e: RuntimeException) {
			log.warn("The VAPID key pair for Web Push is not usable, push messages will fail: {}", e.toString())
		}
	}

	/** The key pair, generated on first use. Two instances generating at once agree on the one stored first. */
	fun current(): VapidKeyPair {
		val stored = tx.execute { keys.findFirstBy() } ?: generate()
		return try {
			VapidKeyPair(
				WebPushCrypto.decode(stored.publicKey),
				WebPushCrypto.privateKey(Base64.getDecoder().decode(stored.privateKey.reveal())),
			)
		} catch (e: GeneralSecurityException) {
			throw IllegalStateException("The stored VAPID private key is not a valid key", e)
		}
	}

	private fun generate(): VapidKey {
		val pair = WebPushCrypto.generateKeyPair()
		val key =
			VapidKey(
				WebPushCrypto.encode(WebPushCrypto.rawPublicKey(pair.public as ECPublicKey)),
				EncryptedSecret(Base64.getEncoder().encodeToString(pair.private.encoded)),
			)
		return try {
			newTx.execute { keys.saveAndFlush(key) }!!.also { log.info("Generated the VAPID key pair for Web Push") }
		} catch (e: DataIntegrityViolationException) {
			log.debug("Another instance generated the VAPID key pair first: {}", e.toString())
			tx.execute { keys.findFirstBy() } ?: throw e
		}
	}
}
