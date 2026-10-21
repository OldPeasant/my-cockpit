package ch.sonensei.cockpit.contacts.domain

import ch.sonensei.cockpit.contacts.infra.ContactRepository
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.web.CursorPage
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** A change of one field; `Change(null)` clears it. A `null` change leaves the field as it is. */
data class Change<out T>(
	val value: T,
)

/** The fields of a create or update; `null`: not given (unchanged). */
data class ContactChanges(
	val displayName: Change<String?>? = null,
	val givenName: Change<String?>? = null,
	val familyName: Change<String?>? = null,
	val emails: Change<List<ContactEmail>>? = null,
	val phones: Change<List<ContactPhone>>? = null,
	val addresses: Change<List<ContactAddress>>? = null,
	val organization: Change<String?>? = null,
	val title: Change<String?>? = null,
	val birthday: Change<java.time.LocalDate?>? = null,
	val notesMd: Change<String?>? = null,
	val photoFileId: Change<UUID?>? = null,
)

/**
 * Contacts (SPEC.md §8.3): CRUD and soft delete. Every change re-indexes the contact for search and publishes a
 * [ch.sonensei.cockpit.core.events.domain.ChangeEvent]; a deleted contact goes to the Trash
 * ([ContactTrashContributor][ch.sonensei.cockpit.contacts.infra.ContactTrashContributor]) and leaves the search index.
 *
 * Without a display name, one is derived: given and family name, else the organization, else the first email address.
 * Methods that change a contact take a `checkVersion` callback that sees the current version first (If-Match).
 */
