package ch.sonensei.cockpit.gtd.api

import ch.sonensei.cockpit.gtd.domain.Area
import ch.sonensei.cockpit.gtd.domain.Change
import ch.sonensei.cockpit.gtd.domain.Context
import ch.sonensei.cockpit.gtd.domain.ProjectChanges
import ch.sonensei.cockpit.gtd.domain.ProjectStatus
import ch.sonensei.cockpit.gtd.domain.ProjectView
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Schema(description = "A GTD project. `version` is also sent as the ETag.")
data class ProjectResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val title: String,
	val descriptionMd: String?,
	val areaId: UUID?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val status: ProjectStatus,
	@get:Schema(description = "The designated next step (a task of the project), set with `POST /next-step`")
	val nextTaskId: UUID?,
	@get:Schema(
		description = "ACTIVE, and the next step is not an open task of the project (none set, done, in the Trash or moved away)",
		requiredMode = RequiredMode.REQUIRED,
	)
	val stalled: Boolean,
	val dueDate: LocalDate?,
	val reviewDate: LocalDate?,
	@get:Schema(description = "When the status became COMPLETED; `null` otherwise") val completedAt: Instant?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val createdAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val updatedAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(view: ProjectView) =
			with(view.project) {
				ProjectResponse(
					id = id,
					title = title,
					descriptionMd = descriptionMd,
					areaId = areaId,
					status = status,
					nextTaskId = nextTaskId,
					stalled = view.stalled,
					dueDate = dueDate,
					reviewDate = reviewDate,
					completedAt = completedAt,
					createdAt = checkNotNull(createdAt),
					updatedAt = checkNotNull(updatedAt),
					version = version,
				)
			}
	}
}

@Schema(description = "One page of projects")
data class ProjectPageResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<ProjectResponse>,
	@get:Schema(description = "Cursor of the next page; `null` on the last page") val nextCursor: String?,
)

@Schema(description = "A new project. Only `title` is required; the status defaults to ACTIVE.")
data class CreateProjectRequest(
	@field:NotNull
	@get:Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 500)
	val title: String? = null,
	val descriptionMd: String? = null,
	val areaId: UUID? = null,
	val status: ProjectStatus? = null,
	val dueDate: LocalDate? = null,
	val reviewDate: LocalDate? = null,
) {
	fun toChanges() =
		ProjectChanges(
			title = title?.let(::Change),
			descriptionMd = descriptionMd?.let(::Change),
			areaId = areaId?.let(::Change),
			status = status?.let(::Change),
			dueDate = dueDate?.let(::Change),
			reviewDate = reviewDate?.let(::Change),
		)
}

/** The fields PATCH accepts (documentation and type conversion; which fields were sent is read from the JSON body). */
@Schema(
	description =
		"Changes to a project: only the fields sent change, `null` clears a field (`title` and `status` cannot be " +
			"cleared). The next step is set with `/next-step`.",
)
data class ProjectPatchRequest(
	@get:Schema(maxLength = 500) val title: String? = null,
	@get:Schema(types = ["string", "null"]) val descriptionMd: String? = null,
	@get:Schema(types = ["string", "null"], format = "uuid") val areaId: UUID? = null,
	@get:Schema(description = "COMPLETED records `completedAt`, any other status clears it") val status: ProjectStatus? = null,
	@get:Schema(types = ["string", "null"], format = "date") val dueDate: LocalDate? = null,
	@get:Schema(types = ["string", "null"], format = "date") val reviewDate: LocalDate? = null,
)

@Schema(description = "The project's new next step")
data class NextStepRequest(
	@get:Schema(
		description = "An open task of the project; `null` clears the next step",
		types = ["string", "null"],
		format = "uuid",
		requiredMode = RequiredMode.REQUIRED,
	)
	val taskId: UUID? = null,
)

@Schema(description = "A new order of a project's tasks")
data class OrderTasksRequest(
	@field:NotNull
	@get:Schema(
		description = "Tasks of the project in their new order; its other tasks follow in their previous order",
		requiredMode = RequiredMode.REQUIRED,
	)
	val taskIds: List<UUID>? = null,
)

@Schema(description = "A list of tasks in order")
data class TaskListResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<TaskResponse>,
)

@Schema(description = "A new order of all items")
data class OrderRequest(
	@field:NotNull
	@get:Schema(description = "Every id exactly once, in the new order", requiredMode = RequiredMode.REQUIRED)
	val ids: List<UUID>? = null,
)

@Schema(description = "An area of focus. `version` is also sent as the ETag.")
data class AreaResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val title: String,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val position: Int,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(area: Area) = AreaResponse(area.id, area.title, area.position, area.version)
	}
}

@Schema(description = "All areas in order")
data class AreaListResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<AreaResponse>,
)

@Schema(description = "A new area")
data class CreateAreaRequest(
	@field:NotNull
	@get:Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 200)
	val title: String? = null,
	@get:Schema(description = "Default: at the end")
	val position: Int? = null,
)

@Schema(description = "Changes to an area; fields not sent stay unchanged")
data class UpdateAreaRequest(
	@get:Schema(maxLength = 200) val title: String? = null,
	val position: Int? = null,
)

@Schema(description = "A context such as `@home`. `version` is also sent as the ETag.")
data class ContextResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val name: String,
	@get:Schema(description = "Hex color, e.g. `#3366ff`") val color: String?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val position: Int,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(context: Context) = ContextResponse(context.id, context.name, context.color, context.position, context.version)
	}
}

@Schema(description = "All contexts in order")
data class ContextListResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<ContextResponse>,
)

@Schema(description = "A new context. Names are trimmed and unique regardless of case.")
data class CreateContextRequest(
	@field:NotNull
	@get:Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 100, example = "@home")
	val name: String? = null,
	@get:Schema(description = "Hex color (`#rgb` or `#rrggbb`)", example = "#3366ff")
	val color: String? = null,
	@get:Schema(description = "Default: at the end")
	val position: Int? = null,
)

/** The fields PATCH accepts for a context (which fields were sent is read from the JSON body). */
@Schema(description = "Changes to a context: only the fields sent change; `null` clears `color` (the others cannot be cleared)")
data class ContextPatchRequest(
	@get:Schema(maxLength = 100) val name: String? = null,
	@get:Schema(types = ["string", "null"], description = "Hex color (`#rgb` or `#rrggbb`)") val color: String? = null,
	val position: Int? = null,
)
