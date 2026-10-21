package ch.sonensei.cockpit.notes

import ch.sonensei.cockpit.core.network.generated.models.NoteResponse
import java.time.Instant
import java.util.UUID

/** A Markdown note as the app shows it (SPEC.md §8.1). */
data class Note(
    val id: UUID,
    /** The title as set; `null` when the note has none. */
    val title: String?,
    /** The title, or else the first line of the body (computed by the server). */
    val displayTitle: String,
    val bodyMd: String,
    val pinned: Boolean,
    val tags: List<String>,
    val updatedAt: Instant,
    /** The note's version, sent back as `If-Match` when it is saved. */
    val version: Long,
)

/** A tag in use and its number of notes. */
data class NoteTag(
    val tag: String,
    val count: Long,
)

internal fun NoteResponse.toNote() =
    Note(
        id = id,
        title = title,
        displayTitle = displayTitle,
        bodyMd = bodyMd,
        pinned = pinned,
        tags = tags,
        updatedAt = updatedAt.toInstant(),
        version = version,
    )

/** Pinned notes first, then the most recently changed (the server's order, kept also when a page was merged). */
internal val noteOrder: Comparator<Note> =
    compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt }.thenBy { it.id }

/** The `If-Match` value of [version] (the server's strong ETag). */
internal fun etag(version: Long) = "\"$version\""
