package ch.sonensei.cockpit.contacts.api

import ch.sonensei.cockpit.contacts.domain.Contact
import ch.sonensei.cockpit.contacts.domain.ContactService
import ch.sonensei.cockpit.contacts.domain.VCardService
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ContentDisposition
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.util.UUID
import io.swagger.v3.oas.annotations.parameters.RequestBody as DocumentedBody

/**
 * Contacts (SPEC.md §8.3): list, CRUD and vCard import/export. Responses carry the contact's `ETag`; PATCH and DELETE
 * need it in `If-Match`.
 */
@RestController
@RequestMapping("/api/v1/contacts")
@Tag(name = "Contacts", description = "Contacts and their vCard import/export")
class ContactController(
	private val contacts: ContactService,
	private val vcards: VCardService,
	private val jsonMapper: JsonMapper,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "listContacts",
		summary = "List contacts",
		description = "Contacts not in the Trash, ordered by display name, then id.",
	)
	@ApiResponse(responseCode = "200", description = "One page of contacts")
	@ContactBadRequestResponse
	fun list(
		@Parameter(description = "Only contacts whose display name or an email address contains this text (case-insensitive)")
		@RequestParam(required = false)
		q: String?,
		@Parameter(description = "Cursor from the previous page") @RequestParam(required = false) cursor: String?,
		@Parameter(description = "Page size, 1-200 (default 50)") @RequestParam(required = false) limit: Int?,
	): ContactPageResponse {
		val page = contacts.list(q, CursorRequest.of(cursor, limit))
		return ContactPageResponse(page.items.map(ContactResponse::of), page.nextCursor)
	}

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "createContact", summary = "Create a contact", description = "Unknown fields are rejected.")
	@DocumentedBody(content = [Content(schema = Schema(implementation = ContactRequest::class))])
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the contact, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ContactResponse::class))],
	)
	@ContactBadRequestResponse
	fun create(
		@RequestBody body: Map<String, Any?>,
	): ResponseEntity<ContactResponse> {
		val contact = contacts.create(parse(body).toChanges(ContactRequest.FIELDS))
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(contact.id)
				.toUri()
		return ResponseEntity.created(location).eTag(ETags.of(contact.version)).body(ContactResponse.of(contact))
	}

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getContact", summary = "One contact")
	@ContactOkResponse
	@ContactNotFoundResponse
	fun get(
		@PathVariable id: UUID,
	): ResponseEntity<ContactResponse> = respond(contacts.get(id))

	@PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "updateContact",
		summary = "Change a contact",
		description =
			"Only the fields sent change; `null` clears a field (lists: `null` or `[]`), a cleared `displayName` is " +
				"derived again. Lists are replaced as a whole. Unknown fields are rejected.",
	)
	@DocumentedBody(content = [Content(schema = Schema(implementation = ContactRequest::class))])
	@ContactOkResponse
	@ContactBadRequestResponse
	@ContactNotFoundResponse
	@ContactIfMatchResponses
	fun update(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the contact") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody body: Map<String, Any?>,
	): ResponseEntity<ContactResponse> {
		val changes = parse(body).toChanges(body.keys)
		return respond(contacts.update(id, changes) { ETags.checkIfMatch(ifMatch, it) })
	}

	@DeleteMapping("/{id}", produces = [PROBLEM_JSON])
	@Operation(operationId = "deleteContact", summary = "Move a contact to the Trash", description = "Its photo stays in the drive.")
	@ApiResponse(responseCode = "204", description = "In the Trash")
	@ContactNotFoundResponse
	@ContactIfMatchResponses
	fun delete(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the contact") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<Void> {
		contacts.delete(id) { ETags.checkIfMatch(ifMatch, it) }
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}

	@PostMapping("/import", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "importContacts",
		summary = "Import a vCard file (multipart)",
		description =
			"Every card of the file in the part `file` (vCard 2.1, 3.0 or 4.0, UTF-8, at most 20 MB) becomes a new LOCAL " +
				"contact. Invalid cards are skipped and listed, the others are imported. Embedded photos are stored in " +
				"`/Attachments`; birthdays without a year are ignored.",
	)
	@ApiResponse(responseCode = "200", description = "The imported contacts and the skipped cards")
	@ContactBadRequestResponse
	fun import(
		@RequestPart("file") file: MultipartFile,
	): VCardImportResponse {
		if (file.size > MAX_IMPORT_BYTES) throw InvalidRequestException("file", "must be at most ${MAX_IMPORT_BYTES / 1024 / 1024} MB")
		val text = file.inputStream.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
		return VCardImportResponse.of(vcards.import(text))
	}

	@GetMapping("/export", produces = [VCARD])
	@Operation(
		operationId = "exportContacts",
		summary = "Export contacts as vCard",
		description = "All contacts not in the Trash (or only the given `ids`) as one vCard 3.0 file; photos are not embedded.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "The vCard file",
		content = [Content(mediaType = VCARD, schema = Schema(type = "string"))],
	)
	@ContactNotFoundResponse
	fun export(
		@Parameter(description = "Only these contacts") @RequestParam(required = false) ids: List<UUID>?,
	): ResponseEntity<String> {
		val list = if (ids.isNullOrEmpty()) contacts.all() else ids.distinct().map(contacts::get)
		return ResponseEntity
			.ok()
			.contentType(MediaType.parseMediaType("$VCARD;charset=UTF-8"))
			.header(
				HttpHeaders.CONTENT_DISPOSITION,
				ContentDisposition
					.attachment()
					.filename("contacts.vcf")
					.build()
					.toString(),
			).body(vcards.export(list))
	}

	/** Converts a create or PATCH body; unknown fields and values of the wrong type are a 400. */
	private fun parse(body: Map<String, Any?>): ContactRequest {
		val unknown = body.keys - ContactRequest.FIELDS
		if (unknown.isNotEmpty()) throw InvalidRequestException(unknown.sorted().map { FieldError(it, "is not a field of a contact") })
		return try {
			jsonMapper.convertValue(body, ContactRequest::class.java)
		} catch (e: JacksonException) {
			val field = e.path.mapNotNull { it.propertyName }.firstOrNull() ?: "body"
			throw InvalidRequestException(field, "has an invalid value")
		}
	}

	private fun respond(contact: Contact): ResponseEntity<ContactResponse> = ETags.ok(ContactResponse.of(contact), contact.version)

	private companion object {
		const val VCARD = "text/vcard"
		const val MAX_IMPORT_BYTES = 20L * 1024 * 1024
	}
}

internal const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE

/** `200` with a contact and its ETag. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "200",
	description = "The contact with its (new) ETag",
	headers = [Header(name = HttpHeaders.ETAG, description = "Version of the contact, for If-Match", schema = Schema(type = "string"))],
	content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ContactResponse::class))],
)
annotation class ContactOkResponse

/** `400` Problem Details response of the contacts endpoints. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "400",
	description = "Invalid or unknown fields",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class ContactBadRequestResponse

/** `404` Problem Details response of the contacts endpoints. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "404",
	description = "Not found or in the Trash",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class ContactNotFoundResponse

/** The optimistic locking responses (409, 412, 428) of a change that requires `If-Match`. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "409",
	description = "Changed concurrently",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
@ApiResponse(
	responseCode = "412",
	description = "If-Match does not match the current ETag",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
@ApiResponse(
	responseCode = "428",
	description = "If-Match is missing",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class ContactIfMatchResponses
