package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.web.CursorPage
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageLocator
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import ch.sonensei.cockpit.mail.sanitize.HtmlMailSanitizer
import ch.sonensei.cockpit.mail.sanitize.RemoteImagePolicy
import ch.sonensei.cockpit.mail.sanitize.SanitizeOptions
import org.jsoup.Jsoup
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/** A list entry: the message plus its conversation counts (see [MailListRow]). */
data class MailListEntry(
	val message: MailMessage,
	val threadSize: Int,
	val threadUnread: Int,
)

/** The client stopped an attachment download (writing the response failed); not a mail server failure. */
class AttachmentDownloadAbortedException(
	cause: IOException,
) : RuntimeException("The client aborted the download", cause)

/** The safe body of a message: sanitized HTML and/or plain text. */
data class MailBodyView(
	val html: String?,
	val text: String?,
	val remoteImageCount: Int,
	val remoteImagesBlocked: Boolean,
)

data class MailMessageView(
	val message: MailMessage,
	val body: MailBodyView,
	val attachments: List<MailAttachment>,
)

/**
 * Reading mail (SPEC.md §7.3, §7.4): lists from the header cache, single messages with their sanitized body and
 * attachments (loaded through [MailContentLoader]), conversations and attachment downloads. A message id whose row is
 * no longer located (moved, UIDVALIDITY changed) resolves to its current copy ([MailMessageLocator]).
 */
