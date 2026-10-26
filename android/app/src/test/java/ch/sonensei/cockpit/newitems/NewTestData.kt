package ch.sonensei.cockpit.newitems

import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.mail.list.MailSummary
import java.time.Instant
import java.util.UUID

/** Fictitious tasks and mails for the New view tests. */
internal object NewTestData {
    /** A UUIDv7 created at [at], told apart by [n]. */
    fun v7(
        at: Instant,
        n: Int,
    ): UUID {
        val ms = at.toEpochMilli()
        return UUID.fromString("%08x-%04x-7000-8000-%012d".format(ms ushr 16, ms and 0xFFFF, n))
    }

    fun task(
        title: String,
        createdAt: String,
        n: Int,
        notes: String? = null,
    ) = GtdTask(v7(Instant.parse(createdAt), n), title, TaskList.INBOX, n, 1, notesMd = notes)

    fun mail(
        subject: String,
        date: String?,
        n: Int,
        seen: Boolean = false,
    ) = MailSummary(
        id = UUID.fromString("00000000-0000-4000-9000-%012d".format(n)),
        accountId = null,
        sender = "Ada Example",
        subject = subject,
        snippet = "Snippet $n",
        date = date?.let(Instant::parse),
        seen = seen,
        flagged = false,
        hasAttachments = false,
    )
}
