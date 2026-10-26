package ch.sonensei.cockpit.contacts.carddav

import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.contacts.domain.ContactSource
import ch.sonensei.cockpit.core.crypto.EncryptedSecret
import ch.sonensei.cockpit.core.persistence.UuidV7
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import java.time.Instant
import java.util.UUID

/**
 * A CardDAV account (`contacts_carddav_account`). [url] is what was configured: an address book, or a server or
 * principal URL from which the first address book is discovered ([addressbookUrl]). [password] is stored encrypted.
 */
@Entity
@Table(name = "contacts_carddav_account")
class CardDavAccount(
	@Column(name = "name", nullable = false)
	var name: String,
	@Column(name = "url", nullable = false)
	var url: String,
	@Column(name = "username")
	var username: String?,
	@Column(name = "password")
	var password: EncryptedSecret?,
	@Id
	@Column(name = "id", nullable = false, updatable = false)
	val id: UUID = UuidV7.generate(),
) {
	/** The address book that is synced, found by the first run. */
	@Column(name = "addressbook_url")
	var addressbookUrl: String? = null

	/** The RFC 6578 sync token of the last complete run; `null`: the next run lists every card. */
	@Column(name = "sync_token")
	var syncToken: String? = null

	@Column(name = "last_attempt_at")
	var lastAttemptAt: Instant? = null

	@Column(name = "last_success_at")
	var lastSuccessAt: Instant? = null

	/** Why the last attempt failed (no secrets, no contact data); `null` after a successful one. */
	@Column(name = "last_error")
	var lastError: String? = null
}

/**
 * Which card a contact is (`contacts_carddav_link`). [href] is the card's path at the server; [etag] is `null` while
 * the card does not exist there (yet, or any more); [syncedVersion] is the contact version that matches the card.
 *
 * Purging the contact from the Trash sets [contactId] to `null` (in the database): the link stays as a tombstone, so
 * the next push still deletes the card and a pull does not import it again meanwhile.
 */
@Entity
@Table(name = "contacts_carddav_link")
class CardDavLink(
	@Column(name = "contact_id", updatable = false)
	val contactId: UUID?,
	@Column(name = "account_id", nullable = false, updatable = false)
	val accountId: UUID,
	@Column(name = "href", nullable = false, updatable = false)
	val href: String,
	@Column(name = "etag")
	var etag: String?,
	@Column(name = "synced_version", nullable = false)
	var syncedVersion: Long,
	@Id
	@Column(name = "id", nullable = false, updatable = false)
	val id: UUID = UuidV7.generate(),
)

interface CardDavAccountRepository : JpaRepository<CardDavAccount, UUID>

interface CardDavLinkRepository : JpaRepository<CardDavLink, UUID> {
	fun findByAccountId(accountId: UUID): List<CardDavLink>

	fun findByContactId(contactId: UUID): CardDavLink?

	fun findByAccountIdAndHref(
		accountId: UUID,
		href: String,
	): CardDavLink?
}

/** Finds the contact a restored backup kept for a card, so a full sync adopts it instead of duplicating it. */
interface CardDavContactLookup : Repository<Contact, UUID> {
	fun findBySourceAndExternalId(
		source: ContactSource,
		externalId: String,
	): Contact?

	/** Sets the id at the server without changing the contact's version (it is no change of the user's data). */
	@Modifying(flushAutomatically = true)
	@Query("update Contact c set c.externalId = :externalId where c.id = :id")
	fun updateExternalId(
		id: UUID,
		externalId: String?,
	): Int
}
