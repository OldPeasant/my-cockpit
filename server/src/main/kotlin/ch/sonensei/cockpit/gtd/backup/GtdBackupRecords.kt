package ch.sonensei.cockpit.gtd.backup

import ch.sonensei.cockpit.gtd.domain.TaskList
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/** One entry of `data/areas.json`. */
data class AreaBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val title: String,
	val position: Int,
)

/** One entry of `data/contexts.json`. */
data class ContextBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val name: String,
	val color: String?,
	val position: Int,
)

/** One entry of `data/projects.json`. [status] is a `ProjectStatus` name; [nextTaskId] the project's next step. */
data class ProjectBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val deletedAt: Instant?,
	val title: String,
	val descriptionMd: String?,
	val areaId: UUID?,
	val status: String,
	val nextTaskId: UUID?,
	val dueDate: LocalDate?,
	val reviewDate: LocalDate?,
	val completedAt: Instant?,
)

/**
 * One line of `data/tasks.jsonl`. [energy], [creativity] and [recurrenceMode] are enum names; [contextIds] are sorted;
 * [nextOccurrenceId] is the occurrence created when this recurring task was completed.
 */
data class TaskBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val deletedAt: Instant?,
	val title: String,
	val notesMd: String?,
	val list: TaskList,
	val projectId: UUID?,
	val position: Int,
	val energy: String?,
	val creativity: String?,
	val timeEstimateMin: Int?,
	val contextIds: List<UUID>,
	val dueDate: LocalDate?,
	val dueTime: LocalTime?,
	val scheduledDate: LocalDate?,
	val ticklerDate: LocalDate?,
	val waitingForContactId: UUID?,
	val waitingForText: String?,
	val waitingSince: LocalDate?,
	val recurrenceRule: String?,
	val recurrenceMode: String?,
	val nextOccurrenceId: UUID?,
	val completedAt: Instant?,
)

/** One line of `data/checklist_items.jsonl`. */
data class ChecklistItemBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val taskId: UUID,
	val text: String,
	val done: Boolean,
	val position: Int,
)

/** Writes tasks as Markdown checkboxes, with their details, notes and checklists indented below. */
internal class TaskMarkdown(
	private val contexts: Map<UUID, String>,
	private val projects: Map<UUID, String>,
	private val checklists: Map<UUID, List<ChecklistItemBackup>>,
) {
	fun appendTasks(
		text: StringBuilder,
		tasks: List<TaskBackup>,
		nextStep: UUID? = null,
		showProject: Boolean = true,
	) {
		if (tasks.isEmpty()) {
			text.append("_No tasks._\n")
			return
		}
		tasks.forEach { task ->
			val box = if (task.completedAt != null) "x" else " "
			val title = task.title.oneLine().ifEmpty { "(untitled)" }
			text.append("- [$box] ").append(if (task.id == nextStep) "**$title** (next step)" else title).append('\n')
			details(task, showProject).takeIf { it.isNotEmpty() }?.let { text.append("  ").append(it.joinToString(" · ")).append('\n') }
			task.notesMd?.takeIf { it.isNotBlank() }?.let { notes ->
				text.append('\n')
				notes.trimEnd().lines().forEach { line -> text.append(if (line.isBlank()) "" else "  $line").append('\n') }
				text.append('\n')
			}
			checklists[task.id].orEmpty().forEach { item ->
				text.append("  - [${if (item.done) "x" else " "}] ${item.text.oneLine()}\n")
			}
		}
	}

	private fun details(
		task: TaskBackup,
		showProject: Boolean,
	): List<String> =
		buildList {
			if (showProject) task.projectId?.let(projects::get)?.let { add("Project: ${it.oneLine()}") }
			task.contextIds.mapNotNull(contexts::get).takeIf { it.isNotEmpty() }?.let { names ->
				add(names.joinToString(" ") { "@${it.removePrefix("@").oneLine()}" })
			}
			task.dueDate?.let { add("Due: $it${task.dueTime?.let { time -> " $time" }.orEmpty()}") }
			task.scheduledDate?.let { add("Scheduled: $it") }
			task.ticklerDate?.let { add("Hidden until: $it") }
			if (task.list == TaskList.WAITING || task.waitingForText != null) {
				task.waitingForText?.let { add("Waiting for: ${it.oneLine()}") }
				task.waitingSince?.let { add("Since: $it") }
			}
			task.recurrenceRule?.let { add("Repeats: $it${if (task.recurrenceMode == "AFTER_COMPLETION") " after completion" else ""}") }
			task.timeEstimateMin?.let { add("$it min") }
			task.energy?.let { add("Energy: ${it.lowercase()}") }
			task.completedAt?.let { add("Done: ${it.date()}") }
		}
}

/** File names claimed in one folder, compared regardless of case. */
internal class UniqueNames {
	private val taken = mutableSetOf<String>()

	/** [name], or its first numbered variant (`a (2)`, `a (3)`, ...) that is not taken yet. */
	fun claim(name: String): String =
		generateSequence(1) { it + 1 }
			.map { n -> if (n == 1) name else "$name ($n)" }
			.first { taken.add(it.lowercase()) }
}

private val FILE_NAME_FORBIDDEN = Regex("[/\\\\\u0000-\u001f\u007f]")
private const val MAX_FILE_NAME = 100

/** [title] as a file name (without extension): one line, no path separators, not empty, not starting with a dot. */
internal fun fileName(title: String): String =
	title
		.replace(FILE_NAME_FORBIDDEN, " ")
		.replace(Regex("\\s+"), " ")
		.trim()
		.trimStart('.')
		.take(MAX_FILE_NAME)
		.trim()
		.ifEmpty { "Untitled" }

internal fun String.oneLine(): String = replace(Regex("\\s*(\r\n|\r|\n)\\s*"), " ").trim()

internal fun Instant.date(): LocalDate = atOffset(ZoneOffset.UTC).toLocalDate()

internal fun Instant.timestamp(): Timestamp = Timestamp.from(this)

internal fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

internal fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

internal fun ResultSet.instant(column: String): Instant = getTimestamp(column).toInstant()

internal fun ResultSet.instantOrNull(column: String): Instant? = getTimestamp(column)?.toInstant()
