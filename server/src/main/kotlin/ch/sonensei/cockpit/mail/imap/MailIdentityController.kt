package ch.sonensei.cockpit.mail.imap

import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.mail.model.MailIdentity
import com.fasterxml.jackson.annotation.JsonProperty
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
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
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

/** Additional sending identities of a mail account (SPEC.md §7.1). */
@RestController
@RequestMapping(MailIdentityController.BASE)
@Tag(
	name = "Mail identities",
	description =
		"Further From addresses of an account besides its own (`emailAddress`), each with an optional Reply-To and " +
			"signature override. Changes send `change` events of type `MAIL_IDENTITY`.",
)
class MailIdentityController(
	private val identities: MailIdentityService,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "listMailIdentities", summary = "The account's identities, in their order")
	@ApiResponse(responseCode = "200", description = "The identities")
	@ApiResponse(
		responseCode = "404",
		description = "No such account",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun list(
		@PathVariable accountId: UUID,
	) = MailIdentityListResponse(identities.list(accountId).map { it.toResponse() })

	@GetMapping("/{identityId}", produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "getMailIdentity", summary = "One identity")
	@ApiResponse(
		responseCode = "200",
		description = "The identity, with its version as ETag",
		headers = [Header(name = HttpHeaders.ETAG, schema = Schema(type = "string"))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such account or identity",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun get(
		@PathVariable accountId: UUID,
		@PathVariable identityId: UUID,
	): ResponseEntity<MailIdentityResponse> = identities.get(accountId, identityId).let { ETags.ok(it.toResponse(), it.version) }

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(
		operationId = "createMailIdentity",
		summary = "Add an identity",
		description =
			"The new identity comes last. `isDefault: true` makes it the one compose starts with (the previous default " +
				"loses the flag).",
	)
	@ApiResponse(responseCode = "201", description = "Created; `Location` points to the identity")
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
		responseCode = "409",
		description = "The account already has an identity with this address",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	fun create(
		@PathVariable accountId: UUID,
		@Valid @RequestBody request: CreateMailIdentityRequest,
	): ResponseEntity<MailIdentityResponse> {
		val created = identities.create(accountId, request.toCommand())
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(created.id)
				.toUri()
		return ResponseEntity.created(location).eTag(ETags.of(created.version)).body(created.toResponse())
	}

	@PatchMapping("/{identityId}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "updateMailIdentity",
		summary = "Change an identity",
		description =
			"Absent (or null) fields stay unchanged. An empty `replyTo` or `signatureMd` removes it (the account's " +
				"signature applies again). `isDefault: true` takes the flag from the previous default.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The changed identity, with its new ETag",
		headers = [Header(name = HttpHeaders.ETAG, schema = Schema(type = "string"))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Invalid request",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such account or identity",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = PROBLEM))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "The account already has an identity with this address",
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
		@PathVariable accountId: UUID,
		@PathVariable identityId: UUID,
		@Parameter(`in` = ParameterIn.HEADER, description = "The identity's ETag", required = true)
		@RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@Valid @RequestBody request: UpdateMailIdentityRequest,
	): ResponseEntity<MailIdentityResponse> =
		identities
			.update(accountId, identityId, request.toCommand(), ifMatch)
			.let { ETags.ok(it.toResponse(), it.version) }

	@DeleteMapping("/{identityId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(
		operationId = "deleteMailIdentity",
		summary = "Remove an identity",
		description = "Existing drafts and sent mail are not changed.",
	)
	@ApiResponse(responseCode = "204", description = "Removed")
	@ApiResponse(
		responseCode = "404",
		description = "No such account or identity",
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
		@PathVariable accountId: UUID,
		@PathVariable identityId: UUID,
		@Parameter(`in` = ParameterIn.HEADER, description = "The identity's ETag", required = true)
		@RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	) = identities.delete(accountId, identityId, ifMatch)

	companion object {
		const val BASE = "/api/v1/mail/accounts/{accountId}/identities"

		private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
		private const val PROBLEM = "#/components/schemas/ProblemDetail"
	}
}

// Nullable fields so a missing value reaches Bean Validation (400 with field errors) instead of failing in Jackson.
class CreateMailIdentityRequest(
	@field:NotBlank
	@field:Size(max = 200)
	@field:Schema(description = "The From display name", requiredMode = RequiredMode.REQUIRED)
	val name: String?,
	@field:NotBlank
	@field:Email
	@field:Schema(description = "The From address; once per account", requiredMode = RequiredMode.REQUIRED)
	val email: String?,
	@field:Email
	@field:Schema(description = "Reply-To address; absent or empty for none")
	val replyTo: String? = null,
	@field:Schema(description = "Markdown; overrides the account's signature. Absent or empty for the account's")
	val signatureMd: String? = null,
	@get:Schema(description = "Make it the identity compose starts with; defaults to false")
	@param:JsonProperty("isDefault")
	@get:JsonProperty("isDefault")
	val isDefault: Boolean? = null,
) {
	fun toCommand() =
		NewMailIdentity(
			name = name!!,
			email = email!!.trim(),
			replyTo = replyTo?.trim(),
			signatureMd = signatureMd,
			isDefault = isDefault ?: false,
		)
}

class UpdateMailIdentityRequest(
	@field:Size(max = 200)
	val name: String? = null,
	@field:Email
	val email: String? = null,
	@field:Email
	@field:Schema(description = "Empty removes the Reply-To")
	val replyTo: String? = null,
	@field:Schema(description = "Markdown; empty removes the override (the account's signature applies)")
	val signatureMd: String? = null,
	@get:Schema(description = "true makes it the default (the previous default loses the flag); false clears it")
	@param:JsonProperty("isDefault")
	@get:JsonProperty("isDefault")
	val isDefault: Boolean? = null,
) {
	fun toCommand() =
		MailIdentityChanges(
			name = name,
			email = email?.trim(),
			replyTo = replyTo?.trim(),
			signatureMd = signatureMd,
			isDefault = isDefault,
		)
}

data class MailIdentityResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val accountId: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val name: String,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val email: String,
	val replyTo: String?,
	@field:Schema(description = "Markdown; absent when the account's signature applies")
	val signatureMd: String?,
	@get:Schema(description = "The identity compose starts with (at most one per account)", requiredMode = RequiredMode.REQUIRED)
	@get:JsonProperty("isDefault")
	val isDefault: Boolean,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val position: Int,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val createdAt: Instant,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val updatedAt: Instant,
	@field:Schema(description = "The ETag value for If-Match", requiredMode = RequiredMode.REQUIRED)
	val version: Long,
)

data class MailIdentityListResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val items: List<MailIdentityResponse>,
)

private fun MailIdentity.toResponse() =
	MailIdentityResponse(
		id = id,
		accountId = accountId,
		name = name,
		email = email,
		replyTo = replyTo,
		signatureMd = signatureMd,
		isDefault = isDefault,
		position = position,
		createdAt = checkNotNull(createdAt),
		updatedAt = checkNotNull(updatedAt),
		version = version,
	)
