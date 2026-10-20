package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.mail.model.MailAccount
import ch.sonensei.cockpit.mail.model.MailAccountType
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRole
import ch.sonensei.cockpit.mail.model.MailSecurity
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.time.Instant
import java.util.UUID

/** Mail accounts and their folders (SPEC.md §7.1, §7.4). */
@RestController
@RequestMapping(MailAccountController.BASE)
@Tag(
	name = "Mail accounts",
	description =
		"IMAP/SMTP accounts with password, and Google/Microsoft accounts through an OAuth connection (XOAUTH2). " +
			"Passwords are write-only. Changes send `change` events of type `MAIL_ACCOUNT` and `MAIL_FOLDER`.",
)
class MailAccountController(
	private val accounts: MailAccountService,
	private val folders: MailFolderService,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "listMailAccounts", summary = "All mail accounts, by display name")
	@ApiResponse(responseCode = "200", description = "The accounts (passwords are never returned)")
	fun list() = MailAccountListResponse(accounts.list().map { it.toResponse() })

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "getMailAccount", summary = "One mail account")
	@ApiResponse(
		responseCode = "200",
		description = "The account, with its version as ETag",
		headers = [Header(name = HttpHeaders.ETAG, schema = Schema(type = "string"))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such account",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun get(
		@PathVariable id: UUID,
	): ResponseEntity<MailAccountResponse> = accounts.get(id).let { ETags.ok(it.toResponse(), it.version) }

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(
		operationId = "createMailAccount",
		summary = "Add a mail account",
		description =
			"`IMAP_SMTP` accounts need servers, username and password. `GOOGLE`/`MICROSOFT` accounts need " +
				"`oauthConnectionId` (a connection of that provider, see `/api/v1/oauth`) and no password; servers " +
				"default to the provider's and the username to the connection's email address. The servers are not " +
				"contacted: use `POST /{id}/test` and `POST /{id}/folders/sync` afterwards.",
	)
	@ApiResponse(responseCode = "201", description = "Created; `Location` points to the account")
	@ApiResponse(
		responseCode = "400",
		description = "Invalid request",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun create(
		@Valid @RequestBody request: CreateMailAccountRequest,
	): ResponseEntity<MailAccountResponse> {
		val created = accounts.create(request.toCommand())
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(created.id)
				.toUri()
		return ResponseEntity.created(location).eTag(ETags.of(created.version)).body(created.toResponse())
	}

	@PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "updateMailAccount",
		summary = "Change a mail account",
		description =
			"Absent (or null) fields stay unchanged; the type cannot change. A server is changed as a whole " +
				"(host, port and security together). `password` replaces the stored one; an empty `signatureMd` " +
				"removes the signature. Changing servers or credentials closes the account's open IMAP connections.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The changed account, with its new ETag",
		headers = [Header(name = HttpHeaders.ETAG, schema = Schema(type = "string"))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Invalid request",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such account",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
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
	fun update(
		@PathVariable id: UUID,
		@Parameter(`in` = ParameterIn.HEADER, description = "The account's ETag", required = true)
		@RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@Valid @RequestBody request: UpdateMailAccountRequest,
	): ResponseEntity<MailAccountResponse> = accounts.update(id, request.toCommand(), ifMatch).let { ETags.ok(it.toResponse(), it.version) }

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(
		operationId = "deleteMailAccount",
		summary = "Remove a mail account",
		description = "Removes the account with its cached folders and messages. Mail on the server is not touched.",
	)
	@ApiResponse(responseCode = "204", description = "Removed")
	@ApiResponse(
		responseCode = "404",
		description = "No such account",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
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
	fun delete(
		@PathVariable id: UUID,
		@Parameter(`in` = ParameterIn.HEADER, description = "The account's ETag", required = true)
		@RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	) = accounts.delete(id, ifMatch)

	@PostMapping("/{id}/test", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "testMailAccountConnection",
		summary = "Test the connection",
		description =
			"Logs in to the IMAP and the SMTP server with the stored settings. Failures are reported in the body " +
				"(200), per protocol, as an error code with a fixed message.",
	)
	@ApiResponse(responseCode = "200", description = "The outcome per protocol")
	@ApiResponse(
		responseCode = "404",
		description = "No such account",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun test(
		@PathVariable id: UUID,
	): ConnectionTestResponse {
		val result = accounts.testConnection(id)
		return ConnectionTestResponse(ProtocolTestResponse.of(result.imap), ProtocolTestResponse.of(result.smtp))
	}

	@GetMapping("/{id}/folders", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "listMailFolders",
		summary = "The account's folders (cached)",
		description = "The folder list of the last sync, by path. Empty before the first sync.",
	)
	@ApiResponse(responseCode = "200", description = "The folders")
	@ApiResponse(
		responseCode = "404",
		description = "No such account",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun folders(
		@PathVariable id: UUID,
	) = MailFolderListResponse(folders.list(id).map { it.toResponse() })

	@PostMapping("/{id}/folders/sync", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "syncMailFolders",
		summary = "Read the folder list from the server",
		description =
			"Adds new folders, removes vanished ones (their cached messages stay as link targets) and detects " +
				"roles via SPECIAL-USE (RFC 6154) or well-known names. Roles set by hand are kept.",
	)
	@ApiResponse(responseCode = "200", description = "The folders after the sync")
	@ApiResponse(
		responseCode = "404",
		description = "No such account",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "502",
		description = "The mail server could not be reached or rejected the login; `errorCode` says why",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun syncFolders(
		@PathVariable id: UUID,
	) = MailFolderListResponse(folders.sync(id).map { it.toResponse() })

	@PatchMapping("/{id}/folders/{folderId}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "updateMailFolder",
		summary = "Set a folder's role by hand",
		description =
			"`{\"role\": \"ARCHIVE\"}` makes the folder the account's Archive (the folder that had the role loses " +
				"it); `{\"role\": null}` gives it no role. Either way detection leaves it alone from then on. " +
				"`{\"automatic\": true}` hands the folder back to detection on the next sync.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The changed folder, with its new ETag",
		headers = [Header(name = HttpHeaders.ETAG, schema = Schema(type = "string"))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Invalid request",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such folder",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
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
	fun updateFolder(
		@PathVariable id: UUID,
		@PathVariable folderId: UUID,
		@Parameter(`in` = ParameterIn.HEADER, description = "The folder's ETag", required = true)
		@RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody request: UpdateMailFolderRequest,
	): ResponseEntity<MailFolderResponse> =
		folders
			.overrideRole(id, folderId, request.role, request.automatic ?: false, ifMatch)
			.let { ETags.ok(it.toResponse(), it.version) }

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

	companion object {
		const val BASE = "/api/v1/mail/accounts"

		private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
		private const val PROBLEM = "#/components/schemas/ProblemDetail"
	}
}

// Nullable fields so a missing value reaches Bean Validation (400 with field errors) instead of failing in Jackson.
class CreateMailAccountRequest(
	@field:NotNull
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val type: MailAccountType?,
	@field:Size(max = 200)
	@field:Schema(description = "Defaults to the email address")
	val displayName: String? = null,
	@field:NotBlank
	@field:Email
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val emailAddress: String?,
	@field:Valid
	@field:Schema(description = "Required for IMAP_SMTP; defaults to the provider's server for GOOGLE/MICROSOFT")
	val imap: MailServerRequest? = null,
	@field:Valid
	@field:Schema(description = "Required for IMAP_SMTP; defaults to the provider's server for GOOGLE/MICROSOFT")
	val smtp: MailServerRequest? = null,
	@field:Schema(description = "Required for IMAP_SMTP; defaults to the connection's email for GOOGLE/MICROSOFT")
	val username: String? = null,
	@field:Schema(description = "IMAP_SMTP only: password or app password. Write-only", accessMode = Schema.AccessMode.WRITE_ONLY)
	val password: String? = null,
	@field:Schema(description = "GOOGLE/MICROSOFT only: the OAuth connection to log in with")
	val oauthConnectionId: UUID? = null,
	@field:Schema(description = "Markdown")
	val signatureMd: String? = null,
	@field:Schema(description = "Notify about new mail in the Inbox; defaults to true")
	val notifyNewMail: Boolean? = null,
	@field:Min(1)
	@field:Max(MAX_SYNC_WINDOW_MONTHS)
	@field:Schema(description = "Months of headers cached on the first sync; defaults to 12")
	val syncWindowMonths: Int? = null,
) {
	fun toCommand() =
		NewMailAccount(
			type = type!!,
			displayName = displayName,
			emailAddress = emailAddress!!.trim(),
			imap = imap?.toServer(),
			smtp = smtp?.toServer(),
			username = username,
			password = password,
			oauthConnectionId = oauthConnectionId,
			signatureMd = signatureMd,
			notifyNewMail = notifyNewMail,
			syncWindowMonths = syncWindowMonths,
		)

	override fun toString(): String = "CreateMailAccountRequest(type=$type, emailAddress=$emailAddress, ******)"
}

class UpdateMailAccountRequest(
	@field:Size(max = 200)
	val displayName: String? = null,
	@field:Email
	val emailAddress: String? = null,
	@field:Valid
	val imap: MailServerRequest? = null,
	@field:Valid
	val smtp: MailServerRequest? = null,
	val username: String? = null,
	@field:Schema(description = "IMAP_SMTP only: replaces the stored password. Write-only", accessMode = Schema.AccessMode.WRITE_ONLY)
	val password: String? = null,
	@field:Schema(description = "GOOGLE/MICROSOFT only")
	val oauthConnectionId: UUID? = null,
	@field:Schema(description = "Markdown; empty removes the signature")
	val signatureMd: String? = null,
	val notifyNewMail: Boolean? = null,
	@field:Min(1)
	@field:Max(MAX_SYNC_WINDOW_MONTHS)
	val syncWindowMonths: Int? = null,
) {
	fun toCommand() =
		MailAccountChanges(
			displayName = displayName,
			emailAddress = emailAddress?.trim(),
			imap = imap?.toServer(),
			smtp = smtp?.toServer(),
			username = username,
			password = password,
			oauthConnectionId = oauthConnectionId,
			signatureMd = signatureMd,
			notifyNewMail = notifyNewMail,
			syncWindowMonths = syncWindowMonths,
		)

	override fun toString(): String = "UpdateMailAccountRequest(******)"
}

class MailServerRequest(
	@field:NotBlank
	@field:Size(max = 253)
	@field:Schema(requiredMode = RequiredMode.REQUIRED, example = "imap.example.com")
	val host: String?,
	@field:NotNull
	@field:Min(1)
	@field:Max(65535)
	@field:Schema(requiredMode = RequiredMode.REQUIRED, example = "993")
	val port: Int?,
	@field:NotNull
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val security: MailSecurity?,
) {
	fun toServer() = MailServer(host!!.trim(), port!!, security!!)
}

data class MailServerResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val host: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val port: Int,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val security: MailSecurity,
)

data class MailAccountResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val type: MailAccountType,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val displayName: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val emailAddress: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val imap: MailServerResponse,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val smtp: MailServerResponse,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val username: String,
	@field:Schema(description = "Whether a password is stored (it is never returned)", requiredMode = RequiredMode.REQUIRED)
	val hasPassword: Boolean,
	val oauthConnectionId: UUID?,
	val signatureMd: String?,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val notifyNewMail: Boolean,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val syncWindowMonths: Int,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val createdAt: Instant,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val updatedAt: Instant,
	@field:Schema(description = "The ETag value for If-Match", requiredMode = RequiredMode.REQUIRED)
	val version: Long,
)

data class MailAccountListResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val items: List<MailAccountResponse>,
)

data class ProtocolTestResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val ok: Boolean,
	@field:Schema(description = "Why it failed; absent when ok")
	val errorCode: MailConnectionError? = null,
	@field:Schema(description = "A fixed, displayable message for errorCode")
	val message: String? = null,
) {
	companion object {
		fun of(error: MailConnectionError?) = ProtocolTestResponse(error == null, error, error?.message)
	}
}

data class ConnectionTestResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val imap: ProtocolTestResponse,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val smtp: ProtocolTestResponse,
)

class UpdateMailFolderRequest(
	@field:Schema(description = "The role to set by hand; null for no role. Ignored when automatic is true")
	val role: MailFolderRole? = null,
	@field:Schema(description = "true: let detection choose the role again on the next sync")
	val automatic: Boolean? = null,
)

data class MailFolderResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val accountId: UUID,
	@field:Schema(description = "Full IMAP path, e.g. INBOX/Projects", requiredMode = RequiredMode.REQUIRED)
	val path: String,
	@field:Schema(description = "Last path segment", requiredMode = RequiredMode.REQUIRED)
	val name: String,
	@field:Schema(description = "Hierarchy delimiter; absent for flat namespaces")
	val delimiter: String?,
	val role: MailFolderRole?,
	@field:Schema(description = "The role was set by hand", requiredMode = RequiredMode.REQUIRED)
	val roleOverridden: Boolean,
	@field:Schema(description = "false for folders that only group children", requiredMode = RequiredMode.REQUIRED)
	val selectable: Boolean,
	val lastSyncedAt: Instant?,
	@field:Schema(description = "The ETag value for If-Match", requiredMode = RequiredMode.REQUIRED)
	val version: Long,
)

data class MailFolderListResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val items: List<MailFolderResponse>,
)

private const val MAX_SYNC_WINDOW_MONTHS = 600L

private fun MailAccount.toResponse() =
	MailAccountResponse(
		id = id,
		type = type,
		displayName = displayName,
		emailAddress = emailAddress,
		imap = MailServerResponse(imapHost, imapPort, imapSecurity),
		smtp = MailServerResponse(smtpHost, smtpPort, smtpSecurity),
		username = username,
		hasPassword = password != null,
		oauthConnectionId = oauthConnectionId,
		signatureMd = signatureMd,
		notifyNewMail = notifyNewMail,
		syncWindowMonths = syncWindowMonths,
		createdAt = checkNotNull(createdAt),
		updatedAt = checkNotNull(updatedAt),
		version = version,
	)

private fun MailFolder.toResponse() =
	MailFolderResponse(
		id = id,
		accountId = accountId,
		path = path,
		name = name,
		delimiter = delimiter,
		role = role,
		roleOverridden = roleOverridden,
		selectable = selectable,
		lastSyncedAt = lastSyncedAt,
		version = version,
	)
