package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.mail.list.MailAccount
import ch.sonensei.cockpit.mail.list.MailAddressDto
import ch.sonensei.cockpit.mail.list.MailHttpFailure
import ch.sonensei.cockpit.mail.list.MailResult
import ch.sonensei.cockpit.mail.list.mailBody
import ch.sonensei.cockpit.mail.list.mailCall
import ch.sonensei.cockpit.mail.list.toAccount
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import retrofit2.Response
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** How a send ended. */
sealed interface SendOutcome {
    data object Sent : SendOutcome

    /** The draft has no recipient (or is too large): nothing was sent. */
    data object Invalid : SendOutcome

    /** The SMTP server refused the message: nothing was sent. */
    data object Rejected : SendOutcome

    /** Another save came first: the draft changed meanwhile. */
    data object Conflict : SendOutcome

    data object Offline : SendOutcome

    data class Failed(
        val status: Int?,
    ) : SendOutcome
}

/** Drafts in the account's Drafts folder (SPEC.md §7.3, §7.4): save, attach, send, discard; reply and forward. */
@Singleton
class MailComposeRepository
    @Inject
    constructor(
        private val api: MailComposeApi,
    ) {
        /** The accounts to send from, by name. */
        suspend fun accounts(): MailResult<List<MailAccount>> =
            mailCall { mailBody(api.accounts()).items.map { it.toAccount() }.sortedBy { it.name.lowercase() } }

        /** Starts a reply, reply to all or forward of a message as a new draft. */
        suspend fun start(mode: ComposeMode): MailResult<Draft> =
            mailCall {
                when (mode) {
                    is ComposeMode.Reply -> draft(api.reply(mode.messageId.toString()))
                    is ComposeMode.ReplyAll -> draft(api.replyAll(mode.messageId.toString()))
                    is ComposeMode.Forward -> draft(api.forward(mode.messageId.toString()))
                    is ComposeMode.New -> throw IllegalArgumentException("A new mail has no draft to start from")
                }
            }

        suspend fun create(
            accountId: UUID,
            content: DraftContent,
        ): MailResult<Draft> =
            mailCall {
                draft(
                    api.create(
                        CreateDraftDto(
                            accountId = accountId.toString(),
                            to = content.to.map { it.dto() },
                            cc = content.cc.map { it.dto() },
                            bcc = content.bcc.map { it.dto() },
                            subject = content.subject,
                            html = content.html,
                        ),
                    ),
                )
            }

        /** Stores [content] as the new version of the draft [id] at version [etag]; `Failed(412)` when it changed meanwhile. */
        suspend fun update(
            id: UUID,
            etag: String,
            content: DraftContent,
        ): MailResult<Draft> =
            mailCall {
                draft(
                    api.update(
                        id.toString(),
                        etag,
                        DraftContentDto(
                            to = content.to.map { it.dto() },
                            cc = content.cc.map { it.dto() },
                            bcc = content.bcc.map { it.dto() },
                            subject = content.subject,
                            html = content.html,
                            attachments = content.keep.map { AttachmentRefDto(it) },
                        ),
                    ),
                )
            }

        /** The current version of draft [id]. */
        suspend fun get(id: UUID): MailResult<Draft> = mailCall { draft(api.get(id.toString())) }

        /** Uploads [file] into the draft; `Failed(400)` when it makes the mail too large. */
        suspend fun attach(
            id: UUID,
            etag: String,
            file: PickedFile,
        ): MailResult<Draft> =
            mailCall {
                val part = MultipartBody.Part.createFormData("file", file.name, PickedFileBody(file))
                draft(api.attach(id.toString(), etag, part))
            }

        suspend fun send(
            id: UUID,
            etag: String,
        ): SendOutcome =
            when (val result = mailCall { mailBody(api.send(id.toString(), etag)) }) {
                is MailResult.Ok -> SendOutcome.Sent
                MailResult.Offline -> SendOutcome.Offline
                is MailResult.Failed ->
                    when (result.status) {
                        BAD_REQUEST -> SendOutcome.Invalid
                        UNPROCESSABLE -> SendOutcome.Rejected
                        PRECONDITION_FAILED -> SendOutcome.Conflict
                        else -> SendOutcome.Failed(result.status)
                    }
            }

        /** Deletes the draft from the Drafts folder; an already deleted one counts as done. */
        suspend fun discard(
            id: UUID,
            etag: String,
        ): MailResult<Unit> =
            when (
                val result =
                    mailCall {
                        val response = api.delete(id.toString(), etag)
                        if (!response.isSuccessful) throw MailHttpFailure(response.code())
                    }
            ) {
                is MailResult.Failed -> if (result.status == NOT_FOUND) MailResult.Ok(Unit) else result
                else -> result
            }

        internal companion object {
            const val BAD_REQUEST = 400
            const val NOT_FOUND = 404
            const val PRECONDITION_FAILED = 412
            const val UNPROCESSABLE = 422
        }
    }

private fun draft(response: Response<DraftDto>): Draft {
    val etag = response.headers()["ETag"]
    val dto = mailBody(response)
    if (etag.isNullOrBlank()) throw IllegalArgumentException("A draft without ETag")
    return dto.toDraft(etag)
}

private fun Recipient.dto() = AddressRequestDto(address, name)

private fun DraftDto.toDraft(etag: String) =
    Draft(
        id = UUID.fromString(id),
        accountId = accountId?.let(UUID::fromString),
        etag = etag,
        to = to.mapNotNull { it.recipient() },
        cc = cc.mapNotNull { it.recipient() },
        bcc = bcc.mapNotNull { it.recipient() },
        subject = subject.orEmpty(),
        html = html.orEmpty(),
        text = text.orEmpty(),
        attachments =
            attachments.mapNotNull { a ->
                a.partId?.let { DraftAttachment(it, a.filename?.takeIf { n -> n.isNotBlank() } ?: it, a.sizeBytes, a.inline) }
            },
    )

private fun MailAddressDto.recipient(): Recipient? =
    address?.takeIf { it.isNotBlank() }?.let { Recipient(it, name?.takeIf { n -> n.isNotBlank() }) }

/** Streams the picked file when OkHttp writes the request, so a large file is never held in memory. */
private class PickedFileBody(
    private val file: PickedFile,
) : RequestBody() {
    override fun contentType(): MediaType? = file.mime.toMediaTypeOrNull()

    // Unknown: a provider's size may be wrong, and a wrong length breaks the request.
    override fun contentLength(): Long = -1

    override fun writeTo(sink: BufferedSink) {
        val input = file.open() ?: throw IOException("The file can no longer be read")
        input.source().use { sink.writeAll(it) }
    }
}
