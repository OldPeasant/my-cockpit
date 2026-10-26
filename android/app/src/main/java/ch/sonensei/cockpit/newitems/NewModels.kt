package ch.sonensei.cockpit.newitems

import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.mail.list.MailSummary
import java.time.Instant
import java.util.UUID

/** What the New view shows: everything, only the GTD Inbox or only the unified mail inbox (SPEC.md §5.3). */
enum class NewFilter { ALL, INBOX, MAIL }

/** One row of the New view: a GTD Inbox task or a message of the unified inbox, with the time it sorts by. */
sealed interface NewRow {
    val key: String
    val at: Instant?

    data class Task(
        val task: GtdTask,
    ) : NewRow {
        override val key: String get() = "task:${task.id}"
        override val at: Instant? get() = createdAt(task.id)
    }

    data class Mail(
        val mail: MailSummary,
    ) : NewRow {
        override val key: String get() = "mail:${mail.id}"
        override val at: Instant? get() = mail.date
    }
}

/**
 * The rows of [filter], newest first: tasks by creation, mails by date. Rows without a time come last.
 *
 * Mails come in pages (newest first), tasks all at once. While older mails are still to load ([moreMail]), tasks
 * older than the oldest loaded mail are held back: shown now, they would end up above mails of the next page, and
 * loading more would insert rows above them instead of appending. They show once the mail pages reach them.
 */
fun mergeNew(
    tasks: List<GtdTask>,
    mails: List<MailSummary>,
    moreMail: Boolean,
    filter: NewFilter = NewFilter.ALL,
): List<NewRow> {
    val rows = mutableListOf<NewRow>()
    if (filter != NewFilter.MAIL) {
        val oldestMail = if (moreMail && filter == NewFilter.ALL) mails.mapNotNull { it.date }.minOrNull() else null
        tasks.map(NewRow::Task).filterTo(rows) { row -> oldestMail == null || (row.at ?: Instant.MIN) >= oldestMail }
    }
    if (filter != NewFilter.INBOX) mails.mapTo(rows, NewRow::Mail)
    return rows.sortedWith(compareByDescending<NewRow> { it.at ?: Instant.MIN }.thenBy { it.key })
}

/**
 * When the item with [id] was created: the millisecond timestamp of a UUIDv7 (SPEC.md §4.2, also for offline
 * captures); `null` for any other version.
 */
fun createdAt(id: UUID): Instant? = if (id.version() == UUID_V7) Instant.ofEpochMilli(id.mostSignificantBits ushr TIMESTAMP_SHIFT) else null

private const val UUID_V7 = 7
private const val TIMESTAMP_SHIFT = 16
