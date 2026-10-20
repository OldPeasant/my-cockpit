package ch.sonensei.cockpit.core.items.domain

import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Describes the items of one [ItemType] for generic views such as the "Linked items" panel (SPEC.md §4.3). Each module
 * registers one resolver per type it owns as a Spring bean; [ItemResolverRegistry] collects them.
 */
interface ItemResolver {
	val type: ItemType

	/** The item's display data, or `null` when it does not exist (also when it is in the Trash). */
	fun resolve(id: UUID): ResolvedItem?

	fun exists(id: UUID): Boolean = resolve(id) != null
}

/**
 * Display data of an existing item. [title] and [subtitle] are short plain text (no note or mail bodies); [icon] is a
 * Material Symbols name (e.g. `task_alt`); [deepLink] is the client route of its detail view (e.g. `/tasks/{id}`).
 */
data class ResolvedItem(
	val title: String,
	val subtitle: String? = null,
	val icon: String,
	val deepLink: String,
)

/** Finds the [ItemResolver] of a type. Types without a resolver (their module is not built yet) give `null`. */
@Component
class ItemResolverRegistry(
	resolvers: List<ItemResolver>,
) {
	private val byType: Map<ItemType, ItemResolver> =
		resolvers.groupBy { it.type }.mapValues { (type, found) ->
			check(found.size == 1) { "More than one ItemResolver for $type: ${found.map { it.javaClass.name }}" }
			found.single()
		}

	fun find(type: ItemType): ItemResolver? = byType[type]
}
