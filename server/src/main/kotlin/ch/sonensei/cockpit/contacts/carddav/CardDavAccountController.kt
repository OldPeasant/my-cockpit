package ch.sonensei.cockpit.contacts.carddav

import ch.sonensei.cockpit.contacts.api.ContactBadRequestResponse
import ch.sonensei.cockpit.contacts.api.ContactRequest
import ch.sonensei.cockpit.contacts.api.ContactResponse
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.ETags
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID
import io.swagger.v3.oas.annotations.parameters.RequestBody as DocumentedBody

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE

/**
 * CardDAV accounts whose contacts are synced (SPEC.md §8.3): list, add, remove, sync now, and creating a contact in an
 * account's address book. Passwords are write-only: stored encrypted, never returned.
 */
@RestController
@RequestMapping("/api/v1/contacts/carddav-accounts")
@Tag(name = "Contacts", description = "Contacts and their vCard import/export")
class CardDavAccountController(
	private val accounts: CardDavAccountService,
	private val sync: CardDavContactsSync,
	private val jsonMapper: JsonMapper,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "listCardDavAccounts",
		summary = "List CardDAV accounts",
		description = "In creation order, with their sync status.",
	)
	@ApiResponse(responseCode = "200", description = "The accounts")
	fun list(): CardDavAccountListResponse = CardDavAccountListResponse(accounts.list().map(CardDavAccountResponse::of))

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "addCardDavAccount",
		summary = "Add a CardDAV account",
		description =
			"`url` is an address book, or the server (or principal) URL from which the first address book is found. The " +
				"scheduler syncs the account shortly; `POST /{id}/sync` syncs it at once.",
	)
	@ApiResponse(
		responseCode = "201",
		description = "Added",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = CardDavAccountResponse::class))],
	)
	@ContactBadRequestResponse
	fun add(
		@Valid @RequestBody request: AddCardDavAccountRequest,
	): ResponseEntity<CardDavAccountResponse> {
		val account = accounts.add(request.name!!, request.url!!, request.username, request.password?.ifEmpty { null })
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(account.id)
				.toUri()
		return ResponseEntity.created(location).body(CardDavAccountResponse.of(account))
	}

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getCardDavAccount", summary = "One CardDAV account with its sync status")
	@CardDavAccountOkResponse
	@CardDavAccountNotFoundResponse
	fun get(
		@PathVariable id: UUID,
	): CardDavAccountResponse = CardDavAccountResponse.of(accounts.get(id))

	@DeleteMapping("/{id}", produces = [PROBLEM_JSON])
	@Operation(
		operationId = "removeCardDavAccount",
		summary = "Remove a CardDAV account",
		description = "Its contacts stay here (no longer synced); nothing is deleted at the server.",
	)
	@ApiResponse(responseCode = "204", description = "Removed")
	@CardDavAccountNotFoundResponse
	fun remove(
		@PathVariable id: UUID,
	): ResponseEntity<Void> {
		accounts.remove(id)
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}

	@PostMapping("/{id}/sync", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "syncCardDavAccount",
		summary = "Sync an account now",
		description =
			"Pushes local changes and pulls the server's, and waits until done. A failing server is reported in " +
				"`sync.lastError`, not as an error response. While a run of the account is going on, its status is returned.",
	)
	@CardDavAccountOkResponse
	@CardDavAccountNotFoundResponse
	fun syncNow(
		@PathVariable id: UUID,
	): CardDavAccountResponse = CardDavAccountResponse.of(sync.sync(id))

	@PostMapping(
		"/{id}/contacts",
		consumes = [MediaType.APPLICATION_JSON_VALUE],
		produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON],
	)
	@Operation(
		operationId = "createCardDavContact",
		summary = "Create a contact in the account's address book",
		description =
			"A CARDDAV contact, created here at once and at the server with the next sync. Same fields as `createContact`; " +
				"unknown fields are rejected.",
	)
	@DocumentedBody(content = [Content(schema = Schema(implementation = ContactRequest::class))])
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the contact, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ContactResponse::class))],
	)
	@ContactBadRequestResponse
	@CardDavAccountNotFoundResponse
	@ApiResponse(
		responseCode = "409",
		description = "The account's address book could not be found at the server",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun createContact(
		@PathVariable id: UUID,
		@RequestBody body: Map<String, Any?>,
	): ResponseEntity<ContactResponse> {
		val changes = ContactRequest.parse(body, jsonMapper).toChanges(ContactRequest.FIELDS)
		val contact =
			try {
				sync.createContact(id, changes)
			} catch (e: CardDavException) {
				throw ConflictException(e.message ?: "The CardDAV server could not be reached")
			}
		val location =
			ServletUriComponentsBuilder
				.fromCurrentContextPath()
				.path("/api/v1/contacts/{id}")
				.buildAndExpand(contact.id)
				.toUri()
		return ResponseEntity.created(location).eTag(ETags.of(contact.version)).body(ContactResponse.of(contact))
	}
}

