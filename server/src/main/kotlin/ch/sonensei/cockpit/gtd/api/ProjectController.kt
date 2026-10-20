package ch.sonensei.cockpit.gtd.api

import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.core.web.ETags
import ch.sonensei.cockpit.gtd.domain.ProjectChanges
import ch.sonensei.cockpit.gtd.domain.ProjectFilter
import ch.sonensei.cockpit.gtd.domain.ProjectService
import ch.sonensei.cockpit.gtd.domain.ProjectStatus
import ch.sonensei.cockpit.gtd.domain.ProjectView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import io.swagger.v3.oas.annotations.parameters.RequestBody as DocumentedBody

/**
 * GTD projects (SPEC.md §5.4): list with filters, CRUD, the next step and the order of their tasks. Responses carry the
 * project's `ETag`; PATCH and DELETE need it in `If-Match`, `/next-step` checks it when it is sent.
 */
@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "Projects", description = "GTD projects, areas of focus and contexts")
class ProjectController(
	private val projects: ProjectService,
	private val jsonMapper: JsonMapper,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "listProjects",
		summary = "List projects",
		description = "Projects matching every given filter, not in the Trash, ordered by title, then id.",
	)
	@ApiResponse(responseCode = "200", description = "One page of projects")
	@BadRequestResponse
	fun list(
		@Parameter(description = "Only projects with this status") @RequestParam(required = false) status: ProjectStatus?,
		@Parameter(description = "Only projects in this area") @RequestParam(required = false) areaId: UUID?,
		@Parameter(description = "true: only stalled projects, false: only projects that are not stalled")
		@RequestParam(required = false)
		stalled: Boolean?,
		@Parameter(description = "Cursor from the previous page") @RequestParam(required = false) cursor: String?,
		@Parameter(description = "Page size, 1-200 (default 50)") @RequestParam(required = false) limit: Int?,
	): ProjectPageResponse {
		val page = projects.list(ProjectFilter(status, areaId, stalled), CursorRequest.of(cursor, limit))
		return ProjectPageResponse(page.items.map(ProjectResponse::of), page.nextCursor)
	}

	@PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "createProject", summary = "Create a project")
	@ApiResponse(
		responseCode = "201",
		description = "Created",
		headers = [Header(name = HttpHeaders.ETAG, description = "Version of the project, for If-Match", schema = Schema(type = "string"))],
		content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ProjectResponse::class))],
	)
	@BadRequestResponse
	fun create(
		@Valid @RequestBody request: CreateProjectRequest,
	): ResponseEntity<ProjectResponse> {
		val view = projects.create(request.toChanges())
		val location =
			ServletUriComponentsBuilder
				.fromCurrentRequestUri()
				.path("/{id}")
				.buildAndExpand(view.project.id)
				.toUri()
		return ResponseEntity
			.created(location)
			.eTag(ETags.of(view.project.version))
			.body(ProjectResponse.of(view))
	}

	@GetMapping("/{id}", produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(operationId = "getProject", summary = "One project")
	@ProjectOkResponse
	@NotFoundResponse
	fun get(
		@PathVariable id: UUID,
	): ResponseEntity<ProjectResponse> = respond(projects.get(id))

	@PatchMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "updateProject",
		summary = "Change a project",
		description = "Only the fields sent change; `null` clears a field. Unknown fields (including `nextTaskId`) are rejected.",
	)
	@DocumentedBody(content = [Content(schema = Schema(implementation = ProjectPatchRequest::class))])
	@ProjectOkResponse
	@BadRequestResponse
	@NotFoundResponse
	@IfMatchResponses
	fun update(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the project") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody body: Map<String, Any?>,
	): ResponseEntity<ProjectResponse> {
		val patch = jsonMapper.parsePatch(body, ProjectPatchRequest::class.java, PATCH_FIELDS, setOf("title", "status"))
		val changes =
			with(patch.request) {
				ProjectChanges(
					title = patch.required("title", title),
					descriptionMd = patch.change("descriptionMd", descriptionMd),
					areaId = patch.change("areaId", areaId),
					status = patch.required("status", status),
					dueDate = patch.change("dueDate", dueDate),
					reviewDate = patch.change("reviewDate", reviewDate),
				)
			}
		return respond(projects.update(id, changes) { ETags.checkIfMatch(ifMatch, it) })
	}

	@DeleteMapping("/{id}", produces = [PROBLEM_JSON])
	@Operation(operationId = "deleteProject", summary = "Move a project to the Trash", description = "Its tasks keep their project.")
	@ApiResponse(responseCode = "204", description = "In the Trash")
	@NotFoundResponse
	@IfMatchResponses
	fun delete(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the project") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
	): ResponseEntity<Void> {
		projects.delete(id) { ETags.checkIfMatch(ifMatch, it) }
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}

	@PostMapping("/{id}/next-step", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "setProjectNextStep",
		summary = "Designate the project's next step",
		description =
			"The task must be an open task of the project (create a new one with `POST /tasks` and `projectId` first); " +
				"`taskId: null` clears the next step. `If-Match` is optional.",
	)
	@ProjectOkResponse
	@BadRequestResponse
	@NotFoundResponse
	@IfMatchResponses
	fun setNextStep(
		@PathVariable id: UUID,
		@Parameter(description = "ETag of the project (optional)") @RequestHeader(HttpHeaders.IF_MATCH, required = false) ifMatch: String?,
		@RequestBody request: NextStepRequest,
	): ResponseEntity<ProjectResponse> =
		respond(
			projects.setNextStep(id, request.taskId) { version ->
				if (!ifMatch.isNullOrBlank()) ETags.checkIfMatch(ifMatch, version)
			},
		)

	@PutMapping("/{id}/order", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE, PROBLEM_JSON])
	@Operation(
		operationId = "orderProjectTasks",
		summary = "Order the project's tasks",
		description =
			"The listed tasks come first, in the given order, then the project's other tasks (open and done) in their " +
				"previous order. Changes the tasks' `position` (and version), not the project.",
	)
	@ApiResponse(responseCode = "200", description = "All tasks of the project (not in the Trash) in the new order")
	@BadRequestResponse
	@NotFoundResponse
	fun orderTasks(
		@PathVariable id: UUID,
		@Valid @RequestBody request: OrderTasksRequest,
	): TaskListResponse = TaskListResponse(projects.orderTasks(id, request.taskIds!!).map(TaskResponse::of))

	private fun respond(view: ProjectView): ResponseEntity<ProjectResponse> = ETags.ok(ProjectResponse.of(view), view.project.version)

	private companion object {
		val PATCH_FIELDS = setOf("title", "descriptionMd", "areaId", "status", "dueDate", "reviewDate")
	}
}

/** `200` with a project and its ETag. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ApiResponse(
	responseCode = "200",
	description = "The project with its (new) ETag",
	headers = [Header(name = HttpHeaders.ETAG, description = "Version of the project, for If-Match", schema = Schema(type = "string"))],
	content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ProjectResponse::class))],
)
annotation class ProjectOkResponse
