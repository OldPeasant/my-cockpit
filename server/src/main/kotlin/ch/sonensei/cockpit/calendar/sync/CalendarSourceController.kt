package ch.sonensei.cockpit.calendar.sync

import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE

/** Calendar sources (SPEC.md §6.4) with their sync status, and syncing one on demand. */
@RestController
@RequestMapping("/api/v1/calendar/sources")
@Tag(name = "Calendars", description = "Calendars and their events")
class CalendarSourceController(
	private val sync: CalendarSyncService,
	private val sourceService: CalendarSourceService,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "listCalendarSources",
		summary = "List calendar sources",
		description = "Every source (the local one and connected accounts and feeds) in creation order, with its sync status.",
	)
	@ApiResponse(responseCode = "200", description = "The sources")
	fun list(): CalendarSourceListResponse = CalendarSourceListResponse(sync.list().map(::response))

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "createCalendarSource",
		summary = "Add a calendar source",
		description =
			"Adds a CalDAV account (`url`, `username`, `password`), an ICS feed (`url`, http(s) or webcal) or a Google/Microsoft " +
				"account (`oauthConnectionId`, a connection of that provider, see `/api/v1/oauth`). `name` defaults to the " +
				"host or the account's email. The scheduler syncs the new source shortly; `POST /{id}/sync` syncs it at once. " +
				"Adding an OAuth connection that already has a source of that type returns that source (200).",
	)
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = CalendarSourceResponse::class))],
	)
	@ApiResponse(
		responseCode = "200",
		description = "The OAuth connection already has a source of this type; that source",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = CalendarSourceResponse::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Invalid fields, a LOCAL type, or an unknown connection or one of another provider",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun create(
		@Valid @RequestBody request: CreateCalendarSourceRequest,
	): ResponseEntity<CalendarSourceResponse> {
		val result =
			sourceService.create(
				NewCalendarSource(
					type = request.type!!,
					name = request.name,
					url = request.url,
					username = request.username,
					password = request.password,
					oauthConnectionId = request.oauthConnectionId,
				),
			)
		val body = response(sync.get(result.source.id))
		return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(body)
	}

	@PostMapping("/{id}/sync", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "syncCalendarSource",
		summary = "Sync a source now",
		description =
			"Fetches the source's calendars and their changes from the provider and waits until done. A failing provider is " +
				"reported in `sync.lastError`, not as an error response.",
	)
	@ApiResponse(
		responseCode = "200",
		description = "Synced (or failed, see `sync`); the source with its new status",
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = CalendarSourceResponse::class))],
	)
	@ApiResponse(
		responseCode = "404",
		description = "No such source",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	@ApiResponse(
		responseCode = "409",
		description = "The source is local or its type cannot be synced yet",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun syncNow(
		@PathVariable id: UUID,
	): CalendarSourceResponse = response(sync.sync(id))

	private fun response(view: SourceSyncView): CalendarSourceResponse {
		val source = view.source
		val status =
			view.adapter?.let { adapter ->
				val state =
					when {
						view.status == null -> SyncState.NEVER
						view.status.lastError != null -> SyncState.ERROR
						else -> SyncState.OK
					}
				SyncStatusResponse(
					state = state,
					lastAttemptAt = view.status?.lastAttemptAt,
					lastSuccessAt = view.status?.lastSuccessAt,
					lastError = view.status?.lastError,
					nextSyncAt = sync.nextSyncAt(view)!!,
					pollIntervalSeconds = adapter.pollInterval.seconds,
				)
			}
		return CalendarSourceResponse(
			id = source.id,
			type = source.type,
			name = source.name,
			syncable = view.adapter != null,
			sync = status,
			createdAt = checkNotNull(source.createdAt),
			updatedAt = checkNotNull(source.updatedAt),
			version = source.version,
		)
	}
}

@Schema(description = "A calendar source: the local calendars, or a connected account or feed. Settings and secrets are not shown.")
data class CalendarSourceResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val type: CalendarSourceType,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val name: String,
	@get:Schema(
		description = "Whether the source is synced with its provider (false for LOCAL and for types without support yet)",
		requiredMode = RequiredMode.REQUIRED,
	)
	val syncable: Boolean,
	@get:Schema(description = "Sync status; null when the source is not synced") val sync: SyncStatusResponse?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val createdAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val updatedAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
)

@Schema(description = "A new external calendar source. Secrets are stored encrypted and never returned.")
data class CreateCalendarSourceRequest(
	@get:Schema(description = "CALDAV, ICS_FEED, GOOGLE or MICROSOFT", requiredMode = RequiredMode.REQUIRED)
	@field:NotNull
	val type: CalendarSourceType?,
	@get:Schema(description = "Shown name; defaults to the URL's host or the account's email") val name: String? = null,
	@get:Schema(description = "CALDAV: the server or calendar home URL; ICS_FEED: the feed URL") val url: String? = null,
	@get:Schema(description = "CALDAV: the user name") val username: String? = null,
	@get:Schema(description = "CALDAV: the (app) password", format = "password") val password: String? = null,
	@get:Schema(description = "GOOGLE/MICROSOFT: the OAuth connection") val oauthConnectionId: UUID? = null,
) {
	override fun toString(): String = "CreateCalendarSourceRequest(type=$type, name=$name, url=******, username=$username, password=******)"
}

@Schema(description = "Every calendar source in creation order")
data class CalendarSourceListResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<CalendarSourceResponse>,
)

@Schema(description = "The sync status of a source")
data class SyncStatusResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val state: SyncState,
	@get:Schema(description = "Start of the last sync run") val lastAttemptAt: Instant?,
	@get:Schema(description = "Start of the last run without errors") val lastSuccessAt: Instant?,
	@get:Schema(description = "What failed in the last run; null when it succeeded") val lastError: String?,
	@get:Schema(description = "When the scheduler syncs next", requiredMode = RequiredMode.REQUIRED) val nextSyncAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val pollIntervalSeconds: Long,
)

/** The outcome of a source's last sync run. */
enum class SyncState {
	/** Not synced yet. */
	NEVER,

	/** The last run succeeded. */
	OK,

	/** The last run failed (completely or for some calendars or events). */
	ERROR,
}
