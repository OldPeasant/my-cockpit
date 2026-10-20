package ch.sonensei.cockpit.core.persistence

import jakarta.persistence.Column
import jakarta.persistence.MappedSuperclass
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** User content that goes to the Trash instead of being deleted right away (SPEC.md §4.2). */
interface SoftDeletable {
	val deletedAt: Instant?

	val isDeleted: Boolean get() = deletedAt != null
}

/**
 * [BaseEntity] with soft delete. Tables add `deleted_at timestamptz` (nullable). Query through
 * [SoftDeletableRepository] so deleted rows stay out of normal reads.
 */
@MappedSuperclass
abstract class SoftDeletableEntity(
	id: UUID? = null,
) : BaseEntity(id),
	SoftDeletable {
	@Column(name = "deleted_at")
	// Not final: Hibernate can only create lazy proxies when entity getters are open.
	override var deletedAt: Instant? = null
		protected set

	/** Moves the entity to the Trash; deleting an already deleted entity keeps its original time. */
	fun softDelete(clock: Clock) {
		if (deletedAt == null) deletedAt = clock.storableInstant()
	}

	fun restore() {
		deletedAt = null
	}
}
