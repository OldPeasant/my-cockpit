package ch.sonensei.cockpit.notes.api

import ch.sonensei.cockpit.files.domain.FileItem
import ch.sonensei.cockpit.notes.domain.FieldChange
import ch.sonensei.cockpit.notes.domain.Note
import ch.sonensei.cockpit.notes.domain.NoteChanges
import ch.sonensei.cockpit.notes.domain.NoteRevision
import ch.sonensei.cockpit.notes.domain.NoteTag
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import java.time.Instant
import java.util.UUID

@Schema(description = "A Markdown note. `version` is also sent as the ETag.")
data class NoteResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(description = "The title as set; `null` when the note has none") val title: String?,
	@get:Schema(
		description = "The title, or else the first line of the body as plain text (`Untitled note` for an empty body)",
		requiredMode = RequiredMode.REQUIRED,
	)
	val displayTitle: String,
	@get:Schema(
		description = "Markdown. Inline links (type:id in double square brackets) are synced to the links; images point to cockpit-file:{fileId}",
		requiredMode = RequiredMode.REQUIRED,
	)
	val bodyMd: String,
	@get:Schema(description = "Ordered regardless of case", requiredMode = RequiredMode.REQUIRED) val tags: List<String>,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val pinned: Boolean,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val archived: Boolean,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val createdAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val updatedAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(note: Note) =
			NoteResponse(
				id = note.id,
				title = note.title,
				displayTitle = note.displayTitle,
				bodyMd = note.bodyMd,
				tags = note.tags.sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it }),
				pinned = note.pinned,
				archived = note.archived,
				createdAt = checkNotNull(note.createdAt),
				updatedAt = checkNotNull(note.updatedAt),
				version = note.version,
			)
	}
}

@Schema(description = "One page of notes")
data class NotePageResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<NoteResponse>,
	@get:Schema(description = "Cursor of the next page; `null` on the last page") val nextCursor: String?,
)

@Schema(description = "A new note. Every field is optional.")
data class CreateNoteRequest(
	@get:Schema(description = "Empty or omitted: derived from the first line of the body", maxLength = 500)
	val title: String? = null,
	@get:Schema(description = "Markdown (default empty)", maxLength = 100_000)
	val bodyMd: String? = null,
	@get:Schema(description = "Trimmed, a leading `#` removed, each once regardless of case (at most 50, 50 characters each)")
	val tags: List<String?>? = null,
	val pinned: Boolean? = null,
	val archived: Boolean? = null,
) {
	fun toChanges() =
		NoteChanges(
			title = title?.let(::FieldChange),
			bodyMd = bodyMd?.let(::FieldChange),
			tags = tags?.let { FieldChange(it.withoutNulls()) },
			pinned = pinned?.let(::FieldChange),
			archived = archived?.let(::FieldChange),
		)
}

/** The fields PATCH accepts (documentation and type conversion; which fields were sent is read from the JSON body). */
@Schema(description = "Changes to a note: only the fields sent change; `null` clears `title` (the others cannot be null)")
data class NotePatchRequest(
	@get:Schema(types = ["string", "null"], maxLength = 500) val title: String? = null,
	@get:Schema(maxLength = 100_000) val bodyMd: String? = null,
	@get:Schema(description = "The complete new list of tags") val tags: List<String?>? = null,
	val pinned: Boolean? = null,
	val archived: Boolean? = null,
)

@Schema(description = "A tag and the number of notes (not in the Trash) that have it")
data class NoteTagResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val tag: String,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val count: Long,
) {
	companion object {
		fun of(tag: NoteTag) = NoteTagResponse(tag.tag, tag.count)
	}
}

@Schema(description = "Every tag in use, ordered regardless of case")
data class NoteTagListResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<NoteTagResponse>,
)

@Schema(description = "An earlier version of a note's title and body")
data class NoteRevisionResponse(
	@get:Schema(description = "Counts up per note; used to restore it", requiredMode = RequiredMode.REQUIRED) val number: Int,
	val title: String?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val bodyMd: String,
	@get:Schema(description = "When this content was last saved", requiredMode = RequiredMode.REQUIRED) val savedAt: Instant,
	@get:Schema(description = "When it was replaced by a newer version", requiredMode = RequiredMode.REQUIRED) val replacedAt: Instant,
) {
	companion object {
		fun of(revision: NoteRevision) =
			NoteRevisionResponse(
				number = revision.number,
				title = revision.title,
				bodyMd = revision.bodyMd,
				savedAt = revision.savedAt,
				replacedAt = checkNotNull(revision.createdAt),
			)
	}
}

@Schema(description = "The kept revisions of a note (at most 20), newest first; the current content is not one of them")
data class NoteRevisionListResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<NoteRevisionResponse>,
)

@Schema(description = "An image stored for a note in `/Attachments/Notes`")
data class NoteImageResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val fileId: UUID,
	@get:Schema(description = "`cockpit-file:{fileId}`", requiredMode = RequiredMode.REQUIRED) val reference: String,
	@get:Schema(description = "A Markdown image of cockpit-file:{fileId}, to insert into the body", requiredMode = RequiredMode.REQUIRED)
	val markdown: String,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val name: String,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val mime: String,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val size: Long,
) {
	companion object {
		fun of(file: FileItem): NoteImageResponse {
			val reference = "cockpit-file:${file.id}"
			return NoteImageResponse(file.id, reference, "![]($reference)", file.name, file.mime, file.size)
		}
	}
}
