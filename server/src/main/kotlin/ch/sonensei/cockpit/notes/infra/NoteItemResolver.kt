package ch.sonensei.cockpit.notes.infra

import ch.sonensei.cockpit.core.items.domain.ItemResolver
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Notes in generic views (SPEC.md §4.3): the (derived) title, subtitle "Note" or "Archived note", deep link `/notes/{id}`. */
@Component
class NoteItemResolver(
	private val notes: NoteRepository,
) : ItemResolver {
	override val type = ItemType.NOTE

	@Transactional(readOnly = true)
	override fun resolve(id: UUID): ResolvedItem? =
		notes.findActiveById(id)?.let {
			ResolvedItem(
				title = it.displayTitle,
				subtitle = if (it.archived) "Archived note" else "Note",
				icon = if (it.pinned) "push_pin" else "description",
				deepLink = "/notes/${it.id}",
			)
		}
}
