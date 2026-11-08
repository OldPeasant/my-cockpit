package ch.sonensei.cockpit.mail.read

import ch.sonensei.cockpit.mail.list.MailActionRequestDto
import ch.sonensei.cockpit.mail.list.MailAddressDto
import ch.sonensei.cockpit.mail.list.MailApi
import ch.sonensei.cockpit.mail.list.MailListAction
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
    /** The type the mail gives it (`application/pdf`), `null` when it gives none. */
    val contentType: String? = null,
)

/**
 * A message with its sanitized body (SPEC.md §7.3): remote images were removed by the server ([remoteImagesBlocked]
 * counts them) or, when asked for, point to its image proxy.
 */
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
    /** The address of the (first) sender, for the per-sender image allowlist; `null` when it has none. */
    val fromAddress: String? = null,
    val flagged: Boolean = false,
    /** The account and folder the mail is in: "Move to folder" offers the account's other folders. */
    val accountId: UUID? = null,
    val folderId: UUID? = null,
)

/** How the server answered an action on one mail: [ok], or refused with its [errorCode] (e.g. `NO_ROLE_FOLDER`). */
data class MailActionOutcome(
    val ok: Boolean,
    val errorCode: String? = null,
)

/** What the GTD actions created (SPEC.md §7.3 "→ Inbox task", "→ Waiting For", and a note as the web app offers). */
enum class MailGtdAction { TASK, WAITING, NOTE }

/** One message, its GTD actions and archive/delete/spam on the server. */
@Singleton
class MailReadRepository
    @Inject
    constructor(
        private val api: MailApi,
    ) {
        /** The message [id]; with [proxyImages] its remote images point to the server's image proxy instead of being removed. */
        suspend fun message(
            id: UUID,
            proxyImages: Boolean = false,
        ): MailResult<MailMessage> = mailCall { mailBody(api.message(id.toString(), if (proxyImages) "PROXY" else null)).toMessage() }

        /** Creates an Inbox task, a Waiting For task or a note from [id], linked to it; the result is the item's title. */
        suspend fun gtd(
            id: UUID,
            action: MailGtdAction,
        ): MailResult<String> =
            mailCall {
                val item =
                    when (action) {
                        MailGtdAction.TASK -> mailBody(api.toTask(id.toString()))
                        MailGtdAction.WAITING -> mailBody(api.toWaiting(id.toString()))
                        MailGtdAction.NOTE -> mailBody(api.toNote(id.toString()))
                    }
                item.title.orEmpty()
            }

        /**
         * Applies [action] to [id] (moving it to [target] for MOVE); a refusal keeps the server's error code, so the view
         * can show it.
         */
        suspend fun act(
            id: UUID,
            action: MailListAction,
            target: UUID? = null,
        ): MailResult<MailActionOutcome> =
            mailCall {
                val request = MailActionRequestDto(action.wire, listOf(id.toString()), target?.toString())
                val result =
                    mailBody(api.act(request)).results.firstOrNull {
                        it.id ==
                            id.toString()
                    }
                MailActionOutcome(ok = result?.ok == true, errorCode = result?.errorCode?.takeIf { result.ok.not() })
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
                .map {
                    MailAttachment(
                        it.partId,
                        it.filename?.takeIf { n -> n.isNotBlank() } ?: it.partId,
                        it.sizeBytes,
                        it.contentType?.takeIf { type -> type.isNotBlank() },
                    )
                },
        fromAddress = from.firstNotNullOfOrNull { it.address?.trim()?.takeIf { address -> address.isNotEmpty() } },
        flagged = flagged,
        accountId = accountId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
        folderId = folderId?.let { runCatching { UUID.fromString(it) }.getOrNull() },
    )

/** "Name <address>", or whichever of the two there is. */
private fun MailAddressDto.full(): String {
    val shown = display()
    val mail = address.orEmpty()
    return if (shown == mail || mail.isEmpty()) shown else "$shown <$mail>"
}
