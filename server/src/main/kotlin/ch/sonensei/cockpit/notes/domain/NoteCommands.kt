package ch.sonensei.cockpit.notes.domain

/** A new value for a field; `null` instead of a [FieldChange] means the field stays unchanged. */
data class FieldChange<out T>(
	val value: T,
)

/** The fields of a create or change of a note; `null` fields stay unchanged (create: their defaults). */
data class NoteChanges(
	val title: FieldChange<String?>? = null,
	val bodyMd: FieldChange<String>? = null,
	val tags: FieldChange<List<String>>? = null,
	val pinned: FieldChange<Boolean>? = null,
	val archived: FieldChange<Boolean>? = null,
)

/**
 * Which notes to list: with [tag] (regardless of case), [pinned] or not (`null`: both), and archived ones only
 * ([archived] `true`), not archived ones only (`false`), or both (`null`).
 */
data class NoteFilter(
	val tag: String? = null,
	val pinned: Boolean? = null,
	val archived: Boolean? = false,
)

/** A tag and the number of notes (not in the Trash) that have it. */
data class NoteTag(
	val tag: String,
	val count: Long,
)
