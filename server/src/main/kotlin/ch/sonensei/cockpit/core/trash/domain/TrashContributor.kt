package ch.sonensei.cockpit.core.trash.domain

import ch.sonensei.cockpit.core.items.domain.ItemType
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * The Trash of one [ItemType] (SPEC.md §4.2): its soft-deleted items. Each module registers one contributor per
 * soft-deletable type it owns as a Spring bean; [TrashContributorRegistry] collects them and [TrashService] calls them
 * inside its transaction.
 */
interface TrashContributor {
	val type: ItemType

	/** Every item of [type] that is in the Trash (soft-deleted, not yet purged), in any order. */
	fun listDeleted(): List<TrashedItem>

	/** Takes the item out of the Trash. `false` when no item with [id] is in the Trash (missing or not deleted). */
	fun restore(id: UUID): Boolean

	/**
	 * Deletes the item permanently, with everything the module keeps for it (e.g. a file's blob). Links are removed by
	 * [TrashService]. `false` when no item with [id] is in the Trash: an item that is not deleted is never purged.
	 */
	fun purge(id: UUID): Boolean
}

/**
 * An item in the Trash. [title] and [subtitle] are short plain text (no note or mail bodies); [icon] is a Material
 * Symbols name (e.g. `task_alt`).
 */
data class TrashedItem(
	val id: UUID,
	val title: String,
	val deletedAt: Instant,
	val subtitle: String? = null,
	val icon: String,
)

/** Finds the [TrashContributor] of a type. Types without one (not soft-deletable, or not built yet) give `null`. */
@Component
class TrashContributorRegistry(
	contributors: List<TrashContributor>,
) {
	private val byType: Map<ItemType, TrashContributor> =
		contributors.groupBy { it.type }.mapValues { (type, found) ->
			check(found.size == 1) { "More than one TrashContributor for $type: ${found.map { it.javaClass.name }}" }
			found.single()
		}

	fun find(type: ItemType): TrashContributor? = byType[type]

	fun all(): Collection<TrashContributor> = byType.values
}