@Service
@Transactional
class ContactService(
	private val contacts: ContactRepository,
	private val items: ItemResolverRegistry,
	private val indexer: SearchIndexer,
	private val changes: ChangeEventPublisher,
	private val clock: Clock,
) {
	/** One page of contacts, ordered by display name, then id; [query] keeps those whose name or an email contains it. */
	@Transactional(readOnly = true)
	fun list(
		query: String?,
		request: CursorRequest,
	): CursorPage<Contact> {
		val after = request.position?.let(::parsePosition)
		val fetched =
			contacts.findBy<Contact, List<Contact>>(specification(query?.trim()?.takeIf { it.isNotEmpty() }, after)) { q ->
				q.sortBy(Sort.by(Sort.Order.asc("displayName"), Sort.Order.asc("id"))).limit(request.limit + 1).all()
			}
		return request.page(fetched) { "${it.id}|${it.displayName}" }
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): Contact = load(id)

	/** Every contact not in the Trash, in list order. */
	@Transactional(readOnly = true)
	fun all(): List<Contact> = contacts.findByDeletedAtIsNullOrderByDisplayNameAscIdAsc()

	/**
	 * Creates a LOCAL contact.
	 *
	 * @throws InvalidRequestException (400) for invalid fields, an unknown photo file, or when no display name is given
	 *   and none can be derived
	 */
	fun create(fields: ContactChanges): Contact {
		val contact = Contact(displayName = "")
		apply(contact, fields)
		validate(contact, checkPhoto = fields.photoFileId != null)
		val saved = contacts.saveAndFlush(contact)
		changed(saved, ChangeOp.CREATED)
		return saved
	}

	/**
	 * Changes the given fields. Clearing the display name derives it again from the other fields.
	 *
	 * @throws InvalidRequestException (400) for invalid fields or an unknown photo file (only checked when it changes)
	 */
	fun update(
		id: UUID,
		fields: ContactChanges,
		checkVersion: (Long) -> Unit,
	): Contact {
		val contact = load(id)
		checkVersion(contact.version)
		apply(contact, fields)
		validate(contact, checkPhoto = fields.photoFileId != null)
		val saved = contacts.saveAndFlush(contact)
		changed(saved)
		return saved
	}

	/** Moves the contact to the Trash. Its photo stays in the drive. */
	fun delete(
		id: UUID,
		checkVersion: (Long) -> Unit,
	) {
		val contact = load(id)
		checkVersion(contact.version)
		contact.softDelete(clock)
		contacts.saveAndFlush(contact)
		indexer.delete(ItemType.CONTACT, contact.id)
		changes.publish(ItemType.CONTACT, contact.id, ChangeOp.DELETED)
	}

	/** Re-indexes a contact and announces it as changed, e.g. after a restore. */
	fun changed(
		contact: Contact,
		op: ChangeOp = ChangeOp.UPDATED,
	) {
		indexContact(contact)
		changes.publish(ItemType.CONTACT, contact.id, op)
	}

	/** Puts the contact into the search index: the name in the title, the other fields in the body. */
	fun indexContact(contact: Contact) {
		val body =
			listOfNotNull(contact.givenName, contact.familyName, contact.organization, contact.title) +
				contact.emails.map { it.address } +
				contact.phones.map { it.number } +
				contact.addresses.flatMap { it.parts() } +
				listOfNotNull(contact.notesMd)
		indexer.upsert(ItemType.CONTACT, contact.id, contact.displayName, body.joinToString("\n"))
	}

	private fun load(id: UUID): Contact = contacts.findActiveById(id) ?: throw NotFoundException("Contact not found")

	private fun apply(
		contact: Contact,
		fields: ContactChanges,
	) {
		fields.givenName?.let { contact.givenName = it.value.normalized() }
		fields.familyName?.let { contact.familyName = it.value.normalized() }
		fields.emails?.let { change -> contact.emails = change.value.map { it.copy(address = it.address.trim(), label = it.label.normalized()) } }
		fields.phones?.let { change -> contact.phones = change.value.map { it.copy(number = it.number.trim(), label = it.label.normalized()) } }
		fields.addresses?.let { change -> contact.addresses = change.value.map(::normalized) }
		fields.organization?.let { contact.organization = it.value.normalized() }
		fields.title?.let { contact.title = it.value.normalized() }
		fields.birthday?.let { contact.birthday = it.value }
		fields.notesMd?.let { contact.notesMd = it.value?.takeIf { notes -> notes.isNotBlank() } }
		fields.photoFileId?.let { contact.photoFileId = it.value }
		val name = if (fields.displayName != null) fields.displayName.value.normalized() else contact.displayName.normalized()
		contact.displayName = name ?: derivedName(contact).orEmpty()
	}

	/** [checkPhoto]: whether the photo was changed; an unchanged photo may be gone from the drive meanwhile. */
	private fun validate(
		contact: Contact,
		checkPhoto: Boolean,
	) {
		val errors = mutableListOf<FieldError>()
		if (contact.displayName.isBlank()) {
			errors += FieldError("displayName", "is required when there is no name, organization or email address")
		}

		fun checkLength(
			field: String,
			value: String?,
			max: Int = MAX_FIELD_LENGTH,
		) {
			if ((value?.length ?: 0) > max) errors += FieldError(field, "must be at most $max characters")
		}
		checkLength("displayName", contact.displayName)
		checkLength("givenName", contact.givenName)
		checkLength("familyName", contact.familyName)
		checkLength("organization", contact.organization)
		checkLength("title", contact.title)
		checkLength("notesMd", contact.notesMd, MAX_NOTES_LENGTH)
		if (contact.emails.size + contact.phones.size + contact.addresses.size > MAX_ENTRIES) {
			errors += FieldError("emails", "together with phones and addresses must have at most $MAX_ENTRIES entries")
		}
		contact.emails.forEachIndexed { i, email ->
			if (!EMAIL.matches(email.address)) errors += FieldError("emails[$i].address", "is not an email address")
			checkLength("emails[$i].address", email.address)
			checkLength("emails[$i].label", email.label)
		}
		contact.phones.forEachIndexed { i, phone ->
			if (phone.number.isBlank()) errors += FieldError("phones[$i].number", "must not be blank")
			checkLength("phones[$i].number", phone.number)
			checkLength("phones[$i].label", phone.label)
		}
		contact.addresses.forEachIndexed { i, address ->
			if (address.parts().isEmpty()) errors += FieldError("addresses[$i]", "must have at least one part")
			listOf(address.label, address.street, address.city, address.region, address.postalCode, address.country)
				.forEach { checkLength("addresses[$i]", it) }
		}
		contact.photoFileId?.takeIf { checkPhoto }?.let { fileId ->
			// Through the resolver: it answers `null` instead of throwing, which would mark this transaction rollback-only.
			if (items.find(ItemType.FILE)?.exists(fileId) != true) errors += FieldError("photoFileId", "is not a file of the drive")
		}
		if (errors.isNotEmpty()) throw InvalidRequestException(errors.distinct())
	}

	private fun derivedName(contact: Contact): String? =
		listOfNotNull(contact.givenName, contact.familyName).joinToString(" ").normalized()
			?: contact.organization
			?: contact.emails
				.firstOrNull()
				?.address
				?.normalized()

	private fun normalized(address: ContactAddress) =
		ContactAddress(
			label = address.label.normalized(),
			street = address.street.normalized(),
			city = address.city.normalized(),
			region = address.region.normalized(),
			postalCode = address.postalCode.normalized(),
			country = address.country.normalized(),
		)

	private fun String?.normalized(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

	private fun specification(
		query: String?,
		after: Pair<String, UUID>?,
	): Specification<Contact> =
		Specification { root, _, cb ->
			val predicates = mutableListOf<Predicate>(cb.isNull(root.get<Instant>("deletedAt")))
			if (query != null) {
				val pattern =
					"%" +
						query
							.lowercase()
							.replace("\\", "\\\\")
							.replace("%", "\\%")
							.replace("_", "\\_") + "%"
				// emails is jsonb: the SQL function (migration contacts_email_search) looks only at the address values.
				val emails = cb.function("contacts_email_matches", Boolean::class.java, root.get<Any>("emails"), cb.literal(pattern))
				predicates += cb.or(cb.like(cb.lower(root.get("displayName")), pattern, '\\'), cb.isTrue(emails))
			}
			if (after != null) {
				val name = root.get<String>("displayName")
				val id = root.get<UUID>("id")
				predicates += cb.or(cb.greaterThan(name, after.first), cb.and(cb.equal(name, after.first), cb.greaterThan(id, after.second)))
			}
			cb.and(*predicates.toTypedArray())
		}

	/** The cursor is `<id>|<displayName>`: the id first, as the name may contain `|`. */
	private fun parsePosition(position: String): Pair<String, UUID> {
		val parsed =
			runCatching {
				val separator = position.indexOf('|').also { require(it > 0) }
				position.substring(separator + 1) to UUID.fromString(position.substring(0, separator))
			}.getOrNull()
		return parsed ?: throw InvalidRequestException("cursor", "is not a valid cursor")
	}

	companion object {
		const val MAX_FIELD_LENGTH = 500
		const val MAX_NOTES_LENGTH = 100_000
		const val MAX_ENTRIES = 100
		private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+$")
	}
}
