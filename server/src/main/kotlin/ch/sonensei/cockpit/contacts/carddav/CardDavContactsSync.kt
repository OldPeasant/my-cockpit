package ch.sonensei.cockpit.contacts.carddav

import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.contacts.domain.ContactChanges
import ch.sonensei.cockpit.contacts.domain.ContactService
import ch.sonensei.cockpit.contacts.domain.ContactSource
import ch.sonensei.cockpit.contacts.domain.VCardService
import ch.sonensei.cockpit.contacts.infra.ContactRepository
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Two-way sync of the contacts of each [CardDavAccount] with one address book of its CardDAV server (RFC 6352,
 * SPEC.md §8.3, source CARDDAV). The address book is the configured URL, or the first one found from it through
 * `current-user-principal` and `addressbook-home-set`.
 *
 * A run first pushes local changes, then pulls the server's:
 * - Push: for a contact whose version is above the version last synced, the card is read first. If its ETag is not
 *   the one last synced, it changed at the server and the server's version wins (it is applied). Otherwise the
 *   contact is written into that card ([CardDavMapping.merged]: only the changed field groups, unknown properties
 *   stay) with `If-Match`; a 412 (changed in between) also lets the server win. A contact in the Trash is deleted at
 *   the server, also after it was purged (its link stays as a tombstone until then); a contact created here
 *   ([createContact]) or restored from the Trash after its card was deleted is written with `If-None-Match: *`.
 * - Pull: a `sync-collection` REPORT (RFC 6578) with the token of the last run, so only changes come; removed cards
 *   move their contact to the Trash, and the changed ones are fetched with `addressbook-multiget`. Without a token, or
 *   when the server refuses it (403/409/400: invalid token), everything is listed, and contacts whose card is missing
 *   are moved to the Trash (not the ones this run's push just created). A server without sync-collection is listed
 *   with PROPFIND every run. A card with the ETag already stored is skipped (e.g. the echo of a push). A card back
 *   under the href of a contact trashed because its card was gone restores that contact.
 *
 * HTTP calls run outside transactions; each contact is stored in its own one, so one card that does not fit (it is
 * skipped and logged by href) does not stop the run. The run's error (no contact data) is kept in
 * [CardDavAccount.lastError].
 */
@Service
class CardDavContactsSync(
	private val accounts: CardDavAccountRepository,
	private val links: CardDavLinkRepository,
	private val lookup: CardDavContactLookup,
	private val contacts: ContactRepository,
	private val service: ContactService,
	vcards: VCardService,
	transactionManager: PlatformTransactionManager,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val mapping = CardDavMapping(vcards)
	private val tx = TransactionTemplate(transactionManager)
	private val running = ConcurrentHashMap<UUID, ReentrantLock>()

	/** Syncs every CardDAV account. */
	fun syncAll() {
		tx.execute { accounts.findAll().map { it.id } }!!.forEach { sync(it) }
	}

	/**
	 * One run for [accountId]; returns its state afterwards. A run already going on for the account is not waited for
	 * (its state is returned as it is).
	 *
	 * @throws NotFoundException when there is no such account
	 */
	fun sync(accountId: UUID): CardDavAccount {
		val lock = running.computeIfAbsent(accountId) { ReentrantLock() }
		if (!lock.tryLock()) return state(accountId)
		try {
			val account =
				tx.execute {
					val account = accounts.findById(accountId).orElseThrow { NotFoundException("CardDAV account not found") }
					account.lastAttemptAt = clock.instant()
					accounts.saveAndFlush(account)
				}!!
			val error =
				try {
					val run = Run(account)
					// Hrefs this run's push created: a full listing right after does not trash them when it lacks them.
					val created = mutableSetOf<String>()
					val skipped = run.push(created) + run.pull(created)
					if (skipped > 0) "$skipped contacts could not be synced with the CardDAV server" else null
				} catch (e: CardDavException) {
					log.warn("CardDAV contacts sync of account {} failed: {}", accountId, e.message)
					e.message
				}
			tx.executeWithoutResult {
				val current = accounts.findById(accountId).orElse(null) ?: return@executeWithoutResult
				current.lastError = error
				if (error == null) current.lastSuccessAt = clock.instant()
			}
			return state(accountId)
		} finally {
			lock.unlock()
		}
	}

	/**
	 * Creates a contact in the address book of [accountId]: a CARDDAV contact now, its card with the next push.
	 *
	 * @throws NotFoundException when there is no such account
	 * @throws CardDavException when the address book cannot be found (the first run of a new account looks it up)
	 */
	fun createContact(
		accountId: UUID,
		fields: ContactChanges,
	): Contact {
		val account = tx.execute { accounts.findById(accountId).orElse(null) } ?: throw NotFoundException("CardDAV account not found")
		val run = Run(account)
		val href = run.addressbook.resolve("${UuidV7.generate()}.vcf").rawPath
		return tx.execute {
			val contact = service.createSynced(ContactSource.CARDDAV, run.externalId(href), fields)
			links.saveAndFlush(CardDavLink(contact.id, accountId, href, etag = null, syncedVersion = 0))
			contact
		}!!
	}

	private fun state(accountId: UUID): CardDavAccount =
		tx.execute { accounts.findById(accountId).orElse(null) } ?: throw NotFoundException("CardDAV account not found")

	/** One run for [account]: the client and the address book. */
	private inner class Run(
		account: CardDavAccount,
	) {
		private val accountId = account.id
		private val client =
			CardDavClient(
				base = URI(account.url),
				username = account.username,
				password = account.password?.reveal(),
			)
		val addressbook: URI = asCollection(account.addressbookUrl?.let(client::resolve) ?: discover())

		/** The absolute URL of a card, the contact's external id (unique among CARDDAV contacts). */
		fun externalId(href: String): String = addressbook.resolve(href).toString()

		/** Finds the address book from the configured URL and stores it. */
		private fun discover(): URI {
			val what = "Finding the address book"
			val start = client.base
			val self = MultiStatus.parse(propfind(start, 0, DISCOVERY_PROPS, what).body, what).responses.firstOrNull()
			val found =
				if (self != null && isAddressbook(self)) {
					start
				} else {
					val home =
						self?.href(CardDavClient.CARDDAV, "addressbook-home-set")
							?: self?.href(CardDavClient.DAV, "current-user-principal")?.let { principal ->
								MultiStatus
									.parse(propfind(principal, 0, DISCOVERY_PROPS, what).body, what)
									.responses
									.firstOrNull()
									?.href(CardDavClient.CARDDAV, "addressbook-home-set")
							} ?: throw CardDavException("$what: the CardDAV server announced no address book home")
					MultiStatus
						.parse(propfind(home, 1, "<D:resourcetype/>", what).body, what)
						.responses
						.filter(::isAddressbook)
						.mapNotNull { client.resolve(it.href) }
						.minByOrNull { it.rawPath }
						?: throw CardDavException("$what: the CardDAV server has no address book")
				}
			tx.executeWithoutResult { accounts.findById(accountId).ifPresent { it.addressbookUrl = found.toString() } }
			return found
		}

		private fun propfind(
			uri: URI,
			depth: Int,
			props: String,
			what: String,
		): CardDavClient.Response {
			val response = client.propfind(uri, depth, props, what)
			if (response.status != MULTI_STATUS) throw CardDavException("$what failed (HTTP ${response.status})")
			return response
		}

		private fun isAddressbook(response: DavResponse): Boolean =
			response.prop(CardDavClient.DAV, "resourcetype")?.children(CardDavClient.CARDDAV, "addressbook")?.isNotEmpty() == true

		private fun DavResponse.href(
			namespace: String,
			name: String,
		): URI? =
			prop(namespace, name)
				?.children(CardDavClient.DAV, "href")
				?.firstOrNull()
				?.textContent
				?.let(client::resolve)

		/** The path of a card the server sent; `null` when it is not a card of the address book. */
		private fun cardPath(href: String): String? {
			val uri = client.resolve(href) ?: return null
			val path = uri.rawPath ?: return null
			val base = addressbook.rawPath.let { if (it.endsWith("/")) it else "$it/" }
			return path.takeIf { it.startsWith(base) && it.length > base.length && !it.endsWith("/") && "/../" !in it }
		}

		private fun cardUri(href: String): URI = addressbook.resolve(href)

		// --- push

		/** Writes local changes to the server, adding the hrefs it creates to [created]; returns how many failed. */
		fun push(created: MutableSet<String>): Int {
			val pending =
				tx.execute {
					links.findByAccountId(accountId).mapNotNull { link ->
						val contact = link.contactId?.let { contacts.findById(it).orElse(null) }
						val write =
							when {
								contact == null && link.etag != null -> Write.DELETE
								contact == null -> {
									// Purged after its card was gone at the server: nothing is left to do.
									links.delete(link)
									null
								}
								contact.isDeleted && link.etag != null -> Write.DELETE
								contact.isDeleted -> null
								link.etag == null -> Write.CREATE
								contact.version > link.syncedVersion -> Write.UPDATE
								else -> null
							}
						write?.let { Pending(it, contact, link) }
					}
				}!!
			return pending.count { item ->
				val ok =
					when (item.write) {
						Write.CREATE -> create(checkNotNull(item.contact), item.link, created)
						Write.UPDATE -> update(checkNotNull(item.contact), item.link)
						Write.DELETE -> delete(item.contact, item.link)
					}
				!ok
			}
		}

		private fun create(
			contact: Contact,
			link: CardDavLink,
			created: MutableSet<String>,
		): Boolean {
			val uri = cardUri(link.href)
			val response =
				client.send("PUT", uri, mapping.newCard(contact), CardDavClient.VCARD, mapOf("If-None-Match" to "*"), "Creating a contact")
			when {
				response.ok -> {
					markSynced(link, contact, etagAfterWrite(response, uri))
					created += link.href
				}
				response.status == PRECONDITION_FAILED -> {
					// A card already exists under this href: the server's version wins.
					val current = read(uri)
					if (!current.ok) return rejected(contact.id, current, "read")
					return serverWins(contact, link.href, current)
				}
				else -> return rejected(contact.id, response, "create")
			}
			return true
		}

		/**
		 * Writes the local change of [contact] into the card as the server has it, when the card is still the one last
		 * synced; otherwise (or when the server refuses the ETag as stale) the server's version wins.
		 */
		private fun update(
			contact: Contact,
			link: CardDavLink,
		): Boolean {
			val uri = cardUri(link.href)
			val current = read(uri)
			when {
				current.status == NOT_FOUND -> {
					removedAtServer(link.href)
					return true
				}
				!current.ok -> return rejected(contact.id, current, "read")
				current.header("ETag") != link.etag -> return serverWins(contact, link.href, current)
			}
			val card = mapping.parse(current.body) ?: return rejected(contact.id, current, "parse")
			val body = mapping.merged(card, contact)
			if (body == null) {
				markSynced(link, contact, link.etag)
				return true
			}
			val response = client.send("PUT", uri, body, CardDavClient.VCARD, mapOf("If-Match" to checkNotNull(link.etag)), "Changing a contact")
			when {
				response.ok -> markSynced(link, contact, etagAfterWrite(response, uri))
				response.status == NOT_FOUND -> removedAtServer(link.href)
				response.status == PRECONDITION_FAILED -> {
					val again = read(uri)
					when {
						again.ok -> return serverWins(contact, link.href, again)
						again.status == NOT_FOUND -> removedAtServer(link.href)
						else -> return rejected(contact.id, again, "read")
					}
				}
				else -> return rejected(contact.id, response, "change")
			}
			return true
		}

		private fun read(uri: URI): CardDavClient.Response = client.send("GET", uri, what = "Reading a contact")

		/** The ETag a write answered with, else the one the card has now (some servers send none on PUT). */
		private fun etagAfterWrite(
			response: CardDavClient.Response,
			uri: URI,
		): String? = response.header("ETag") ?: client.send("HEAD", uri, what = "Reading a contact").header("ETag")

		/** Changed at the server since the last sync, and here: the server's version wins. */
		private fun serverWins(
			contact: Contact,
			href: String,
			card: CardDavClient.Response,
		): Boolean {
			log.info("Contact {} changed at the CardDAV server and here; keeping the server's version", contact.id)
			return apply(href, card.header("ETag"), card.body, force = true)
		}

		/** Stores that [contact] (as pushed) is in sync with the card's [etag]. */
		private fun markSynced(
			link: CardDavLink,
			contact: Contact,
			etag: String?,
		) {
			tx.executeWithoutResult {
				val current = links.findById(link.id).orElse(null) ?: return@executeWithoutResult
				current.etag = etag
				current.syncedVersion = contact.version
			}
		}

		/** Deletes the card of a contact in the Trash, or of a purged one ([contact] `null`: the link is a tombstone). */
		private fun delete(
			contact: Contact?,
			link: CardDavLink,
		): Boolean {
			val response = client.send("DELETE", cardUri(link.href), what = "Deleting a contact")
			if (!response.ok && response.status != NOT_FOUND) return rejected(contact?.id ?: link.id, response, "delete")
			tx.executeWithoutResult {
				val current = links.findById(link.id).orElse(null) ?: return@executeWithoutResult
				if (contact == null) {
					links.delete(current)
				} else {
					current.etag = null
					current.syncedVersion = contact.version
				}
			}
			return true
		}

		/** [id]: the contact's, or the link's of a purged contact; never contact data. */
		private fun rejected(
			id: UUID,
			response: CardDavClient.Response,
			action: String,
		): Boolean {
			log.warn("The CardDAV server refused to {} contact {} (HTTP {})", action, id, response.status)
			return false
		}

		// --- pull

		/**
		 * Applies the server's changes since the last run (or everything); returns how many cards could not be stored.
		 * [created]: the hrefs this run's push created, which a full listing does not trash when it lacks them.
		 */
		fun pull(created: Set<String>): Int {
			val token = tx.execute { accounts.findById(accountId).orElse(null)?.syncToken }
			// Without a token, one REPORT: a second one without a token would be refused the same way.
			val listing = (if (token != null) syncCollection(token) else null) ?: syncCollection(null) ?: propfindListing()
			var failed = 0
			listing.removed.forEach(::removedAtServer)
			val stored = tx.execute { links.findByAccountId(accountId).associate { it.href to it.etag } }!!
			val changed = listing.cards.filter { (href, etag) -> etag == null || stored[href] != etag }.keys
			changed.chunked(MULTIGET_SIZE).forEach { failed += fetch(it) }
			if (listing.complete) removeMissing(listing.cards.keys + created)
			tx.executeWithoutResult { accounts.findById(accountId).ifPresent { it.syncToken = listing.token } }
			return failed
		}

		/** What a listing found: card paths with their ETags, removed ones, whether it lists every card, the new token. */
		private inner class Listing(
			val cards: Map<String, String?>,
			val removed: Set<String>,
			val complete: Boolean,
			val token: String?,
		)

		/**
		 * The changes since [token] (`null`: every card); `null` when the server refuses the token, or (without one)
		 * does not support sync-collection.
		 */
		private fun syncCollection(token: String?): Listing? {
			val what = "Listing the changed contacts"
			val cards = mutableMapOf<String, String?>()
			val removed = mutableSetOf<String>()
			var current = token
			repeat(MAX_SYNC_PAGES) {
				val body =
					"""<?xml version="1.0" encoding="utf-8"?><D:sync-collection xmlns:D="DAV:">""" +
						"<D:sync-token>${xmlEscape(current.orEmpty())}</D:sync-token><D:sync-level>1</D:sync-level>" +
						"<D:prop><D:getetag/></D:prop></D:sync-collection>"
				val response = client.report(addressbook, 1, body, what)
				if (response.status != MULTI_STATUS) {
					if (current != null && response.status in INVALID_TOKEN) {
						log.info("CardDAV sync token of account {} is no longer valid, listing all contacts", accountId)
						return null
					}
					if (current == null) return null
					throw CardDavException("$what failed (HTTP ${response.status})")
				}
				val result = MultiStatus.parse(response.body, what)
				var truncated = false
				for (entry in result.responses) {
					if (entry.status == INSUFFICIENT_STORAGE) {
						truncated = true
						continue
					}
					val path = cardPath(entry.href) ?: continue
					if (entry.status == NOT_FOUND) {
						removed += path
						cards -= path
					} else {
						cards[path] = entry.text(CardDavClient.DAV, "getetag")
						removed -= path
					}
				}
				current = result.syncToken
				if (!truncated) return Listing(cards, removed, complete = token == null, token = current)
			}
			throw CardDavException("$what: the CardDAV server sent too many pages")
		}

		/** Every card of the address book, for a server without sync-collection. */
		private fun propfindListing(): Listing {
			val what = "Listing the contacts"
			val result = MultiStatus.parse(propfind(addressbook, 1, "<D:resourcetype/><D:getetag/>", what).body, what)
			val cards =
				result.responses
					.filter { it.status == null || it.status in 200..299 }
					.mapNotNull { entry -> cardPath(entry.href)?.let { it to entry.text(CardDavClient.DAV, "getetag") } }
					.toMap()
			return Listing(cards, emptySet(), complete = true, token = null)
		}

		/** Fetches and stores the cards at [paths]; returns how many could not be stored. */
		private fun fetch(paths: List<String>): Int {
			val what = "Reading the changed contacts"
			val body =
				"""<?xml version="1.0" encoding="utf-8"?><CR:addressbook-multiget ${CardDavClient.NAMESPACES}>""" +
					"<D:prop><D:getetag/><CR:address-data/></D:prop>" +
					paths.joinToString("") { "<D:href>${xmlEscape(it)}</D:href>" } +
					"</CR:addressbook-multiget>"
			val response = client.report(addressbook, 1, body, what)
			if (response.status != MULTI_STATUS) throw CardDavException("$what failed (HTTP ${response.status})")
			var failed = 0
			for (entry in MultiStatus.parse(response.body, what).responses) {
				val path = cardPath(entry.href) ?: continue
				val data = entry.text(CardDavClient.CARDDAV, "address-data")
				when {
					entry.status == NOT_FOUND -> removedAtServer(path)
					data == null -> failed++
					!apply(path, entry.text(CardDavClient.DAV, "getetag"), data, force = false) -> failed++
				}
			}
			return failed
		}

		/**
		 * Stores one card from the server: creates or changes its contact. [force] applies it even when its ETag is the
		 * stored one. Returns false when it could not be stored.
		 */
		private fun apply(
			href: String,
			etag: String?,
			text: String,
			force: Boolean,
		): Boolean {
			val card = mapping.parse(text)
			if (card == null) {
				log.warn("CardDAV card {} of account {} is no vCard; skipped", href, accountId)
				return false
			}
			return try {
				tx.executeWithoutResult { store(href, etag, mapping.changes(card), force) }
				true
			} catch (e: RuntimeException) {
				// Usually data the contact validation refuses; the message may contain contact data.
				log.warn("CardDAV card {} of account {} could not be stored ({})", href, accountId, e.javaClass.simpleName)
				false
			}
		}

		private fun store(
			href: String,
			etag: String?,
			changes: ContactChanges,
			force: Boolean,
		) {
			val link = links.findByAccountIdAndHref(accountId, href)
			if (link == null) {
				// A contact restored from a backup (active, no link) is adopted. One in the Trash, or one linked to this
				// account (its card was deleted before), gives up the id and stays as it is; the card gets a new contact.
				// One of another account keeps it: the card cannot be stored (the id is unique).
				val externalId = externalId(href)
				val found = lookup.findBySourceAndExternalId(ContactSource.CARDDAV, externalId)
				val foundLink = found?.let { links.findByContactId(it.id) }
				val contact =
					if (found != null && !found.isDeleted && foundLink == null) {
						service.update(found.id, changes) {}
					} else {
						if (found != null && (found.isDeleted || foundLink?.accountId == accountId)) {
							lookup.updateExternalId(found.id, null)
						}
						service.createSynced(ContactSource.CARDDAV, externalId, changes)
					}
				links.saveAndFlush(CardDavLink(contact.id, accountId, href, etag, contact.version))
				return
			}
			// A tombstone: the contact was purged here, and the next push deletes the card.
			val contactId = link.contactId ?: return
			if (!force && etag != null && etag == link.etag) return
			val contact = contacts.findById(contactId).orElse(null) ?: return
			if (contact.isDeleted) {
				// In the Trash here while the server has the card: the next push deletes it (or, when it is restored,
				// writes it).
				if (link.etag != null) return
				// The card was gone at the server (or deleted by a push) and is back under the same href (undeleted or
				// imported again there): the contact comes back from the Trash, as a restore there does, with the card.
				contact.restore()
				contacts.saveAndFlush(contact)
			}
			val updated = service.update(contact.id, changes) {}
			link.etag = etag
			link.syncedVersion = updated.version
		}

		/**
		 * The card is gone at the server: its contact goes to the Trash, and a restore creates it there again. The
		 * tombstone of a purged contact is removed.
		 */
		private fun removedAtServer(href: String) {
			tx.executeWithoutResult {
				val link = links.findByAccountIdAndHref(accountId, href) ?: return@executeWithoutResult
				val contactId = link.contactId
				if (contactId == null) {
					links.delete(link)
					return@executeWithoutResult
				}
				// Not at the server yet: the next push creates it.
				if (link.etag == null) return@executeWithoutResult
				val contact = contacts.findById(contactId).orElse(null)
				link.etag = null
				links.saveAndFlush(link)
				if (contact != null && !contact.isDeleted) service.delete(contact.id) {}
				link.syncedVersion = contacts.findById(contactId).map { it.version }.orElse(link.syncedVersion)
			}
		}

		/** After a full listing: trashes the contacts whose card the server did not list. */
		private fun removeMissing(seen: Set<String>) {
			val missing = tx.execute { links.findByAccountId(accountId).filter { it.etag != null && it.href !in seen }.map { it.href } }!!
			missing.forEach(::removedAtServer)
		}
	}

	private enum class Write { CREATE, UPDATE, DELETE }

	/** A write for [link]; [contact] is `null` for a tombstone (the contact was purged). */
	private class Pending(
		val write: Write,
		val contact: Contact?,
		val link: CardDavLink,
	)

	companion object {
		private const val MULTI_STATUS = 207
		private const val NOT_FOUND = 404
		private const val PRECONDITION_FAILED = 412
		private const val INSUFFICIENT_STORAGE = 507
		private val INVALID_TOKEN = setOf(400, 403, 409)
		private const val MULTIGET_SIZE = 100
		private const val MAX_SYNC_PAGES = 100
		private const val DISCOVERY_PROPS =
			"<D:resourcetype/><D:current-user-principal/><CR:addressbook-home-set/>"

		/** [uri] with a trailing `/`, so card names resolve inside it. */
		private fun asCollection(uri: URI): URI = if (uri.rawPath.endsWith("/")) uri else URI("$uri/")

		private fun xmlEscape(text: String): String =
			text
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
	}
}
