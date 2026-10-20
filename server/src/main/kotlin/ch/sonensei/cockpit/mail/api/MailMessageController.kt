package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.mail.imap.MailConnectionError
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.sanitize.RemoteImagePolicy
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Reading mail: lists, single messages, conversations, attachments and the remote image proxy (SPEC.md §7.3, §7.4). */
@RestController
@RequestMapping("/api/v1/mail")
@Tag(
	name = "Mail messages",
	description =
		"Cached message lists (unified Inbox, an account's Inbox or a folder; flat or threaded), messages with " +
			"sanitized HTML and attachments, attachment downloads and the remote image proxy. Reading never marks a " +
			"message as seen. Mail server failures are 502 (503 when the account's connections are busy) with an " +
			"`errorCode`.",
)
class MailMessageController(
	private val reads: MailReadService,
	private val images: RemoteImageFetcher,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@GetMapping("/messages", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "listMailMessages",
		summary = "List messages, newest first",
		description =
			"`folderId` lists that folder (`accountId`, if given, must be its account); `accountId` alone lists the " +
				"account's Inbox; neither lists the unified Inbox of all accounts. With `threaded=true` each item is " +
				"the newest message of a conversation in that list, with `threadSize` and `threadUnread` counted in " +
				"the list; `GET /messages/{id}/thread` returns the whole conversation. Only cached headers are read.",
	)
	@ApiResponse(responseCode = "200", description = "One page of messages")
	@ApiResponse(
		responseCode = "400",
		description = "Malformed cursor, limit or a folder of another account",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such account or folder",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun list(
		@RequestParam(required = false) accountId: UUID?,
		@RequestParam(required = false) folderId: UUID?,
		@Parameter(description = "One item per conversation instead of per message")
		@RequestParam(required = false, defaultValue = "false") threaded: Boolean,
		@RequestParam(required = false) cursor: String?,
		@RequestParam(required = false) limit: Int?,
	): MailMessageListResponse {
		val page = reads.list(accountId, folderId, threaded, CursorRequest.of(cursor, limit))
		return MailMessageListResponse(page.items.map { it.message.toSummary(it.threadSize, it.threadUnread) }, page.nextCursor)
	}

	@GetMapping("/messages/{id}", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "getMailMessage",
		summary = "One message with headers, sanitized body and attachments",
		description =
			"Loads the body from the server on first access, then from the body cache. `body.html` is sanitized " +
				"(render it in a sandboxed iframe); remote images are removed (`remoteImages=BLOCK`, default) or " +
				"rewritten to the image proxy (`PROXY`), `cid:` images point to the attachment download. A message " +
				"that moved resolves to its current copy (its `id` may differ from the requested one).",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The message, with its version as ETag",
		headers = [Header(name = HttpHeaders.ETAG, schema = Schema(type = "string"))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Malformed id or unknown `remoteImages` value",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such message, or it is no longer on the server",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "502",
		description = "The mail server failed",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "503",
		description = "All connections of the account are busy (`errorCode` POOL_EXHAUSTED); retry later",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun get(
		@PathVariable id: UUID,
		@RequestParam(required = false, defaultValue = "BLOCK") remoteImages: RemoteImagePolicy,
	): ResponseEntity<MailMessageResponse> = reads.read(id, remoteImages).let { ETags.ok(it.toResponse(), it.message.version) }

	@GetMapping("/messages/{id}/thread", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "getMailThread",
		summary = "The conversation of a message",
		description = "Every cached message of the conversation in the message's account (all folders), oldest first.",
	)
	@ApiResponse(responseCode = "200", description = "The conversation")
	@ApiResponse(
		responseCode = "404",
		description = "No such message",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun thread(
		@PathVariable id: UUID,
	) = MailThreadResponse(reads.thread(id).map { it.toSummary(1, if (it.seen) 0 else 1) })

	@GetMapping("/messages/{id}/attachments/{partId}")
	@Operation(
		operationId = "downloadMailAttachment",
		summary = "Download an attachment",
		description =
			"Streams the decoded part from the mail server. Always a download (`Content-Disposition: attachment`), " +
				"except with `inline=true` for raster images (inline `cid:` images, previews).",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The attachment",
		content = [Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = Schema(type = "string", format = "binary"))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such message or attachment",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "502",
		description = "The mail server failed",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "503",
		description = "All connections of the account are busy (`errorCode` POOL_EXHAUSTED); retry later",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun attachment(
		@PathVariable id: UUID,
		@PathVariable partId: String,
		@RequestParam(required = false, defaultValue = "false") inline: Boolean,
		response: HttpServletResponse,
	) {
		reads.attachment(id, partId) { attachment ->
			val showInline = inline && attachment.contentType in RemoteImageFetcher.IMAGE_TYPES
			val filename = attachment.filename ?: "attachment"
			val disposition = if (showInline) ContentDisposition.inline() else ContentDisposition.attachment()
			response.contentType = if (showInline) attachment.contentType else MediaType.APPLICATION_OCTET_STREAM_VALUE
			response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(filename, Charsets.UTF_8).build().toString())
			untrustedContentHeaders(response)
			response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().headerValue)
			response.outputStream
		}
	}

	@GetMapping("/image-proxy")
	@Operation(
		operationId = "proxyMailImage",
		summary = "Load a remote image of a mail through the server",
		description =
			"Fetches an `http`/`https` raster image (no SVG) for \"load images\", so the sender never sees the " +
				"client. Only public internet hosts on ports 80/443; size and time are limited " +
				"(`cockpit.mail.read.image-proxy.*`). `GET /messages/{id}?remoteImages=PROXY` rewrites image URLs " +
				"to this endpoint.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The image",
		content = [Content(mediaType = "image/*", schema = Schema(type = "string", format = "binary"))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Not an http(s) URL, or not a public host or port",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "502",
		description = "The image could not be loaded, is too large or no supported image",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "504",
		description = "The image server did not answer in time",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun imageProxy(
		@RequestParam url: String,
		response: HttpServletResponse,
	): ResponseEntity<ByteArray> {
		val image = images.fetch(url)
		untrustedContentHeaders(response)
		return ResponseEntity
			.ok()
			.contentType(MediaType.parseMediaType(image.contentType))
			.cacheControl(CacheControl.maxAge(IMAGE_CACHE).cachePrivate())
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().build().toString())
			.body(image.bytes)
	}

	@ExceptionHandler(MailConnectionException::class)
	fun handleConnectionFailure(e: MailConnectionException): ResponseEntity<ProblemDetail> {
		// The cause may hold server responses; keep it at DEBUG, never with credentials.
		log.info("Mail server operation failed: {}", e.error)
		log.debug("Mail server failure", e.cause)
		val status = if (e.error == MailConnectionError.POOL_EXHAUSTED) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY
		val problem = ProblemDetail.forStatusAndDetail(status, e.error.message)
		problem.setProperty("errorCode", e.error.name)
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
	}

	@ExceptionHandler(AttachmentDownloadAbortedException::class)
	fun handleDownloadAborted(e: AttachmentDownloadAbortedException) {
		// The response is partly sent and the client is gone: nothing to answer, and no mail server failure.
		log.debug("Attachment download aborted by the client", e.cause)
	}

	@ExceptionHandler(RemoteImageException::class)
	fun handleImageFailure(e: RemoteImageException): ResponseEntity<ProblemDetail> {
		// The URL is mail content; it is not logged.
		log.debug("Proxying a remote image failed: {}", e.error, e.cause)
		val problem = ProblemDetail.forStatusAndDetail(e.error.status, e.error.message)
		problem.setProperty("errorCode", e.error.name)
		return ResponseEntity.status(e.error.status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
	}

	/** Content from mail is untrusted: never sniffed, never run as a document of our origin. */
	private fun untrustedContentHeaders(response: HttpServletResponse) {
		response.setHeader("X-Content-Type-Options", "nosniff")
		response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox")
	}

	private companion object {
		const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
		const val PROBLEM = "#/components/schemas/ProblemDetail"
		val IMAGE_CACHE: Duration = Duration.ofDays(1)
	}
}

data class MailMessageListResponse(
	val items: List<MailMessageSummaryResponse>,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "`null` on the last page")
	val nextCursor: String?,
)

data class MailThreadResponse(
	val items: List<MailMessageSummaryResponse>,
)

data class MailAddressResponse(
	val address: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val name: String?,
)

@Schema(description = "A list entry (cached headers)")
data class MailMessageSummaryResponse(
	val id: UUID,
	val accountId: UUID,
	val folderId: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val threadKey: String?,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val subject: String?,
	val from: List<MailAddressResponse>,
	val to: List<MailAddressResponse>,
	val cc: List<MailAddressResponse>,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "The `Date` header")
	val sentAt: Instant?,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Arrival in the mailbox (INTERNALDATE)")
	val receivedAt: Instant?,
	val seen: Boolean,
	val flagged: Boolean,
	val answered: Boolean,
	val draft: Boolean,
	val hasAttachments: Boolean,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val snippet: String?,
	val sizeBytes: Long,
	@field:Schema(description = "Messages of the conversation in the list (1 when not threaded)")
	val threadSize: Int,
	@field:Schema(description = "Unread messages of the conversation in the list")
	val threadUnread: Int,
)

data class MailBodyResponse(
	@field:Schema(
		requiredMode = RequiredMode.REQUIRED,
		nullable = true,
		description = "Sanitized HTML (body content only), `null` for plain-text mail; render in a sandboxed iframe",
	)
	val html: String?,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "The text/plain part, if any")
	val text: String?,
	@field:Schema(description = "Remote images in the HTML (blocked or proxied)")
	val remoteImageCount: Int,
	@field:Schema(description = "Remote images were removed: offer \"load images\" (`remoteImages=PROXY`)")
	val remoteImagesBlocked: Boolean,
)

data class MailAttachmentResponse(
	@field:Schema(description = "IMAP section, e.g. `2` or `1.2`") val partId: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val filename: String?,
	val contentType: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Estimated decoded size")
	val sizeBytes: Long?,
	@field:Schema(description = "A part of the body (e.g. a `cid:` image) rather than a real attachment")
	val inline: Boolean,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val contentId: String?,
	@field:Schema(description = "Relative download URL") val downloadUrl: String,
)

@Schema(description = "A message with headers, sanitized body and attachments")
data class MailMessageResponse(
	val id: UUID,
	val accountId: UUID,
	val folderId: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val threadKey: String?,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "The Message-ID header with angle brackets")
	val messageIdHeader: String?,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val inReplyTo: String?,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val references: String?,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val subject: String?,
	val from: List<MailAddressResponse>,
	val to: List<MailAddressResponse>,
	val cc: List<MailAddressResponse>,
	val bcc: List<MailAddressResponse>,
	val replyTo: List<MailAddressResponse>,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val sentAt: Instant?,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val receivedAt: Instant?,
	val seen: Boolean,
	val flagged: Boolean,
	val answered: Boolean,
	val draft: Boolean,
	val keywords: List<String>,
	val hasAttachments: Boolean,
	val sizeBytes: Long,
	val body: MailBodyResponse,
	val attachments: List<MailAttachmentResponse>,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
)

