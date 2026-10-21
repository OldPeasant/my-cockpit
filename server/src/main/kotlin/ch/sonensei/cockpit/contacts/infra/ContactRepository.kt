package ch.sonensei.cockpit.contacts.infra

import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.core.persistence.SoftDeletableRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

interface ContactRepository :
	SoftDeletableRepository<Contact>,
	JpaSpecificationExecutor<Contact> {
	/** Every contact not in the Trash, in list order (export). */
	fun findByDeletedAtIsNullOrderByDisplayNameAscIdAsc(): List<Contact>
}
