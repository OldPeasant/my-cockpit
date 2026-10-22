package ch.sonensei.cockpit.files

import ch.sonensei.cockpit.core.network.generated.models.DriveEntryResponse
import ch.sonensei.cockpit.core.network.generated.models.FileResponse
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** An entry of a folder as the app shows it (SPEC.md §8.2): a folder or a file. */
sealed interface DriveEntry {
    val id: UUID
    val name: String

    data class Folder(
        override val id: UUID,
        override val name: String,
        /** Set for the auto-managed folders (`ATTACHMENTS`, `TASKS`, ...). */
        val special: String?,
    ) : DriveEntry

    data class File(
        override val id: UUID,
        override val name: String,
        val mime: String,
        val size: Long,
        val updatedAt: Instant,
    ) : DriveEntry
}

/** A folder on the way from the drive root to the one shown; the root itself is not one. */
data class FolderCrumb(
    val id: UUID,
    val name: String,
)

/** An entry of an unknown type, or without its folder or file, is skipped. */
internal fun DriveEntryResponse.toEntry(): DriveEntry? =
    when (type) {
        DriveEntryResponse.Type.FOLDER -> folder?.let { DriveEntry.Folder(it.id, it.name, it.special?.value) }
        DriveEntryResponse.Type.FILE -> file?.toFile()
    }

internal fun FileResponse.toFile() = DriveEntry.File(id, name, mime, propertySize, updatedAt.toInstant())

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
