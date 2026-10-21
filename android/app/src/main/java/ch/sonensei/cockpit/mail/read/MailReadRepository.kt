package ch.sonensei.cockpit.mail.read

import ch.sonensei.cockpit.mail.list.MailAddressDto
import ch.sonensei.cockpit.mail.list.MailApi
import ch.sonensei.cockpit.mail.list.MailMessageDto
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.mail.list.display
import ch.sonensei.cockpit.mail.list.mailBody
import ch.sonensei.cockpit.mail.list.mailCall
import ch.sonensei.cockpit.mail.list.parseDate
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** An attachment as listed by the read view. */
data class MailAttachment(
    val partId: String,
    val name: String,
    val sizeBytes: Long?,
)

/** A message with its sanitized body (SPEC.md §7.3); remote images were removed by the server. */
data class MailMessage(
    /** The current id: a message that moved resolves to its new copy. */
    val id: UUID,
    val subject: String,
    val from: String,
    val to: List<String>,
    val cc: List<String>,
    val date: Instant?,
    val seen: Boolean,
    /** Sanitized HTML, or blank for a text-only mail. */
    val html: String,
    val text: String,
    val remoteImagesBlocked: Int,
    val attachments: List<MailAttachment>,
)

/** What the GTD actions created (SPEC.md §7.3 "→ Inbox task", "→ Waiting For"). */
enum class MailGtdAction { TASK, WAITING }

/** One message and its GTD actions on the server. */
@Singleton
class MailReadRepository
    @Inject
    constructor(
        private val api: MailApi,
    ) {
        suspend fun message(id: UUID): MailResult<MailMessage> = mailCall { mailBody(api.message(id.toString())).toMessage() }

        /** Creates an Inbox or Waiting For task from [id], linked to it; the result is the task's title. */
        suspend fun gtd(
            id: UUID,
            action: MailGtdAction,
        ): MailResult<String> =
            mailCall {
                val item =
                    when (action) {
                        MailGtdAction.TASK -> mailBody(api.toTask(id.toString()))
                        MailGtdAction.WAITING -> mailBody(api.toWaiting(id.toString()))
                    }
                item.title.orEmpty()
            }
    }

private fun MailMessageDto.toMessage() =
    MailMessage(
        id = UUID.fromString(id),
        subject = subject.orEmpty(),
        from = from.joinToString { it.full() },
        to = to.map { it.full() },
        cc = cc.map { it.full() },
        date = parseDate(sentAt) ?: parseDate(receivedAt),
        seen = seen,
        html = body?.html.orEmpty(),
        text = body?.text.orEmpty(),
        remoteImagesBlocked = body?.takeIf { it.remoteImagesBlocked }?.remoteImageCount ?: 0,
        attachments =
            attachments
                .filterNot { it.inline }
                .map { MailAttachment(it.partId, it.filename?.takeIf { n -> n.isNotBlank() } ?: it.partId, it.sizeBytes) },
    )

/** "Name <address>", or whichever of the two there is. */
private fun MailAddressDto.full(): String {
    val shown = display()
    val mail = address.orEmpty()
    return if (shown == mail || mail.isEmpty()) shown else "$shown <$mail>"
}
