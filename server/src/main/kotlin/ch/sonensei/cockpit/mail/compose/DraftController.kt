package ch.sonensei.cockpit.mail.compose

import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.mail.api.MailAddressResponse
import ch.sonensei.cockpit.mail.api.MailAttachmentResponse
import ch.sonensei.cockpit.mail.api.MailReadService
import ch.sonensei.cockpit.mail.model.MailAddress
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.MediaTypeFactory
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.time.Instant
import java.util.UUID

/** Compose, reply, forward and send (SPEC.md §7.3, §7.4). */
@RestController
@RequestMapping("/api/v1/mail")
@Tag(
	name = "Mail compose",
	description =
		"Drafts are stored in the account's Drafts folder on the mail server; every save replaces the stored version. " +
			"A draft's `ETag` is the IMAP UID of its current version: saving, attaching, deleting and sending need it in " +
			"`If-Match` (412 when another save came first). The draft keeps its id across saves. Changes show as " +
			"`MAIL_FOLDER` change events of the Drafts (and Sent) folder.",
)
class DraftController(
	private val drafts: DraftService,
	private val correspondents: CorrespondentService,
	private val properties: ComposeProperties,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@PostMapping("/drafts", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "createMailDraft",
		summary = "Save a new draft",
		description =
			"Builds the message (HTML with a plain-text alternative, derived from the HTML when `text` is missing; `From` " +
				"and `Reply-To` from the identity, by default the account's default identity) and appends it to the " +
				"account's Drafts folder. Attachments can only be files (`fileId`) here; upload others afterwards.",
	)
	@DraftCreatedResponse
	@MailFailureResponses
	@ApiResponse(
		responseCode = "409",
		description = "The account has no Drafts folder",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun create(
		@Valid @RequestBody request: CreateDraftRequest,
	): ResponseEntity<DraftResponse> {
		val draft = drafts.create(checkNotNull(request.accountId), request.toInput())
		return created(draft)
	}

	@GetMapping("/drafts/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getMailDraft", summary = "A draft with its content, for editing")
	@DraftOkResponse
	@MailFailureResponses
	fun get(
		@PathVariable id: UUID,
	): ResponseEntity<DraftResponse> = drafts.get(id).let { ETags.ok(it.toResponse(), it.version) }

	@PutMapping("/drafts/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "updateMailDraft",
		summary = "Save a draft (autosave)",
		description =
			"Replaces recipients, subject, body and identity; `In-Reply-To`/`References` of a reply stay. `attachments` " +
				"lists what the new version keeps (`partId` of the current version) or adds (`fileId`); omitted, the " +
				"current attachments stay.",
	)
	@DraftOkResponse
	@MailFailureResponses
	@IfMatchResponses
	fun update(
		@PathVariable id: UUID,
		@Parameter(description = "The draft's ETag", required = true) @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@Valid @RequestBody request: DraftContentRequest,
	): ResponseEntity<DraftResponse> = drafts.update(id, ifMatch, request.toInput()).let { ETags.ok(it.toResponse(), it.version) }

	@PostMapping(
		"/drafts/{id}/attachments",
		consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
		produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON],
	)
	@Operation(
		operationId = "uploadMailDraftAttachment",
		summary = "Upload an attachment into a draft",
		description = "Adds the file as an attachment and saves the draft; everything else stays.",
	)
	@DraftOkResponse
	@MailFailureResponses
	@IfMatchResponses
	fun attach(
		@PathVariable id: UUID,
		@Parameter(description = "The draft's ETag", required = true) @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestPart("file") file: MultipartFile,
	): ResponseEntity<DraftResponse> {
		if (file.size > properties.maxMessageSize.toBytes()) {
			throw InvalidRequestException("file", "must not be larger than ${properties.maxMessageSize.toMegabytes()} MB")
		}
		val name =
			file.originalFilename
				?.substringAfterLast('/')
				?.substringAfterLast('\\')
				?.takeIf { it.isNotBlank() } ?: "attachment"
		val type =
			file.contentType?.takeIf { it.isNotBlank() && it != MediaType.APPLICATION_OCTET_STREAM_VALUE }
				?: MediaTypeFactory.getMediaType(name).map { it.toString() }.orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE)
		return drafts.attach(id, ifMatch, DraftUpload(name, type, file.bytes)).let { ETags.ok(it.toResponse(), it.version) }
	}

	@DeleteMapping("/drafts/{id}", produces = [PROBLEM_JSON])
	@Operation(operationId = "deleteMailDraft", summary = "Discard a draft", description = "Deletes it from the Drafts folder on the server.")
	@ApiResponse(responseCode = "204", description = "Deleted")
	@ApiResponse(
		responseCode = "404",
		description = "No such draft",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@MailFailureResponses
	@IfMatchResponses
	fun delete(
		@PathVariable id: UUID,
		@Parameter(description = "The draft's ETag", required = true) @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<Void> {
		drafts.delete(id, ifMatch)
		return ResponseEntity.noContent().build()
	}

	@PostMapping("/drafts/{id}/send", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "sendMailDraft",
		summary = "Send a draft",
		description =
			"Sends the stored version over SMTP (XOAUTH2 for Google and Microsoft), appends it to the Sent folder (not for " +
				"Google and Microsoft, whose servers do that themselves) and deletes the draft. Once the server accepted " +
				"the message the request succeeds; `appendedToSent` and `draftRemoved` tell whether the rest worked.",
	)
	@ApiResponse(responseCode = "200", description = "Sent")
	@ApiResponse(
		responseCode = "400",
		description =
			"The draft has no recipient (field `to`) or is larger than the size limit (field `attachments`, e.g. after " +
				"another mail client changed it); nothing was sent",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such draft",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "422",
		description = "The SMTP server refused the message (`errorCode` SEND_REJECTED, `rejectedRecipients`); nothing was sent",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@MailFailureResponses
	@IfMatchResponses
	fun send(
		@PathVariable id: UUID,
		@Parameter(description = "The draft's ETag", required = true) @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): SendDraftResponse = drafts.send(id, ifMatch).let { SendDraftResponse(it.sentMessageId, it.appendedToSent, it.draftRemoved) }

	@PostMapping("/messages/{id}/reply", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "replyToMailMessage",
		summary = "Start a reply as a new draft",
		description =
			"To the original's `Reply-To`, else `From` (to its `To` when you sent it), subject with `Re: `, `In-Reply-To` " +
				"and `References` set, the original quoted below an empty first paragraph.",
	)
	@DraftCreatedResponse
	@MailFailureResponses
	fun reply(
		@PathVariable id: UUID,
		@Parameter(description = "Send as this identity; default: the one the original was addressed to, else the default")
		@RequestParam(required = false) identityId: UUID?,
	): ResponseEntity<DraftResponse> = created(drafts.respond(id, ResponseMode.REPLY, identityId))

	@PostMapping("/messages/{id}/reply-all", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "replyAllToMailMessage",
		summary = "Start a reply to all as a new draft",
		description = "Like a reply, plus the original's other `To` and `Cc` recipients, without your own addresses.",
	)
	@DraftCreatedResponse
	@MailFailureResponses
	fun replyAll(
		@PathVariable id: UUID,
		@Parameter(description = "Send as this identity; default: the one the original was addressed to, else the default")
		@RequestParam(required = false) identityId: UUID?,
	): ResponseEntity<DraftResponse> = created(drafts.respond(id, ResponseMode.REPLY_ALL, identityId))

	@PostMapping("/messages/{id}/forward", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "forwardMailMessage",
		summary = "Start a forward as a new draft",
		description = "No recipients, subject with `Fwd: `, the original's headers and body, and all its attachments.",
	)
	@DraftCreatedResponse
	@MailFailureResponses
	fun forward(
		@PathVariable id: UUID,
		@Parameter(description = "Send as this identity; default: the one the original was addressed to, else the default")
		@RequestParam(required = false) identityId: UUID?,
	): ResponseEntity<DraftResponse> = created(drafts.respond(id, ResponseMode.FORWARD, identityId))

	@GetMapping("/correspondents", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "listMailCorrespondents",
		summary = "Recent correspondents, for recipient autocomplete",
		description =
			"Addresses you recently wrote to (Sent) or got mail from, newest first, without your own addresses. `q` " +
				"filters by address or name (contains, case-insensitive).",
	)
	@ApiResponse(responseCode = "200", description = "The correspondents")
	@ApiResponse(
		responseCode = "400",
		description = "Invalid `limit`",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun correspondents(
		@RequestParam(required = false) q: String?,
		@Parameter(description = "1 to $MAX_CORRESPONDENTS, default $DEFAULT_CORRESPONDENTS")
		@RequestParam(required = false) limit: Int?,
	): CorrespondentListResponse {
		val max = limit ?: DEFAULT_CORRESPONDENTS
		if (max !in 1..MAX_CORRESPONDENTS) throw InvalidRequestException("limit", "must be between 1 and $MAX_CORRESPONDENTS")
		return CorrespondentListResponse(correspondents.recent(q, max).map { CorrespondentResponse(it.address, it.name, it.lastAt) })
	}

	@ExceptionHandler(MailSendRejectedException::class)
	fun handleSendRejected(e: MailSendRejectedException): ResponseEntity<ProblemDetail> {
		log.info("The SMTP server refused a message ({} recipients rejected)", e.rejected.size)
		log.debug("SMTP refusal", e.cause)
		val problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.message ?: "")
		problem.setProperty("errorCode", "SEND_REJECTED")
		problem.setProperty("rejectedRecipients", e.rejected)
		return ResponseEntity.unprocessableContent().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
	}

	private fun created(draft: Draft): ResponseEntity<DraftResponse> {
		val location =
			ServletUriComponentsBuilder
				.fromCurrentContextPath()
				.path("/api/v1/mail/drafts/{id}")
				.buildAndExpand(draft.message.id)
				.toUri()
		return ResponseEntity.created(location).eTag(ETags.of(draft.version)).body(draft.toResponse())
	}

	@Target(AnnotationTarget.FUNCTION)
	@Retention(AnnotationRetention.RUNTIME)
	@ApiResponse(
		responseCode = "201",
		description = "Saved in the Drafts folder; `Location` is the draft, `ETag` its version",
		headers = [
			Header(
				name = HttpHeaders.LOCATION,
				schema = Schema(type = "string"),
			), Header(name = HttpHeaders.ETAG, schema = Schema(type = "string")),
		],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = DraftResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Invalid request (addresses, identity of another account, unknown attachment, too large)",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such account, message or file",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	private annotation class DraftCreatedResponse

	@Target(AnnotationTarget.FUNCTION)
	@Retention(AnnotationRetention.RUNTIME)
	@ApiResponse(
		responseCode = "200",
		description = "The draft, with its version as `ETag`",
		headers = [Header(name = HttpHeaders.ETAG, schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = DraftResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Invalid request (addresses, identity of another account, unknown attachment, too large)",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such draft (or file)",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	private annotation class DraftOkResponse

	@Target(AnnotationTarget.FUNCTION)
	@Retention(AnnotationRetention.RUNTIME)
	@ApiResponse(
		responseCode = "412",
		description = "If-Match is stale",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "428",
		description = "If-Match is missing",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	private annotation class IfMatchResponses

	@Target(AnnotationTarget.FUNCTION)
	@Retention(AnnotationRetention.RUNTIME)
	@ApiResponse(
		responseCode = "502",
		description = "The mail server failed (`errorCode`, e.g. CONNECTION_FAILED, AUTHENTICATION_FAILED)",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "503",
		description = "All connections of the account are busy (`errorCode` POOL_EXHAUSTED); retry later",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	private annotation class MailFailureResponses

	private companion object {
		const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
		const val PROBLEM = "#/components/schemas/ProblemDetail"
		const val DEFAULT_CORRESPONDENTS = 10
		const val MAX_CORRESPONDENTS = 50
	}
}

class MailAddressRequest(
	@field:NotBlank
	@field:Size(max = 320)
	@field:Schema(requiredMode = RequiredMode.REQUIRED, example = "ann@example.com")
	val address: String?,
	@field:Size(max = 200)
	val name: String? = null,
)

@Schema(description = "Exactly one of `partId` (keep an attachment of the current version) and `fileId` (a file of the drive)")
class DraftAttachmentRequest(
	@field:Size(max = 60)
	val partId: String? = null,
	val fileId: UUID? = null,
)

// Nullable fields so a missing value reaches Bean Validation (400 with field errors) instead of failing in Jackson.
class DraftContentRequest(
	@field:Schema(description = "Identity of the account to send as; default: the account's default identity")
	val identityId: UUID? = null,
	@field:Valid @field:Size(max = MAX_RECIPIENTS) val to: List<MailAddressRequest>? = null,
	@field:Valid @field:Size(max = MAX_RECIPIENTS) val cc: List<MailAddressRequest>? = null,
	@field:Valid @field:Size(max = MAX_RECIPIENTS) val bcc: List<MailAddressRequest>? = null,
	@field:Size(max = 998) val subject: String? = null,
	@field:Schema(description = "The editor's HTML (body content)")
	val html: String? = null,
	@field:Schema(description = "Plain-text alternative; default: derived from `html`")
	val text: String? = null,
	@field:Valid
	@field:Size(max = MAX_ATTACHMENTS)
	@field:Schema(description = "The attachments of the new version; omitted: unchanged (create: none)")
	val attachments: List<DraftAttachmentRequest>? = null,
) {
	fun toInput(): DraftInput =
		DraftInput(
			identityId = identityId,
			to = addresses("to", to),
			cc = addresses("cc", cc),
			bcc = addresses("bcc", bcc),
			subject = subject,
			html = html,
			text = text,
			attachments =
				attachments?.mapIndexed { i, ref ->
					when {
						ref.partId != null && ref.fileId == null -> DraftAttachmentRef.Existing(ref.partId)
						ref.fileId != null && ref.partId == null -> DraftAttachmentRef.File(ref.fileId)
						else -> throw InvalidRequestException("attachments[$i]", "needs exactly one of partId and fileId")
					}
				},
		)

	private fun addresses(
		field: String,
		list: List<MailAddressRequest>?,
	): List<MailAddress> =
		list.orEmpty().mapIndexed { i, request ->
			val parsed =
				try {
					InternetAddress(request.address.orEmpty().trim(), true).also { it.validate() }
				} catch (_: AddressException) {
					null
				}
			// Only a bare addr-spec: a display name belongs in `name`, and a group would expand to several recipients.
			if (parsed == null || parsed.isGroup || parsed.personal != null || parsed.address.isNullOrBlank()) {
				throw InvalidRequestException("$field[$i].address", "is not a valid email address")
			}
			MailAddress(parsed.address, request.name?.trim()?.takeIf { it.isNotEmpty() })
		}

	companion object {
		const val MAX_RECIPIENTS = 500
		const val MAX_ATTACHMENTS = 100
	}
}

class CreateDraftRequest(
	@field:NotNull
	@field:Schema(requiredMode = RequiredMode.REQUIRED, description = "The account whose Drafts folder stores the draft")
	val accountId: UUID?,
	@field:Schema(description = "Identity of the account to send as; default: the account's default identity")
	val identityId: UUID? = null,
	@field:Valid @field:Size(max = DraftContentRequest.MAX_RECIPIENTS) val to: List<MailAddressRequest>? = null,
	@field:Valid @field:Size(max = DraftContentRequest.MAX_RECIPIENTS) val cc: List<MailAddressRequest>? = null,
	@field:Valid @field:Size(max = DraftContentRequest.MAX_RECIPIENTS) val bcc: List<MailAddressRequest>? = null,
	@field:Size(max = 998) val subject: String? = null,
	@field:Schema(description = "The editor's HTML (body content)")
	val html: String? = null,
	@field:Schema(description = "Plain-text alternative; default: derived from `html`")
	val text: String? = null,
	@field:Valid
	@field:Size(max = DraftContentRequest.MAX_ATTACHMENTS)
	@field:Schema(description = "Files of the drive to attach (`fileId` only)")
	val attachments: List<DraftAttachmentRequest>? = null,
) {
	fun toInput(): DraftInput = DraftContentRequest(identityId, to, cc, bcc, subject, html, text, attachments).toInput()
}

@Schema(description = "A draft as stored in the Drafts folder")
data class DraftResponse(
	@field:Schema(description = "Stays the same across saves") val id: UUID,
	val accountId: UUID,
	val folderId: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "`null` when `from` matches no identity")
	val identityId: UUID?,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val from: MailAddressResponse?,
	val to: List<MailAddressResponse>,
	val cc: List<MailAddressResponse>,
	val bcc: List<MailAddressResponse>,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val subject: String?,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Sanitized HTML (body content)")
	val html: String?,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val text: String?,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "Message-ID of the message answered")
	val inReplyTo: String?,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val references: String?,
	val attachments: List<MailAttachmentResponse>,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true, description = "When this version was saved")
	val savedAt: Instant?,
)

data class SendDraftResponse(
	@field:Schema(
		requiredMode = RequiredMode.REQUIRED,
		nullable = true,
		description = "The message in the Sent folder, once the cache has it (later for Google and Microsoft)",
	)
	val sentMessageId: UUID?,
	@field:Schema(description = "A copy was appended to the Sent folder (false for Google/Microsoft: their servers do it)")
	val appendedToSent: Boolean,
	@field:Schema(description = "The draft was deleted from the Drafts folder")
	val draftRemoved: Boolean,
)

data class CorrespondentListResponse(
	val items: List<CorrespondentResponse>,
)

data class CorrespondentResponse(
	val address: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED, nullable = true) val name: String?,
	@field:Schema(description = "The most recent mail with them") val lastAt: Instant,
)

private fun Draft.toResponse() =
	DraftResponse(
		id = message.id,
		accountId = message.accountId,
		folderId = checkNotNull(message.folderId),
		identityId = identityId,
		from = message.from.firstOrNull()?.let { MailAddressResponse(it.address, it.name) },
		to = message.to.map { MailAddressResponse(it.address, it.name) },
		cc = message.cc.map { MailAddressResponse(it.address, it.name) },
		bcc = message.bcc.map { MailAddressResponse(it.address, it.name) },
		subject = message.subject,
		html = html,
		text = text,
		inReplyTo = message.inReplyTo,
		references = message.referencesHeader,
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
		savedAt = message.sentAt,
	)
