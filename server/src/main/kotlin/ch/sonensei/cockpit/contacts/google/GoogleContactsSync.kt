package ch.sonensei.cockpit.contacts.google

import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.contacts.domain.ContactService
import ch.sonensei.cockpit.contacts.domain.ContactSource
import ch.sonensei.cockpit.contacts.infra.ContactRepository
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionDisconnected
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionService
import ch.sonensei.cockpit.core.oauth.domain.OAuthConnectionStatus
import ch.sonensei.cockpit.core.oauth.domain.OAuthProvider
import ch.sonensei.cockpit.core.oauth.domain.OAuthTokenProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.event.EventListener
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Two-way sync of the contacts of each Google OAuth connection with the People API (SPEC.md §8.3).
 *
 * A run first pushes local changes, then pulls Google's:
 * - Push: for a GOOGLE contact whose version is above the version last synced, the person is read first. If its etag
 *   is not the one last synced, it changed there and Google's version wins (it is applied). Otherwise only the field
 *   groups the contact changed are written with `people.updateContact`, carrying the etag, so Google data the
 *   cockpit does not model stays; Google refusing a stale etag (`FAILED_PRECONDITION`, 412) also lets Google win. A
 *   contact in the Trash is deleted at Google, also after it was purged (its link stays as a tombstone until then); a
 *   contact whose person is gone (e.g. restored from the Trash) is created again with `people.createContact`.
 * - Pull: `people.connections.list` with the sync token of the last run (`requestSyncToken`), so only changes come;
 *   deleted persons (`metadata.deleted`) move their contact to the Trash. Without a token, or when Google answers that
 *   it expired (`EXPIRED_SYNC_TOKEN`, HTTP 400/410), everything is listed and contacts whose person is missing are
 *   moved to the Trash. A listed person with the etag already stored is skipped (e.g. the echo of a push).
 *
 * HTTP calls run outside transactions; each contact is stored in its own one, so one contact Google sends that does
 * not fit (it is skipped and logged by id) does not stop the run. The run's error (no contact data) is kept in
 * [GoogleContactsAccount.lastError].
 */
