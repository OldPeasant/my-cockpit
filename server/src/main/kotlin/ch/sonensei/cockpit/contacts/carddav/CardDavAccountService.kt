package ch.sonensei.cockpit.contacts.carddav

import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID

/**
 * The CardDAV accounts whose contacts are synced ([CardDavContactsSync]). Removing an account forgets its sync state
 * and links; its contacts stay (no longer synced).
 */
@Service
@Transactional
class CardDavAccountService(
	private val accounts: CardDavAccountRepository,
) {
	@Transactional(readOnly = true)
	fun list(): List<CardDavAccount> = accounts.findAll().sortedBy { it.id }

	/**
	 * Adds an account. [url] is an address book, or the server (or principal) URL to find the first address book from.
	 *
	 * @throws InvalidRequestException (400) when [url] is no absolute http(s) URL or [name] is blank
	 */
	fun add(
		name: String,
		url: String,
		username: String?,
		password: String?,
	): CardDavAccount {
		if (name.isBlank()) throw InvalidRequestException("name", "must not be blank")
		val uri =
			try {
				URI(url.trim())
			} catch (e: URISyntaxException) {
				null
			}
		if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https") || uri.host == null || uri.userInfo != null) {
			throw InvalidRequestException("url", "must be an http or https URL without credentials")
		}
		return accounts.saveAndFlush(
			CardDavAccount(name.trim(), uri.toString(), username?.ifBlank { null }, password?.let(::EncryptedSecret)),
		)
	}

	/** @throws NotFoundException when there is no such account */
	@Transactional(readOnly = true)
	fun get(id: UUID): CardDavAccount = accounts.findById(id).orElseThrow { NotFoundException("CardDAV account not found") }

	/** @throws NotFoundException when there is no such account */
	fun remove(id: UUID) {
		accounts.delete(get(id))
	}
}
