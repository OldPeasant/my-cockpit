package ch.sonensei.cockpit.bookmarks.domain

import java.util.UUID

/** A new value for a field; `null` instead of a [FieldChange] means the field stays unchanged. */
data class FieldChange<out T>(
	val value: T,
)

/**
 * The fields of a create or change of a bookmark; `null` fields stay unchanged (create: their defaults). [read] `true`
 * marks the bookmark as read now (a bookmark already read keeps its time), `false` as unread.
 */
data class BookmarkChanges(
	val url: FieldChange<String>? = null,
	val title: FieldChange<String?>? = null,
	val description: FieldChange<String?>? = null,
	val tags: FieldChange<List<String>>? = null,
	val readLater: FieldChange<Boolean>? = null,
	val read: FieldChange<Boolean>? = null,
)

/**
 * Which bookmarks to list: with [tag] (regardless of case), in the read-later list or not ([readLater], `null`: both),
 * read or unread ([read], `null`: both). The read-later queue is `readLater = true, read = false`.
 */
data class BookmarkFilter(
	val tag: String? = null,
	val readLater: Boolean? = null,
	val read: Boolean? = null,
)

/** A tag and the number of bookmarks (not in the Trash) that have it. */
data class BookmarkTag(
	val tag: String,
	val count: Long,
)

/**
 * What a page says about itself: OpenGraph (or plain HTML) title and description, and the favicon as an absolute
 * http(s) URL. Each is `null` when the page has none.
 */
data class PageMetadata(
	val title: String? = null,
	val description: String? = null,
	val favicon: String? = null,
)

/**
 * Published (in the saving transaction) when the page metadata of bookmark [id] should be fetched from [url]. With
 * [overwrite], fetched values replace the title, description and favicon; otherwise they only fill empty ones.
 */
data class BookmarkMetadataRequested(
	val id: UUID,
	val url: String,
	val overwrite: Boolean,
)