@Service
class GoogleContactsSync(
	tokens: OAuthTokenProvider,
	private val json: JsonMapper,
	private val connections: OAuthConnectionService,
	private val accounts: GoogleContactsAccountRepository,
	private val links: GoogleContactLinkRepository,
	private val lookup: GoogleContactLookup,
	private val contacts: ContactRepository,
	private val service: ContactService,
	transactionManager: PlatformTransactionManager,
	private val clock: Clock,
	@Value("\${cockpit.contacts.google.api-url:${GoogleContactsClient.DEFAULT_API_URL}}") apiUrl: String,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val client = GoogleContactsClient(apiUrl, tokens, json)
	private val tx = TransactionTemplate(transactionManager)
	private val running = ConcurrentHashMap<UUID, ReentrantLock>()

	/** Syncs every connected Google account that granted the contacts scope. */
	fun syncAll() {
		connections
			.list()
			.filter { it.provider == OAuthProvider.GOOGLE && it.status == OAuthConnectionStatus.CONNECTED && CONTACTS_SCOPE in it.scopes }
			.forEach { sync(it.id) }
	}

	/**
	 * One run for [connectionId]; returns its state afterwards. A run already going on for the connection is not
	 * waited for (its state is returned as it is).
	 */
	fun sync(connectionId: UUID): GoogleContactsAccount {
		val lock = running.computeIfAbsent(connectionId) { ReentrantLock() }
		if (!lock.tryLock()) return state(connectionId)
		try {
			tx.executeWithoutResult {
				val account = accounts.findById(connectionId).orElseGet { GoogleContactsAccount(connectionId) }
				account.lastAttemptAt = clock.instant()
				accounts.saveAndFlush(account)
			}
			val error =
				try {
					val skipped = push(connectionId) + pull(connectionId)
					if (skipped > 0) "$skipped contacts could not be synced with Google" else null
				} catch (e: GoogleContactsException) {
					log.warn("Google contacts sync of connection {} failed: {}", connectionId, e.message)
					e.message
				}
			tx.executeWithoutResult {
				val account = accounts.findById(connectionId).orElse(null) ?: return@executeWithoutResult
				account.lastError = error
				if (error == null) account.lastSuccessAt = clock.instant()
			}
			return state(connectionId)
		} finally {
			lock.unlock()
		}
	}

	/** Forgets the sync state and links of a removed connection; its contacts stay (no longer synced). */
	@EventListener
	fun onDisconnected(event: OAuthConnectionDisconnected) {
		if (event.provider != OAuthProvider.GOOGLE) return
		links.deleteAllInBatch(links.findByConnectionId(event.connectionId))
		accounts.findById(event.connectionId).ifPresent { accounts.delete(it) }
	}

	private fun state(connectionId: UUID): GoogleContactsAccount =
		tx.execute { accounts.findById(connectionId).orElse(null) } ?: GoogleContactsAccount(connectionId)

	// --- push

	private enum class Write { CREATE, UPDATE, DELETE, DELETE_PURGED }

	/** A write for [link]; [contact] is `null` for a tombstone (the contact was purged). */
	private class Pending(
		val write: Write,
		val contact: Contact?,
		val link: GoogleContactLink,
	)

	/** Writes local changes to Google; returns how many could not be written. */
	private fun push(connectionId: UUID): Int {
		val pending =
			tx.execute {
				links.findByConnectionId(connectionId).mapNotNull { link ->
					val contact = link.contactId?.let { contacts.findById(it).orElse(null) }
					val write =
						when {
							contact == null && link.resourceName != null -> Write.DELETE_PURGED
							contact == null -> {
								// Purged after its person was gone at Google: nothing is left to do.
								links.delete(link)
								null
							}
							contact.isDeleted && link.resourceName != null -> Write.DELETE
							contact.isDeleted -> null
							link.resourceName == null -> Write.CREATE
							contact.version > link.syncedVersion -> Write.UPDATE
							else -> null
						}
					write?.let { Pending(it, contact, link) }
				}
			}!!
		var failed = 0
		for (item in pending) {
			val ok =
				when (item.write) {
					Write.CREATE -> create(connectionId, checkNotNull(item.contact), item.link)
					Write.UPDATE -> update(connectionId, checkNotNull(item.contact), item.link)
					Write.DELETE, Write.DELETE_PURGED -> delete(connectionId, item.contact, item.link)
				}
			if (!ok) failed++
		}
		return failed
	}

	private fun create(
		connectionId: UUID,
		contact: Contact,
		link: GoogleContactLink,
	): Boolean {
		val response =
			client.call(
				connectionId,
				HttpMethod.POST,
				"/v1/people:createContact",
				query = mapOf("personFields" to GooglePersonMapping.PERSON_FIELDS),
				body = GooglePersonMapping.toPerson(json, contact, etag = null),
			)
		val resourceName = GooglePersonMapping.resourceName(response.body)
		// Checked before it is stored, so a later push or pull never puts an odd resource name into a URL.
		if (!response.ok || resourceName == null || !GoogleContactsClient.isResourceName(resourceName)) {
			return rejected(contact.id, response, "create")
		}
		tx.executeWithoutResult {
			val current = links.findById(link.id).orElse(null) ?: return@executeWithoutResult
			current.resourceName = resourceName
			current.etag = GooglePersonMapping.etag(response.body)
			current.syncedVersion = contact.version
			lookup.updateExternalId(contact.id, resourceName)
		}
		return true
	}

	/**
	 * Writes the local change of [contact]. The person is read first: when its etag is no longer the one last synced,
	 * it changed at Google, and Google's version wins (it is applied, nothing is written). Otherwise only the field
	 * groups the contact changed are written ([GooglePersonMapping.toUpdate]), with that etag; Google refusing it as
	 * stale (the person changed in between) also lets Google's version win.
	 */
	private fun update(
		connectionId: UUID,
		contact: Contact,
		link: GoogleContactLink,
	): Boolean {
		val resourceName = checkNotNull(link.resourceName)
		if (!GoogleContactsClient.isResourceName(resourceName)) return invalidName(contact.id)
		val path = GoogleContactsClient.resourcePath(resourceName)
		val current = read(connectionId, path)
		when {
			current.status == 404 -> {
				removedAtGoogle(connectionId, resourceName)
				return true
			}
			!current.ok -> return rejected(contact.id, current, "read")
			GooglePersonMapping.etag(current.body) != link.etag -> return googleWins(connectionId, contact, current.body)
		}
		val update = GooglePersonMapping.toUpdate(json, contact, current.body)
		if (update.fields.isEmpty()) {
			// Nothing Google has was changed (e.g. only the photo, or the display name Google derives).
			markSynced(link, contact, etag = link.etag)
			return true
		}
		val response =
			client.call(
				connectionId,
				HttpMethod.PATCH,
				"$path:updateContact",
				query = mapOf("updatePersonFields" to update.fields.joinToString(","), "personFields" to GooglePersonMapping.PERSON_FIELDS),
				body = update.person,
			)
		when {
			response.ok -> markSynced(link, contact, GooglePersonMapping.etag(response.body))
			response.status == 404 -> removedAtGoogle(connectionId, resourceName)
			isStaleEtag(response) -> {
				val person = read(connectionId, path)
				when {
					person.ok -> return googleWins(connectionId, contact, person.body)
					person.status == 404 -> removedAtGoogle(connectionId, resourceName)
					else -> return rejected(contact.id, person, "read")
				}
			}
			else -> return rejected(contact.id, response, "update")
		}
		return true
	}

	private fun read(
		connectionId: UUID,
		path: String,
	): GoogleContactsClient.Response =
		client.call(connectionId, HttpMethod.GET, path, query = mapOf("personFields" to GooglePersonMapping.PERSON_FIELDS))

	/** Changed at Google since the last sync, and here: Google's version wins. */
	private fun googleWins(
		connectionId: UUID,
		contact: Contact,
		person: JsonNode,
	): Boolean {
		log.info("Contact {} changed at Google and here; keeping Google's version", contact.id)
		return apply(connectionId, person, force = true)
	}

	private fun markSynced(
		link: GoogleContactLink,
		contact: Contact,
		etag: String?,
	) {
		tx.executeWithoutResult {
			val current = links.findById(link.id).orElse(null) ?: return@executeWithoutResult
			current.etag = etag
			current.syncedVersion = contact.version
		}
	}

	/** Deletes the person of a contact in the Trash, or of a purged one ([contact] `null`: the link is a tombstone). */
	private fun delete(
		connectionId: UUID,
		contact: Contact?,
		link: GoogleContactLink,
	): Boolean {
		val id = contact?.id ?: link.id
		val resourceName = checkNotNull(link.resourceName)
		if (!GoogleContactsClient.isResourceName(resourceName)) return invalidName(id)
		val response = client.call(connectionId, HttpMethod.DELETE, "${GoogleContactsClient.resourcePath(resourceName)}:deleteContact")
		if (!response.ok && response.status != 404) return rejected(id, response, "delete")
		tx.executeWithoutResult {
			val current = links.findById(link.id).orElse(null) ?: return@executeWithoutResult
			if (contact == null) {
				links.delete(current)
			} else {
				current.resourceName = null
				current.etag = null
				current.syncedVersion = contact.version
			}
		}
		return true
	}

	/** [id]: the contact's, or the link's of a purged contact; never contact data. */
	private fun rejected(
		id: UUID,
		response: GoogleContactsClient.Response,
		action: String,
	): Boolean {
		log.warn("Google refused to {} contact {} (HTTP {} {})", action, id, response.status, response.errorReasons())
		return false
	}

	/** A stored resource name that is not `people/<id>` is not put into a URL; only this contact is not synced. */
	private fun invalidName(id: UUID): Boolean {
		log.warn("Contact {} has an unexpected Google resource name and is not synced", id)
		return false
	}

	private fun isStaleEtag(response: GoogleContactsClient.Response): Boolean =
		response.status == 412 || (response.status == 400 && "FAILED_PRECONDITION" in response.errorReasons())

	// --- pull

	/** Applies Google's changes since the last run (or everything); returns how many persons could not be stored. */
	private fun pull(connectionId: UUID): Int {
		var syncToken = state(connectionId).syncToken
		var pageToken: String? = null
		val seen = mutableSetOf<String>()
		var failed = 0
		while (true) {
			val response =
				client.call(
					connectionId,
					HttpMethod.GET,
					"/v1/people/me/connections",
					query =
						mapOf(
							"personFields" to GooglePersonMapping.PERSON_FIELDS,
							"pageSize" to PAGE_SIZE.toString(),
							"requestSyncToken" to "true",
							"syncToken" to syncToken,
							"pageToken" to pageToken,
						),
				)
			if (syncToken != null && isExpiredToken(response)) {
				log.info("Google contacts sync token of connection {} expired, listing all contacts", connectionId)
				syncToken = null
				pageToken = null
				seen.clear()
				failed = 0
				continue
			}
			if (!response.ok) throw GoogleContactsException("Listing the Google contacts failed (HTTP ${response.status})")
			for (person in response.body.path("connections")) {
				GooglePersonMapping.resourceName(person)?.let { seen += it }
				if (!apply(connectionId, person, force = false)) failed++
			}
			pageToken = response.body.path("nextPageToken").asString(null)
			if (pageToken == null) {
				if (syncToken == null) removeMissing(connectionId, seen)
				tx.executeWithoutResult {
					accounts.findById(connectionId).ifPresent { it.syncToken = response.body.path("nextSyncToken").asString(null) }
				}
				return failed
			}
		}
	}

	private fun isExpiredToken(response: GoogleContactsClient.Response): Boolean =
		response.status == 410 || (response.status == 400 && "EXPIRED_SYNC_TOKEN" in response.errorReasons())

	/**
	 * Stores one person from Google: creates, changes or (deleted there) trashes its contact. [force] applies it even
	 * when its etag is the stored one. Returns false when it could not be stored, also when its resource name is not
	 * `people/<id>` (a later write would put it into a URL).
	 */
	private fun apply(
		connectionId: UUID,
		person: JsonNode,
		force: Boolean,
	): Boolean {
		val resourceName = GooglePersonMapping.resourceName(person) ?: return true
		if (!GoogleContactsClient.isResourceName(resourceName)) {
			log.warn("Google listed a contact with an unexpected resource name for connection {}; skipped", connectionId)
			return false
		}
		if (GooglePersonMapping.isDeleted(person)) {
			removedAtGoogle(connectionId, resourceName)
			return true
		}
		return try {
			tx.executeWithoutResult { store(connectionId, resourceName, person, force) }
			true
		} catch (e: RuntimeException) {
			// Usually Google data the contact validation refuses; the message may contain contact data.
			log.warn("Google contact {} of connection {} could not be stored ({})", resourceName, connectionId, e.javaClass.simpleName)
			false
		}
	}

	private fun store(
		connectionId: UUID,
		resourceName: String,
		person: JsonNode,
		force: Boolean,
	) {
		val etag = GooglePersonMapping.etag(person)
		val link = links.findByConnectionIdAndResourceName(connectionId, resourceName)
		if (link == null) {
			val changes = GooglePersonMapping.toChanges(person)
			// A contact restored from a backup (active, no link) is adopted. One in the Trash, or one linked to this
			// connection (its person was deleted at Google before), gives up the id and stays as it is; the person gets
			// a new contact. One of another connection keeps it: the person cannot be stored (the id is unique).
			val found = lookup.findBySourceAndExternalId(ContactSource.GOOGLE, resourceName)
			val foundLink = found?.let { links.findByContactId(it.id) }
			val contact =
				if (found != null && !found.isDeleted && foundLink == null) {
					service.update(found.id, changes) {}
				} else {
					if (found != null && (found.isDeleted || foundLink?.connectionId == connectionId)) {
						lookup.updateExternalId(found.id, null)
					}
					service.createSynced(ContactSource.GOOGLE, resourceName, changes)
				}
			links.saveAndFlush(GoogleContactLink(contact.id, connectionId, resourceName, etag, contact.version))
			return
		}
		// A tombstone: the contact was purged here, and the next push deletes the person at Google.
		val contactId = link.contactId ?: return
		if (!force && etag != null && etag == link.etag) return
		val contact = contacts.findById(contactId).orElse(null) ?: return
		// In the Trash here and not yet deleted at Google: the next push deletes it.
		if (contact.isDeleted) return
		val updated = service.update(contact.id, GooglePersonMapping.toChanges(person)) {}
		link.etag = etag
		link.syncedVersion = updated.version
	}

	/**
	 * The person is gone at Google: its contact goes to the Trash, and a restore creates it there again. The tombstone
	 * of a purged contact is removed.
	 */
	private fun removedAtGoogle(
		connectionId: UUID,
		resourceName: String,
	) {
		tx.executeWithoutResult {
			val link = links.findByConnectionIdAndResourceName(connectionId, resourceName) ?: return@executeWithoutResult
			val contactId = link.contactId
			if (contactId == null) {
				links.delete(link)
				return@executeWithoutResult
			}
			val contact = contacts.findById(contactId).orElse(null)
			link.resourceName = null
			link.etag = null
			links.saveAndFlush(link)
			if (contact != null && !contact.isDeleted) service.delete(contact.id) {}
			link.syncedVersion = contacts.findById(contactId).map { it.version }.orElse(link.syncedVersion)
		}
	}

	/** After a full listing: trashes the contacts whose person Google did not list. */
	private fun removeMissing(
		connectionId: UUID,
		seen: Set<String>,
	) {
		val missing = tx.execute { links.findByConnectionId(connectionId).mapNotNull { it.resourceName }.filter { it !in seen } }!!
		missing.forEach { removedAtGoogle(connectionId, it) }
	}

	companion object {
		const val CONTACTS_SCOPE = "https://www.googleapis.com/auth/contacts"
		private const val PAGE_SIZE = 1000
	}
}
