package ch.sonensei.cockpit.gtd.api

import ch.sonensei.cockpit.gtd.domain.Change
import ch.sonensei.cockpit.gtd.domain.ChecklistItem
import ch.sonensei.cockpit.gtd.domain.Creativity
import ch.sonensei.cockpit.gtd.domain.Energy
import ch.sonensei.cockpit.gtd.domain.RecurrenceMode
import ch.sonensei.cockpit.gtd.domain.Task
import ch.sonensei.cockpit.gtd.domain.TaskChanges
import ch.sonensei.cockpit.gtd.domain.TaskList
import ch.sonensei.cockpit.gtd.domain.TaskMove
import com.fasterxml.jackson.annotation.JsonUnwrapped
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.JsonSchema
import jakarta.validation.constraints.NotNull
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Schema(description = "A GTD task. `version` is also sent as the ETag.")
data class TaskResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val title: String,
	val notesMd: String?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val list: TaskList,
	val projectId: UUID?,
	@get:Schema(description = "Order within the project, or within the list for tasks without a project", requiredMode = RequiredMode.REQUIRED)
	val position: Int,
	val energy: Energy?,
	val creativity: Creativity?,
	val timeEstimateMin: Int?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val contextIds: List<UUID>,
	@get:Schema(description = "Local date in the user's time zone") val dueDate: LocalDate?,
	@get:Schema(type = "string", format = "time", example = "14:30:00") val dueTime: LocalTime?,
	val scheduledDate: LocalDate?,
	@get:Schema(description = "While set, the task is hidden; on that date it returns to the Inbox") val ticklerDate: LocalDate?,
	val waitingForContactId: UUID?,
	val waitingForText: String?,
	val waitingSince: LocalDate?,
	@get:Schema(description = "iCalendar RRULE without the `RRULE:` prefix", example = "FREQ=WEEKLY;BYDAY=MO")
	val recurrenceRule: String?,
	val recurrenceMode: RecurrenceMode?,
	@get:Schema(description = "When the task was done; `null` while it is open") val completedAt: Instant?,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val createdAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val updatedAt: Instant,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(task: Task) =
			TaskResponse(
				id = task.id,
				title = task.title,
				notesMd = task.notesMd,
				list = task.list,
				projectId = task.projectId,
				position = task.position,
				energy = task.energy,
				creativity = task.creativity,
				timeEstimateMin = task.timeEstimateMin,
				contextIds = task.contextIds.sorted(),
				dueDate = task.dueDate,
				dueTime = task.dueTime,
				scheduledDate = task.scheduledDate,
				ticklerDate = task.ticklerDate,
				waitingForContactId = task.waitingForContactId,
				waitingForText = task.waitingForText,
				waitingSince = task.waitingSince,
				recurrenceRule = task.recurrenceRule,
				recurrenceMode = task.recurrenceMode,
				completedAt = task.completedAt,
				createdAt = checkNotNull(task.createdAt),
				updatedAt = checkNotNull(task.updatedAt),
				version = task.version,
			)
	}
}

@Schema(description = "A task after `/complete`: the task's fields, and whether its project needs a new next step")
data class CompletedTaskResponse(
	@get:JsonUnwrapped val task: TaskResponse,
	@get:Schema(
		description = "The task was the next step of an ACTIVE project, which is now stalled: ask the user for a new next step",
		requiredMode = RequiredMode.REQUIRED,
	)
	val needsNextStep: Boolean,
)

@Schema(description = "One page of tasks")
data class TaskPageResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<TaskResponse>,
	@get:Schema(description = "Cursor of the next page; `null` on the last page") val nextCursor: String?,
)

@Schema(description = "A new task. Only `title` is required; it goes to the Inbox unless `list` says otherwise.")
data class CreateTaskRequest(
	@get:Schema(description = "Client-generated UUIDv7 for an idempotent create (offline capture)")
	val id: UUID? = null,
	@field:NotNull
	@get:Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 500)
	val title: String? = null,
	val notesMd: String? = null,
	@get:Schema(
		description =
			"Default INBOX; SCHEDULED needs `scheduledDate` (only allowed there), WAITING sets `waitingSince` to today " +
				"(the waiting-for fields and `waitingSince` are only allowed there)",
	)
	val list: TaskList? = null,
	val projectId: UUID? = null,
	@get:Schema(description = "Default: the end of its list or project")
	val position: Int? = null,
	val energy: Energy? = null,
	val creativity: Creativity? = null,
	@get:Schema(minimum = "0", maximum = "10000")
	val timeEstimateMin: Int? = null,
	val contextIds: List<UUID>? = null,
	val dueDate: LocalDate? = null,
	@get:Schema(type = "string", format = "time", example = "14:30:00", description = "Only together with `dueDate`")
	val dueTime: LocalTime? = null,
	val scheduledDate: LocalDate? = null,
	val waitingForContactId: UUID? = null,
	val waitingForText: String? = null,
	val waitingSince: LocalDate? = null,
	val recurrenceRule: String? = null,
	val recurrenceMode: RecurrenceMode? = null,
) {
	fun toChanges() =
		TaskChanges(
			title = title?.let(::Change),
			notesMd = notesMd?.let(::Change),
			projectId = projectId?.let(::Change),
			position = position?.let(::Change),
			energy = energy?.let(::Change),
			creativity = creativity?.let(::Change),
			timeEstimateMin = timeEstimateMin?.let(::Change),
			contextIds = contextIds?.let { Change(it.toSet()) },
			dueDate = dueDate?.let(::Change),
			dueTime = dueTime?.let(::Change),
			scheduledDate = scheduledDate?.let(::Change),
			waitingForContactId = waitingForContactId?.let(::Change),
			waitingForText = waitingForText?.let(::Change),
			waitingSince = waitingSince?.let(::Change),
			recurrenceRule = recurrenceRule?.let(::Change),
			recurrenceMode = recurrenceMode?.let(::Change),
		)
}

