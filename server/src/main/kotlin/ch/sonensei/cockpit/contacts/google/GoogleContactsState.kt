package ch.sonensei.cockpit.contacts.google

import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.contacts.domain.ContactSource
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

/** The sync state of one Google connection (`contacts_google_account`); created by its first sync. */
@Entity
@Table(name = "contacts_google_account")
class GoogleContactsAccount(
	@Id
	@Column(name = "connection_id", nullable = false, updatable = false)
	val connectionId: UUID,
) {
	/** The People API `nextSyncToken` of the last complete run; `null`: the next run lists every contact. */
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
 * Which Google person a contact is (`contacts_google_link`). [resourceName] is `null` while the person does not exist
 * at Google (any more); [syncedVersion] is the contact version that matches Google's [etag].
 */
@Entity
@Table(name = "contacts_google_link")
class GoogleContactLink(
	@Id
	@Column(name = "contact_id", nullable = false, updatable = false)
	val contactId: UUID,
	@Column(name = "connection_id", nullable = false, updatable = false)
	val connectionId: UUID,
	@Column(name = "resource_name")
	var resourceName: String?,
	@Column(name = "etag")
	var etag: String?,
	@Column(name = "synced_version", nullable = false)
	var syncedVersion: Long,
)

interface GoogleContactsAccountRepository : JpaRepository<GoogleContactsAccount, UUID>

interface GoogleContactLinkRepository : JpaRepository<GoogleContactLink, UUID> {
	fun findByConnectionId(connectionId: UUID): List<GoogleContactLink>

	fun findByConnectionIdAndResourceName(
		connectionId: UUID,
		resourceName: String,
	): GoogleContactLink?
}

/** Finds the contact a restored backup kept for a Google person, so a full sync adopts it instead of duplicating it. */
interface GoogleContactLookup : Repository<Contact, UUID> {
	fun findBySourceAndExternalId(
		source: ContactSource,
		externalId: String,
	): Contact?

	/** Sets the id at Google without changing the contact's version (it is no change of the user's data). */
	@Modifying(flushAutomatically = true)
	@Query("update Contact c set c.externalId = :externalId where c.id = :id")
	fun updateExternalId(
		id: UUID,
		externalId: String?,
	): Int
}