@Service
class MailReadService(
	private val locator: MailMessageLocator,
	private val messages: MailMessageRepository,
	private val accounts: MailAccountRepository,
	private val folders: MailFolderRepository,
	private val listQuery: MailMessageListQuery,
	private val loader: MailContentLoader,
	private val sanitizer: HtmlMailSanitizer,
) {
	/**
	 * The list for a folder ([folderId]), an account's Inbox ([accountId] alone) or the unified Inbox (neither).
	 *
	 * @throws NotFoundException for an unknown account or folder
	 * @throws InvalidRequestException when the folder is not one of [accountId]'s, or for a malformed cursor
	 */
	@Transactional(readOnly = true)
	fun list(
		accountId: UUID?,
		folderId: UUID?,
		threaded: Boolean,
		page: CursorRequest,
	): CursorPage<MailListEntry> {
		val scope = scope(accountId, folderId)
		val rows = listQuery.rows(scope, threaded, page.position?.let(::position), page.limit + 1)
		val byId = messages.findAllById(rows.map { it.messageId }).associateBy { it.id }
		val entries = rows.mapNotNull { row -> byId[row.messageId]?.let { MailListEntry(it, row.threadSize, row.threadUnread) } }
		val sortAts = rows.associate { it.messageId to it.sortAt }
		return page.page(entries) { "${sortAts.getValue(it.message.id)}|${it.message.id}" }
	}

	/** The current copy of a message, without body. */
	@Transactional(readOnly = true)
	fun get(id: UUID): MailMessage = locator.current(id) ?: throw NotFoundException(MESSAGE_NOT_FOUND)

	/**
	 * A message with its body: HTML sanitized with remote images blocked or proxied ([remoteImages]), `cid:` images
	 * pointing to the attachment download, plain text as is.
	 */
	fun read(
		id: UUID,
		remoteImages: RemoteImagePolicy,
	): MailMessageView {
		val message = get(id)
		val content = loader.load(message)
		val body =
			content.textHtml?.let { html ->
				val options = SanitizeOptions(remoteImages, if (remoteImages == RemoteImagePolicy.PROXY) ::proxyUrl else null)
				val sanitized = sanitizer.sanitize(html, options)
				MailBodyView(
					resolveInlineImages(sanitized.html, message.id, content.attachments),
					content.textPlain,
					sanitized.remoteImageCount,
					sanitized.remoteImagesBlocked,
				)
			} ?: MailBodyView(null, content.textPlain, 0, false)
		return MailMessageView(message, body, content.attachments)
	}

	/** The located messages of the conversation of [id] in its account, oldest first, one per Message-ID. */
	@Transactional(readOnly = true)
	fun thread(id: UUID): List<MailMessage> {
		val message = get(id)
		val threadKey = message.threadKey ?: return listOf(message)
		return messages
			.findByAccountIdAndThreadKeyAndFolderIdIsNotNullAndUidIsNotNullOrderByReceivedAtAscIdAsc(message.accountId, threadKey)
			.sortedByDescending { it.id == message.id }
			.distinctBy { it.messageIdHeader ?: it.id.toString() }
			.sortedWith(compareBy<MailMessage, Instant?>(nullsLast()) { it.receivedAt }.thenBy { it.id })
	}

	/**
	 * Streams attachment [partId] of message [id] into the stream [open] returns for it (the HTTP response), while the
	 * IMAP connection is held.
	 *
	 * @throws NotFoundException when the message has no such attachment
	 * @throws AttachmentDownloadAbortedException when writing to the client failed (it went away); the mail server
	 *   was fine, so its connection is kept
	 */
	fun attachment(
		id: UUID,
		partId: String,
		open: (MailAttachment) -> OutputStream,
	) {
		val message = get(id)
		val attachment = loader.load(message).attachments.firstOrNull { it.partId == partId } ?: throw NotFoundException(ATTACHMENT_NOT_FOUND)
		// A client failure or a missing part is returned, not thrown, inside the block: a throw would count as an IMAP
		// failure there.
		val outcome =
			loader.withRemoteMessage(message) { root ->
				val part = MimeContent.findPart(root, partId) ?: return@withRemoteMessage AttachmentCopy.PartMissing
				part.inputStream.use { copy(it, open(attachment)) }?.let { AttachmentCopy.ClientFailed(it) } ?: AttachmentCopy.Done
			}
		when (outcome) {
			AttachmentCopy.Done -> Unit
			AttachmentCopy.PartMissing -> throw NotFoundException(ATTACHMENT_NOT_FOUND)
			is AttachmentCopy.ClientFailed -> throw AttachmentDownloadAbortedException(outcome.cause)
		}
	}

	/** How streaming an attachment inside the IMAP block ended. */
	private sealed interface AttachmentCopy {
		data object Done : AttachmentCopy

		data object PartMissing : AttachmentCopy

		data class ClientFailed(
			val cause: IOException,
		) : AttachmentCopy
	}

	/**
	 * Copies [input] (the mail server) to [output] (the client). Read failures are thrown; a write failure ends the
	 * copy and is returned.
	 */
	private fun copy(
		input: InputStream,
		output: OutputStream,
	): IOException? {
		val buffer = ByteArray(COPY_BUFFER_SIZE)
		while (true) {
			val read = input.read(buffer)
			if (read < 0) break
			try {
				output.write(buffer, 0, read)
			} catch (e: IOException) {
				return e
			}
		}
		return try {
			output.flush()
			null
		} catch (e: IOException) {
			e
		}
	}

	private fun scope(
		accountId: UUID?,
		folderId: UUID?,
	): MailListScope {
		if (folderId != null) {
			val folder = folders.findById(folderId).orElse(null) ?: throw NotFoundException("Mail folder not found")
			if (accountId != null && folder.accountId != accountId) {
				throw InvalidRequestException("folderId", "is not a folder of the account")
			}
			return MailListScope.Folder(folderId)
		}
		if (accountId != null) {
			if (!accounts.existsById(accountId)) throw NotFoundException("Mail account not found")
			return MailListScope.AccountInbox(accountId)
		}
		return MailListScope.Unified
	}

	private fun position(value: String): MailListPosition {
		val parts = value.split('|')
		return try {
			require(parts.size == 2)
			MailListPosition(Instant.parse(parts[0]), UUID.fromString(parts[1]))
		} catch (_: IllegalArgumentException) {
			throw InvalidRequestException("cursor", "is not a valid cursor")
		} catch (_: DateTimeParseException) {
			throw InvalidRequestException("cursor", "is not a valid cursor")
		}
	}

	/**
	 * Points `cid:` images at the inline download of their part; unknown ones lose their `src`. The reference is
	 * URL-encoded (RFC 2392), so it is compared percent-decoded, and as written for mailers that do not encode it.
	 */
	private fun resolveInlineImages(
		html: String,
		messageId: UUID,
		attachments: List<MailAttachment>,
	): String {
		if (!html.contains("cid:", ignoreCase = true)) return html
		val document = Jsoup.parseBodyFragment(html)
		document.outputSettings().prettyPrint(false).charset(Charsets.UTF_8)
		document.select("img[src]").forEach { img ->
			val src = img.attr("src").trim()
			if (!src.startsWith("cid:", ignoreCase = true)) return@forEach
			val contentId = src.substring(CID_PREFIX_LENGTH).removePrefix("<").removeSuffix(">")
			val decoded = percentDecoded(contentId)?.removePrefix("<")?.removeSuffix(">")
			val part =
				attachments.firstOrNull { it.contentId.equals(decoded, ignoreCase = true) }
					?: attachments.firstOrNull { it.contentId.equals(contentId, ignoreCase = true) }
			if (part == null) img.removeAttr("src") else img.attr("src", attachmentUrl(messageId, part.partId, inline = true))
		}
		return document.body().html()
	}

	/** [value] with its `%XX` escapes decoded as UTF-8 (`+` stays a plus); `null` when an escape is malformed. */
	private fun percentDecoded(value: String): String? =
		try {
			URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8)
		} catch (_: IllegalArgumentException) {
			null
		}

	companion object {
		const val MESSAGES_PATH = "/api/v1/mail/messages"
		const val IMAGE_PROXY_PATH = "/api/v1/mail/image-proxy"
		private const val MESSAGE_NOT_FOUND = "Mail message not found"
		private const val ATTACHMENT_NOT_FOUND = "Attachment not found"
		private const val CID_PREFIX_LENGTH = 4
		private const val COPY_BUFFER_SIZE = 16 * 1024

		fun attachmentUrl(
			messageId: UUID,
			partId: String,
			inline: Boolean = false,
		) = "$MESSAGES_PATH/$messageId/attachments/$partId" + if (inline) "?inline=true" else ""

		fun proxyUrl(remoteUrl: String) = "$IMAGE_PROXY_PATH?url=" + URLEncoder.encode(remoteUrl, Charsets.UTF_8)
	}
}
