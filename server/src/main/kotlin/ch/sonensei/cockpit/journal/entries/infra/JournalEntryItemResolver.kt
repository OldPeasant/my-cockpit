package ch.sonensei.cockpit.journal.entries.infra

import ch.sonensei.cockpit.core.items.domain.ItemResolver
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Journal entries in generic views (SPEC.md §4.3): title `Journal {date}`, subtitle "Journal entry", deep link `/journal/{date}`. */
@Component
class JournalEntryItemResolver(
	private val entries: JournalEntryRepository,
) : ItemResolver {
	override val type = ItemType.JOURNAL_ENTRY

	@Transactional(readOnly = true)
	override fun resolve(id: UUID): ResolvedItem? =
		entries.findActiveById(id)?.let {
			ResolvedItem(
				title = it.displayTitle,
				subtitle = SUBTITLE,
				icon = ICON,
				deepLink = "/journal/${it.date}",
			)
		}

	companion object {
		const val SUBTITLE = "Journal entry"
		const val ICON = "auto_stories"
	}
}
