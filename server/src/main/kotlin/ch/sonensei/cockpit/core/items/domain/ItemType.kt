package ch.sonensei.cockpit.core.items.domain

import java.util.UUID

/** Every kind of item that links, search and reminders can point at (SPEC.md §4.3). Stored by name. */
enum class ItemType {
	TASK,
	PROJECT,
	NOTE,
	EVENT,
	EMAIL,
	FILE,
	FOLDER,
	CONTACT,
	BOOKMARK,
	JOURNAL_ENTRY,
	HABIT,
}

/** A reference to one item of any module (`item_ref`, SPEC.md §4.3). For [ItemType.EMAIL] the id is `mail_message.id`. */
data class ItemRef(
	val type: ItemType,
	val id: UUID,
) {
	override fun toString(): String = "${type.name.lowercase()}:$id"
}