private fun List<MailAddress>.toResponse() = map { MailAddressResponse(it.address, it.name) }

private fun MailMessage.toSummary(
	threadSize: Int,
	threadUnread: Int,
) = MailMessageSummaryResponse(
	id = id,
	accountId = accountId,
	folderId = checkNotNull(folderId),
	threadKey = threadKey,
	subject = subject,
	from = from.toResponse(),
	to = to.toResponse(),
	cc = cc.toResponse(),
	sentAt = sentAt,
	receivedAt = receivedAt,
	seen = seen,
	flagged = flagged,
	answered = answered,
	draft = draft,
	hasAttachments = hasAttachments,
	snippet = snippet,
	sizeBytes = sizeBytes,
	threadSize = threadSize,
	threadUnread = threadUnread,
)

private fun MailMessageView.toResponse() =
	MailMessageResponse(
		id = message.id,
		accountId = message.accountId,
		folderId = checkNotNull(message.folderId),
		threadKey = message.threadKey,
		messageIdHeader = message.messageIdHeader,
		inReplyTo = message.inReplyTo,
		references = message.referencesHeader,
		subject = message.subject,
		from = message.from.toResponse(),
		to = message.to.toResponse(),
		cc = message.cc.toResponse(),
		bcc = message.bcc.toResponse(),
		replyTo = message.replyTo.toResponse(),
		sentAt = message.sentAt,
		receivedAt = message.receivedAt,
		seen = message.seen,
		flagged = message.flagged,
		answered = message.answered,
		draft = message.draft,
		keywords = message.keywords.toList(),
		hasAttachments = message.hasAttachments,
		sizeBytes = message.sizeBytes,
		body = MailBodyResponse(body.html, body.text, body.remoteImageCount, body.remoteImagesBlocked),
		attachments =
			attachments.map {
				MailAttachmentResponse(
					it.partId,
					it.filename,
					it.contentType,
					it.sizeBytes,
					it.inline,
					it.contentId,
					MailReadService.attachmentUrl(message.id, it.partId),
				)
			},
		createdAt = checkNotNull(message.createdAt),
		updatedAt = checkNotNull(message.updatedAt),
		version = message.version,
	)
