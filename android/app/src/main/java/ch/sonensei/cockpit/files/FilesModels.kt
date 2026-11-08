package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.core.network.PLACEHOLDER_BASE_URL
import ch.sonensei.cockpit.core.network.generated.models.DriveEntryResponse
import ch.sonensei.cockpit.core.network.generated.models.FileResponse
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** An entry of a folder as the app shows it (SPEC.md §8.2): a folder or a file. */
sealed interface DriveEntry {
    val id: UUID
    val name: String

    /** Sent as `If-Match` when it is changed (SPEC.md §4.2). */
    val version: Long

    data class Folder(
        override val id: UUID,
        override val name: String,
        /** Set for the auto-managed folders (`ATTACHMENTS`, `TASKS`, ...), which cannot be renamed, moved or deleted. */
        val special: String?,
        override val version: Long = 0,
    ) : DriveEntry

    data class File(
        override val id: UUID,
        override val name: String,
        val mime: String,
        val size: Long,
        val updatedAt: Instant,
        override val version: Long = 0,
    ) : DriveEntry
}

/** The start of a text file as the server previews it; [truncated] when the file is longer. */
data class TextPreview(
    val text: String,
    val truncated: Boolean,
)

/** How the app previews a file itself (SPEC.md §8.2); [OTHER] files are only opened in another app. */
enum class PreviewKind { IMAGE, PDF, TEXT, OTHER }

/**
 * What the in-app preview does with [mime]: the images the server serves for previews and thumbnails (JPEG, PNG, WebP,
 * GIF), PDFs, and what the server previews as text.
 */
internal fun previewKind(mime: String): PreviewKind {
    val type = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
    return when {
        type in IMAGE_TYPES -> PreviewKind.IMAGE
        type == "application/pdf" -> PreviewKind.PDF
        type.startsWith("text/") || type in TEXT_APPLICATION_TYPES -> PreviewKind.TEXT
        else -> PreviewKind.OTHER
    }
}

private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

/** Types outside `text/` the server previews as text. */
private val TEXT_APPLICATION_TYPES =
    setOf("application/json", "application/xml", "application/yaml", "application/x-yaml", "application/x-sh")

/** Where the image of file [id] is loaded from for its preview, with [FileImages.loader]. */
internal fun previewUrl(id: UUID) = "${PLACEHOLDER_BASE_URL}api/v1/files/$id/preview"

/** Where the server's small thumbnail of image file [id] is loaded from, with [FileImages.loader]. */
internal fun thumbnailUrl(id: UUID) = "${PLACEHOLDER_BASE_URL}api/v1/files/$id/thumbnail?size=$THUMBNAIL_SIZE"

/** The server makes thumbnails of 256 and 1024 pixels; a 40 dp list tile needs the small one. */
private const val THUMBNAIL_SIZE = 256

/** A folder on the way from the drive root to the one shown; the root itself is not one. */
data class FolderCrumb(
    val id: UUID,
    val name: String,
)

/**
 * A name the server rejects for its characters (it trims, so blanks count as empty; control characters such as tabs or
 * line breaks are refused), or `null` when it is fine.
 */
internal fun nameProblem(name: String): NameProblem? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> NameProblem.EMPTY
        trimmed.length > MAX_NAME_LENGTH -> NameProblem.TOO_LONG
        trimmed == "." || trimmed == ".." || trimmed.any { it == '/' || it == '\\' || it.isISOControl() } -> NameProblem.INVALID
        else -> null
    }
}

enum class NameProblem { EMPTY, TOO_LONG, INVALID }

internal const val MAX_NAME_LENGTH = 255

/** An entry of an unknown type, or without its folder or file, is skipped. */
internal fun DriveEntryResponse.toEntry(): DriveEntry? =
    when (type) {
        DriveEntryResponse.Type.FOLDER -> folder?.let { DriveEntry.Folder(it.id, it.name, it.special?.value, it.version) }
        DriveEntryResponse.Type.FILE -> file?.toFile()
    }

internal fun FileResponse.toFile() = DriveEntry.File(id, name, mime, propertySize, updatedAt.toInstant(), version)

private const val UNIT = 1024.0

/** `532 B`, `1.4 KB`, `12 MB`, `3.1 GB`: one decimal below 10, binary units as file managers show them. */
internal fun formatSize(bytes: Long): String {
    if (bytes < UNIT) return "$bytes B"
    var value = bytes / UNIT
    var unit = 0
    while (value >= UNIT && unit < UNITS.lastIndex) {
        value /= UNIT
        unit++
    }
    return if (value < 10) "%.1f %s".format(Locale.ROOT, value, UNITS[unit]) else "${value.toLong()} ${UNITS[unit]}"
}

private val UNITS = listOf("KB", "MB", "GB", "TB")