/**
 * The fields PATCH accepts (documentation and type conversion of the JSON body; which fields were sent is read from the
 * body itself, see [TaskController.update]).
 */
@Schema(
	description =
		"Changes to a task: only the fields sent change, `null` clears a field (`title`, `position` and `contextIds` " +
			"cannot be cleared). The list is changed with `/move`, completion with `/complete` and `/uncomplete`.",
)
data class TaskPatchRequest(
	@get:Schema(maxLength = 500) val title: String? = null,
	@get:Schema(types = ["string", "null"]) val notesMd: String? = null,
	@get:Schema(types = ["string", "null"], format = "uuid") val projectId: UUID? = null,
	val position: Int? = null,
	@get:Schema(types = ["string", "null"]) val energy: Energy? = null,
	@get:Schema(types = ["string", "null"]) val creativity: Creativity? = null,
	@get:Schema(types = ["integer", "null"], format = "int32", minimum = "0", maximum = "10000") val timeEstimateMin: Int? = null,
	@get:Schema(description = "Replaces the task's contexts") val contextIds: List<UUID>? = null,
	@get:Schema(types = ["string", "null"], format = "date") val dueDate: LocalDate? = null,
	@get:Schema(types = ["string", "null"], format = "time", example = "14:30:00") val dueTime: LocalTime? = null,
	@get:Schema(types = ["string", "null"], format = "date") val scheduledDate: LocalDate? = null,
	@get:Schema(types = ["string", "null"], format = "uuid") val waitingForContactId: UUID? = null,
	@get:Schema(types = ["string", "null"]) val waitingForText: String? = null,
	@get:Schema(types = ["string", "null"], format = "date") val waitingSince: LocalDate? = null,
	@get:Schema(types = ["string", "null"]) val recurrenceRule: String? = null,
	@get:Schema(types = ["string", "null"]) val recurrenceMode: RecurrenceMode? = null,
)

@Schema(
	description =
		"Moves a task to a list. SCHEDULED needs `scheduledDate` (only allowed there); moving to WAITING sets " +
			"`waitingSince` to today unless the task was already waiting, the waiting-for fields are only allowed " +
			"there. Leaving WAITING clears the waiting-for fields, leaving SCHEDULED the scheduled date. " +
			"`ticklerDate` hides the task until that future date; omitted, it clears the tickler.",
)
data class MoveTaskRequest(
	@field:NotNull
	@get:Schema(requiredMode = RequiredMode.REQUIRED)
	val list: TaskList? = null,
	@get:Schema(description = "Puts the task into this project; omitted keeps its project")
	val projectId: UUID? = null,
	val scheduledDate: LocalDate? = null,
	val ticklerDate: LocalDate? = null,
	val waitingForContactId: UUID? = null,
	val waitingForText: String? = null,
) {
	fun toMove() = TaskMove(list!!, projectId, scheduledDate, ticklerDate, waitingForContactId, waitingForText)
}

@Schema(description = "A sub-step of a task. `version` is also sent as the ETag.")
data class ChecklistItemResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val id: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val taskId: UUID,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val text: String,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val done: Boolean,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val position: Int,
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val version: Long,
) {
	companion object {
		fun of(item: ChecklistItem) = ChecklistItemResponse(item.id, item.taskId, item.text, item.done, item.position, item.version)
	}
}

@Schema(description = "A task's checklist in order")
data class ChecklistResponse(
	@get:Schema(requiredMode = RequiredMode.REQUIRED) val items: List<ChecklistItemResponse>,
)

@Schema(description = "A new checklist item, added at the end")
data class CreateChecklistItemRequest(
	@field:NotNull
	@get:Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 1000)
	val text: String? = null,
	val done: Boolean? = null,
)

@Schema(description = "Changes to a checklist item; fields not sent stay unchanged")
data class UpdateChecklistItemRequest(
	@get:Schema(maxLength = 1000) val text: String? = null,
	val done: Boolean? = null,
	val position: Int? = null,
)

/**
 * Adds `null` to the enums of [TaskPatchRequest] (`energy`, `creativity`, `recurrenceMode`): `null` clears them, and in
 * OpenAPI 3.1 `enum` restricts the value besides `type` (same approach as the settings' `SettingsPatchNullEnums`).
 */
@Component
class TaskPatchNullEnums : OpenApiCustomizer {
	override fun customise(openApi: OpenAPI) {
		val properties =
			openApi.components
				?.schemas
				?.get("TaskPatchRequest")
				?.properties ?: return
		for ((name, property) in properties) {
			val values = property.enum ?: continue
			if (null in values || property.types?.contains("null") != true) continue
			properties[name] =
				JsonSchema().apply {
					types = property.types
					description = property.description
					enum = (values + null).toMutableList()
				}
		}
	}
}
