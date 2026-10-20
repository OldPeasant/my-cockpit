package ch.sonensei.cockpit.core.settings.api

import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.core.web.ETags
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.parameters.RequestBody as DocumentedBody

/**
 * User settings (SPEC.md §4.7): one JSON object with every registered setting. The object's `ETag` covers all
 * settings; PATCH needs it in `If-Match`.
 */
@RestController
@RequestMapping("/api/v1/settings")
@Tag(name = "Settings", description = "User settings")
class SettingsController(
	private val settings: SettingsService,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "getSettings", summary = "All settings, unset ones with their defaults")
	@ApiResponse(
		responseCode = "200",
		description = "Every registered setting",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the settings, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = Settings::class))],
	)
	fun get(): ResponseEntity<Map<String, Any>> = settings.current().let { ETags.ok(it.values, it.version) }

	@PatchMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "updateSettings",
		summary = "Change settings",
		description =
			"Sets the given keys; `null` resets a key to its default, keys not sent stay unchanged. Nothing is changed " +
				"when a key is unknown or a value invalid (400, one error per key).",
	)
	@DocumentedBody(content = [Content(schema = Schema(implementation = SettingsPatch::class))])
	@ApiResponse(
		responseCode = "200",
		description = "Settings changed; all settings with the new ETag",
		headers = [Header(name = HttpHeaders.ETAG, description = "New version of the settings", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = Settings::class))],
	)
	@ApiResponse(
		responseCode = "400",
		description = "Unknown key or invalid value",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
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
	fun update(
		@Parameter(description = "ETag from the last GET or PATCH") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody changes: Map<String, Any?>,
	): ResponseEntity<Map<String, Any>> = settings.update(changes) { ETags.checkIfMatch(ifMatch, it) }.let { ETags.ok(it.values, it.version) }
}

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
