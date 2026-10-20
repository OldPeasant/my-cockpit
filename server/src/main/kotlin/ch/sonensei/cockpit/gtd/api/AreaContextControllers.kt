package ch.sonensei.cockpit.gtd.api

import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.gtd.domain.AreaService
import ch.sonensei.cockpit.gtd.domain.ContextService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.util.UUID
import io.swagger.v3.oas.annotations.parameters.RequestBody as DocumentedBody

/** Areas of focus (SPEC.md §5.1) in the user's order. PATCH and DELETE need the area's `ETag` in `If-Match`. */
@RestController
@RequestMapping("/api/v1/areas")
@Tag(name = "Projects", description = "GTD projects, areas of focus and contexts")
class AreaController(
	private val areas: AreaService,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "listAreas", summary = "All areas in order")
	@ApiResponse(responseCode = "200", description = "The areas")
	fun list(): AreaListResponse = AreaListResponse(areas.list().map(AreaResponse::of))

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "createArea", summary = "Create an area")
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the area, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = AreaResponse::class))],
	)
	@BadRequestResponse
	fun create(
		@Valid @RequestBody request: CreateAreaRequest,
	): ResponseEntity<AreaResponse> {
		val area = areas.create(request.title!!, request.position)
		return ResponseEntity
			.created(locationOf(area.id))
			.eTag(ETags.of(area.version))
			.body(AreaResponse.of(area))
	}

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getArea", summary = "One area")
	@AreaOkResponse
	@NotFoundResponse
	fun get(
		@PathVariable id: UUID,
	): ResponseEntity<AreaResponse> = areas.get(id).let { ETags.ok(AreaResponse.of(it), it.version) }

	@PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "updateArea", summary = "Change an area")
	@AreaOkResponse
	@BadRequestResponse
	@NotFoundResponse
	@IfMatchResponses
	fun update(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the area") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody request: UpdateAreaRequest,
	): ResponseEntity<AreaResponse> =
		areas
			.update(id, request.title, request.position) { ETags.checkIfMatch(ifMatch, it) }
			.let { ETags.ok(AreaResponse.of(it), it.version) }

	@PutMapping("/order", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "orderAreas", summary = "Reorder all areas")
	@ApiResponse(responseCode = "200", description = "All areas in the new order")
	@BadRequestResponse
	fun order(
		@Valid @RequestBody request: OrderRequest,
	): AreaListResponse = AreaListResponse(areas.order(request.ids!!).map(AreaResponse::of))

	@DeleteMapping("/{id}", produces = [PROBLEM_JSON])
	@Operation(
		operationId = "deleteArea",
		summary = "Delete an area",
		description = "Deleted for good (areas have no Trash); its projects no longer have an area.",
	)
	@ApiResponse(responseCode = "204", description = "Deleted")
	@NotFoundResponse
	@IfMatchResponses
	fun delete(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the area") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<Void> {
		areas.delete(id) { ETags.checkIfMatch(ifMatch, it) }
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}
}

/**
 * Contexts (`@home`, SPEC.md §5.1) in the user's order. Names are unique regardless of case (409 otherwise). PATCH and
 * DELETE need the context's `ETag` in `If-Match`.
 */
@RestController
@RequestMapping("/api/v1/contexts")
@Tag(name = "Projects", description = "GTD projects, areas of focus and contexts")
class ContextController(
	private val contexts: ContextService,
	private val jsonMapper: JsonMapper,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(operationId = "listContexts", summary = "All contexts in order")
	@ApiResponse(responseCode = "200", description = "The contexts")
	fun list(): ContextListResponse = ContextListResponse(contexts.list().map(ContextResponse::of))

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "createContext", summary = "Create a context")
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the context, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ContextResponse::class))],
	)
	@BadRequestResponse
	@ApiResponse(
		responseCode = "409",
		description = "A context with this name exists",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun create(
		@Valid @RequestBody request: CreateContextRequest,
	): ResponseEntity<ContextResponse> {
		val context = nameTaken { contexts.create(request.name!!, request.color, request.position) }
		return ResponseEntity
			.created(locationOf(context.id))
			.eTag(ETags.of(context.version))
			.body(ContextResponse.of(context))
	}

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getContext", summary = "One context")
	@ContextOkResponse
	@NotFoundResponse
	fun get(
		@PathVariable id: UUID,
	): ResponseEntity<ContextResponse> = contexts.get(id).let { ETags.ok(ContextResponse.of(it), it.version) }

	@PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "updateContext",
		summary = "Change a context",
		description = "Only the fields sent change; `null` clears `color`.",
	)
	@DocumentedBody(content = [Content(schema = Schema(implementation = ContextPatchRequest::class))])
	@ContextOkResponse
	@BadRequestResponse
	@NotFoundResponse
	@IfMatchResponses
	fun update(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the context") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody body: Map<String, Any?>,
	): ResponseEntity<ContextResponse> {
		val patch = jsonMapper.parsePatch(body, ContextPatchRequest::class.java, setOf("name", "color", "position"), setOf("name", "position"))
		val context =
			nameTaken {
				with(patch.request) {
					contexts.update(id, name, patch.change("color", color), position) { ETags.checkIfMatch(ifMatch, it) }
				}
			}
		return ETags.ok(ContextResponse.of(context), context.version)
	}

	@PutMapping("/order", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "orderContexts", summary = "Reorder all contexts")
	@ApiResponse(responseCode = "200", description = "All contexts in the new order")
	@BadRequestResponse
	fun order(
		@Valid @RequestBody request: OrderRequest,
	): ContextListResponse = ContextListResponse(contexts.order(request.ids!!).map(ContextResponse::of))

	@DeleteMapping("/{id}", produces = [PROBLEM_JSON])
	@Operation(operationId = "deleteContext", summary = "Delete a context", description = "Deleted for good; it is removed from its tasks.")
	@ApiResponse(responseCode = "204", description = "Deleted")
	@NotFoundResponse
	@IfMatchResponses
	fun delete(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the context") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<Void> {
		contexts.delete(id) { ETags.checkIfMatch(ifMatch, it) }
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}

	/** A concurrent create or rename with the same name passed the service's check but lost on the unique index. */
	private fun <T> nameTaken(action: () -> T): T =
		try {
			action()
		} catch (_: DataIntegrityViolationException) {
			throw ConflictException("A context with this name exists")
		}
}

private fun locationOf(id: UUID): URI =
	ServletUriComponentsBuilder
		.fromCurrentRequestUri()
		.path("/{id}")
		.buildAndExpand(id)
		.toUri()

/** `200` with an area and its ETag. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "200",
	description = "The area with its (new) ETag",
	headers = [Header(name = HttpHeaders.ETAG, description = "Version of the area, for If-Match", schema = Schema(type = "string"))],
	content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = AreaResponse::class))],
)
annotation class AreaOkResponse

/** `200` with a context and its ETag. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "200",
	description = "The context with its (new) ETag",
	headers = [Header(name = HttpHeaders.ETAG, description = "Version of the context, for If-Match", schema = Schema(type = "string"))],
	content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ContextResponse::class))],
)
annotation class ContextOkResponse
