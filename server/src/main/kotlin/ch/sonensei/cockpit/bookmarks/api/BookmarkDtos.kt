package ch.sonensei.cockpit.bookmarks.api

import ch.sonensei.cockpit.bookmarks.domain.Bookmark
import ch.sonensei.cockpit.bookmarks.domain.BookmarkChanges
import ch.sonensei.cockpit.bookmarks.domain.BookmarkTag
import ch.sonensei.cockpit.bookmarks.domain.FieldChange
import ch.sonensei.cockpit.bookmarks.domain.MetadataStatus
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import java.time.Instant
import java.util.UUID

@Schema(description = "A bookmark. `version` is also sent as the ETag.")
data class BookmarkResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(description = "Absolute http(s) URL", requiredMode = RequiredMode.REQUIRED) val url: String,
	@get:Schema(description = "As set or fetched from the page; `null` when there is none") val title: String?,
	@get:Schema(description = "The title, or else the host of the URL", requiredMode = RequiredMode.REQUIRED) val displayTitle: String,
	@get:Schema(description = "As set or fetched from the page") val description: String?,
	@get:Schema(description = "Absolute http(s) URL of the site's icon, fetched from the page") val favicon: String?,
	@get:Schema(description = "Ordered regardless of case", requiredMode = RequiredMode.REQUIRED) val tags: List<String>,
	@get:Schema(description = "In the read-later list", requiredMode = RequiredMode.REQUIRED) val readLater: Boolean,
	@get:Schema(description = "When it was marked as read; `null` while unread") val readAt: Instant?,
	@get:Schema(description = "File with a readable copy of the page") val snapshotFileId: UUID?,
	@get:Schema(
		description =
			"Whether title, description and favicon were fetched from the page after the URL was saved. The fetch runs in " +
				"the background; when it finishes the bookmark changes (new ETag) and a change event is sent.",
		requiredMode = RequiredMode.REQUIRED,
	)
	val metadataStatus: MetadataStatus,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val createdAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val updatedAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(bookmark: Bookmark) =
			BookmarkResponse(
				id = bookmark.id,
				url = bookmark.url,
				title = bookmark.title,
				displayTitle = bookmark.displayTitle,
				description = bookmark.description,
				favicon = bookmark.favicon,
				tags = bookmark.tags.sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it }),
				readLater = bookmark.readLater,
				readAt = bookmark.readAt,
				snapshotFileId = bookmark.snapshotFileId,
				metadataStatus = bookmark.metadataStatus,
				createdAt = checkNotNull(bookmark.createdAt),
				updatedAt = checkNotNull(bookmark.updatedAt),
				version = bookmark.version,
			)
	}
}

@Schema(description = "One page of bookmarks")
data class BookmarkPageResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<BookmarkResponse>,
	@get:Schema(description = "Cursor of the next page; `null` on the last page") val nextCursor: String?,
)

@Schema(description = "A new bookmark. Only `url` is required; an empty title or description is filled from the page.")
data class CreateBookmarkRequest(
	@get:Schema(description = "Absolute http(s) URL", maxLength = 2_048, requiredMode = RequiredMode.REQUIRED)
	val url: String? = null,
	@get:Schema(maxLength = 500) val title: String? = null,
	@get:Schema(maxLength = 2_000) val description: String? = null,
	@get:Schema(description = "Trimmed, a leading `#` removed, each once regardless of case (at most 50, 50 characters each)")
	val tags: List<String?>? = null,
	@get:Schema(description = "Put into the read-later list (default false)") val readLater: Boolean? = null,
) {
	fun toChanges() =
		BookmarkChanges(
			url = url?.let(::FieldChange),
			title = title?.let(::FieldChange),
			description = description?.let(::FieldChange),
			tags = tags?.let { FieldChange(it.withoutNulls()) },
			readLater = readLater?.let(::FieldChange),
		)
}

/** The fields PATCH accepts (documentation and type conversion; which fields were sent is read from the JSON body). */
@Schema(
	description =
		"Changes to a bookmark: only the fields sent change; `null` clears `title` and `description` (the others cannot " +
			"be null). A new `url` clears the favicon and fetches the page metadata again.",
)
data class BookmarkPatchRequest(
	@get:Schema(maxLength = 2_048) val url: String? = null,
	@get:Schema(types = ["string", "null"], maxLength = 500) val title: String? = null,
	@get:Schema(types = ["string", "null"], maxLength = 2_000) val description: String? = null,
	@get:Schema(description = "The complete new list of tags") val tags: List<String?>? = null,
	val readLater: Boolean? = null,
	@get:Schema(description = "true: mark as read now (keeps an earlier read time), false: mark as unread") val read: Boolean? = null,
)

@Schema(description = "A tag and the number of bookmarks (not in the Trash) that have it")
data class BookmarkTagResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val tag: String,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val count: Long,
) {
	companion object {
		fun of(tag: BookmarkTag) = BookmarkTagResponse(tag.tag, tag.count)
	}
}

@Schema(description = "Every bookmark tag in use, ordered regardless of case")
data class BookmarkTagListResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<BookmarkTagResponse>,
)
