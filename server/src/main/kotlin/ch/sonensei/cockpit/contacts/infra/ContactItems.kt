package ch.sonensei.cockpit.contacts.infra

import ch.sonensei.cockpit.contacts.domain.ContactService
import ch.sonensei.cockpit.core.items.domain.ItemResolver
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import ch.sonensei.cockpit.core.trash.domain.TrashContributor
import ch.sonensei.cockpit.core.trash.domain.TrashedItem
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private const val CONTACT_ICON = "person"

/** Contacts in generic views (SPEC.md §4.3): subtitle is the organization or first email, deep link `/contacts/{id}`. */
@Component
class ContactItemResolver(
	private val contacts: ContactRepository,
) : ItemResolver {
	override val type = ItemType.CONTACT

	@Transactional(readOnly = true)
	override fun resolve(id: UUID): ResolvedItem? =
		contacts.findActiveById(id)?.let {
			ResolvedItem(
				title = it.displayName,
				subtitle = it.organization ?: it.emails.firstOrNull()?.address,
				icon = CONTACT_ICON,
				deepLink = "/contacts/${it.id}",
			)
		}
}

/** Deleted contacts in the Trash. A restored contact is searchable again; a purge deletes the row (its photo stays). */
@Component
class ContactTrashContributor(
	private val contacts: ContactRepository,
	private val service: ContactService,
) : TrashContributor {
	override val type = ItemType.CONTACT

	override fun listDeleted(): List<TrashedItem> =
		contacts.findAllDeleted().map {
			TrashedItem(
				id = it.id,
				title = it.displayName,
				deletedAt = checkNotNull(it.deletedAt),
				subtitle = "Contact",
				icon = CONTACT_ICON,
			)
		}

	override fun restore(id: UUID): Boolean {
		val contact = contacts.findById(id).orElse(null)?.takeIf { it.isDeleted } ?: return false
		contact.restore()
		contacts.saveAndFlush(contact)
		service.indexContact(contact)
		return true
	}

	override fun purge(id: UUID): Boolean {
		val contact = contacts.findById(id).orElse(null)?.takeIf { it.isDeleted } ?: return false
		contacts.delete(contact)
		contacts.flush()
		return true
	}
}
