package ch.sonensei.cockpit.trash

import androidx.annotation.StringRes
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.core.network.generated.models.TrashItemResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** One deleted item. [type] is the server's item type (`TASK`, `NOTE`, ...). */
data class TrashItem(
    val id: UUID,
    val type: String,
    val title: String,
    val subtitle: String?,
    val deletedAt: Instant,
    /** When the server deletes it for good (30 days after [deletedAt]). */
    val purgeAt: Instant,
) {
    /** Tells items apart across types (ids are unique per type only). */
    val key: String get() = "$type:$id"
}

/** Whole days until [purgeAt], rounded up; 0 once it is due. */
fun daysUntilPurge(
    purgeAt: Instant,
    now: Instant,
): Int {
    val left = Duration.between(now, purgeAt)
    if (left.isNegative || left.isZero) return 0
    val days = left.toDays()
    return (if (left == Duration.ofDays(days)) days else days + 1).toInt()
}

/** From this many days left on, the countdown is shown as urgent (as on the web). */
const val PURGE_SOON_DAYS = 3

/** The singular name of an item type, shown next to its title. */
@StringRes
fun trashTypeLabel(type: String): Int =
    when (type) {
        "TASK" -> R.string.trash_type_task
        "PROJECT" -> R.string.trash_type_project
        "NOTE" -> R.string.trash_type_note
        "EVENT" -> R.string.trash_type_event
        "EMAIL" -> R.string.trash_type_email
        "FILE" -> R.string.trash_type_file
        "FOLDER" -> R.string.trash_type_folder
        "CONTACT" -> R.string.trash_type_contact
        "BOOKMARK" -> R.string.trash_type_bookmark
        "JOURNAL_ENTRY" -> R.string.trash_type_journal
        "HABIT" -> R.string.trash_type_habit
        else -> R.string.trash_type_other
    }

internal fun TrashItemResponse.toTrashItem(): TrashItem =
    TrashItem(
        id = id,
        type = type.value,
        title = title,
        subtitle = subtitle?.takeIf(String::isNotBlank),
        deletedAt = deletedAt.toInstant(),
        purgeAt = purgeAt.toInstant(),
    )