@Schema(description = "A CardDAV account whose contacts are synced. The password is never returned.")
data class CardDavAccountResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val name: String,
	@get:Schema(description = "The URL as configured: an address book, or the server to find one from", requiredMode = RequiredMode.REQUIRED)
	val url: String,
	val username: String?,
	@get:Schema(description = "Whether a password is stored", requiredMode = RequiredMode.REQUIRED) val hasPassword: Boolean,
	@get:Schema(description = "The address book that is synced; null until the first sync found it") val addressbookUrl: String?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val sync: CardDavSyncStatusResponse,
) {
	companion object {
		fun of(account: CardDavAccount) =
			CardDavAccountResponse(
				id = account.id,
				name = account.name,
				url = account.url,
				username = account.username,
				hasPassword = account.password != null,
				addressbookUrl = account.addressbookUrl,
				sync =
					CardDavSyncStatusResponse(
						state =
							when {
								account.lastError != null -> CardDavSyncState.ERROR
								account.lastSuccessAt != null -> CardDavSyncState.OK
								else -> CardDavSyncState.NEVER
							},
						lastAttemptAt = account.lastAttemptAt,
						lastSuccessAt = account.lastSuccessAt,
						lastError = account.lastError,
					),
			)
	}
}

@Schema(description = "The sync status of a CardDAV account")
data class CardDavSyncStatusResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val state: CardDavSyncState,
	@get:Schema(description = "Start of the last sync run") val lastAttemptAt: Instant?,
	@get:Schema(description = "End of the last run without errors") val lastSuccessAt: Instant?,
	@get:Schema(description = "What failed in the last run (no secrets, no contact data); null when it succeeded")
	val lastError: String?,
)

/** The outcome of an account's last sync run. */
enum class CardDavSyncState {
	/** Not synced yet (or the first run is going on). */
	NEVER,

	/** The last run succeeded. */
	OK,

	/** The last run failed (completely or for some contacts). */
	ERROR,
}

@Schema(description = "Every CardDAV account in creation order")
data class CardDavAccountListResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<CardDavAccountResponse>,
)

@Schema(description = "A new CardDAV account. The password is stored encrypted and never returned.")
data class AddCardDavAccountRequest(
	@get:Schema(description = "Shown name", requiredMode = RequiredMode.REQUIRED)
	@field:NotNull
	val name: String?,
	@get:Schema(description = "An address book, or the server (or principal) URL; http or https", requiredMode = RequiredMode.REQUIRED)
	@field:NotNull
	val url: String?,
	@get:Schema(description = "The user name") val username: String? = null,
	@get:Schema(description = "The (app) password; write-only", format = "password", writeOnly = true) val password: String? = null,
) {
	override fun toString(): String = "AddCardDavAccountRequest(name=$name, url=$url, username=$username, password=******)"
}

/** `200` with an account. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "200",
	description = "The account with its sync status",
	content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = CardDavAccountResponse::class))],
)
annotation class CardDavAccountOkResponse

/** `404` Problem Details response of the CardDAV account endpoints. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "404",
	description = "No such account",
	content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
)
annotation class CardDavAccountNotFoundResponse
