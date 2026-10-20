package ch.sonensei.cockpit.core.items.domain

import ch.sonensei.cockpit.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.util.UUID

/**
 * Who created a link: [MANUAL] via the API (at most one per pair, in either direction), [SYNC] owned by its source and
 * replaced by [LinkService.sync] (at most one per source and target). The origins are independent of each other.
 */
enum class LinkOrigin {
	MANUAL,
	SYNC,
}

/**
 * A link between two items (SPEC.md §4.3). It has a direction (source → target) for ownership, but is shown on both
 * ends. A pair has at most one manual link (in either direction) and at most one sync link per direction;
 * [LinkService.linksOf] shows each linked item once. Links are immutable.
 */
@Entity
@Table(name = "link")
class Link(
	@Enumerated(EnumType.STRING)
	@Column(name = "source_type", nullable = false, updatable = false)
	val sourceType: ItemType,
	@Column(name = "source_id", nullable = false, updatable = false)
	val sourceId: UUID,
	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", nullable = false, updatable = false)
	val targetType: ItemType,
	@Column(name = "target_id", nullable = false, updatable = false)
	val targetId: UUID,
	@Column(name = "label", updatable = false)
	val label: String?,
	@Enumerated(EnumType.STRING)
	@Column(name = "origin", nullable = false, updatable = false)
	val origin: LinkOrigin,
	id: UUID? = null,
) : BaseEntity(id) {
	constructor(source: ItemRef, target: ItemRef, label: String?, origin: LinkOrigin) :
		this(source.type, source.id, target.type, target.id, label, origin)

	val source: ItemRef get() = ItemRef(sourceType, sourceId)

	val target: ItemRef get() = ItemRef(targetType, targetId)

	fun connects(
		a: ItemRef,
		b: ItemRef,
	): Boolean = (source == a && target == b) || (source == b && target == a)
}

/**
 * Publish when an item is deleted for good (purged from the Trash, or a hard delete), not on a soft delete: links of
 * an item in the Trash stay so a restore brings them back. [LinkService] removes the item's links in the publisher's
 * transaction.
 */
data class ItemDeletedEvent(
	val item: ItemRef,
)
