package ch.sonensei.cockpit.gtd.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupReadableWriter
import ch.sonensei.cockpit.backup.domain.json
import ch.sonensei.cockpit.backup.domain.jsonLines
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.gtd.domain.TaskList
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * GTD in backups (SPEC.md §5, §10.2): `data/areas.json`, `data/contexts.json`, `data/projects.json`,
 * `data/tasks.jsonl` (with each task's context ids) and `data/checklist_items.jsonl`, the Trash included.
 *
 * Mirrors, without the Trash: `readable/tasks/{inbox,next,waiting,someday}.md` with the open tasks of each list (Next
 * with the scheduled ones in a section of their own, Waiting For by waiting-since), `readable/tasks/logbook.md` with the done tasks (newest first),
 * and `readable/projects/<area>/<project>.md` per project with its description and tasks as checkboxes. Projects
 * without an area go to `projects/No area/`; titles become file names with `/`, `\`, control characters and the
 * characters Windows forbids (`:*?"<>|`) replaced, trailing dots and spaces trimmed, cut to 100 code points, and clashes (regardless of case) numbered ` (2)`, ` (3)`, ...
 *
 * Restore keeps ids, times and versions. References inside the module (a project's next step, a task's next
 * occurrence) are set once all rows exist; tasks and projects not in the Trash are indexed for search again.
 *
 * Rows are read and written with JDBC rather than JPA: a restore must keep ids, `created_at`, `updated_at` and
 * `version` exactly as backed up, which JPA auditing and versioning would overwrite.
 */
@Component
class GtdBackupContributor(
	private val jdbc: JdbcTemplate,
	private val search: SearchIndexer,
) : BackupContributor {
	override val id = ID

	override fun export(writer: BackupDataWriter) {
		writer.json(AREAS, areas())
		writer.json(CONTEXTS, contexts())
		writer.json(PROJECTS, projects())
		tasks { writer.jsonLines(TASKS, it) }
		checklistItems { writer.jsonLines(CHECKLIST_ITEMS, it) }
	}

	override fun readable(writer: BackupReadableWriter) {
		val areas = areas().associateBy { it.id }
		val contexts = contexts().associate { it.id to it.name }
		val projects = projects().filter { it.deletedAt == null }
		val projectTitles = projects.associate { it.id to it.title }
		val tasks = tasks { all -> all.filter { it.deletedAt == null }.toList() }
		val checklists = checklistItems { all -> all.groupBy { it.taskId } }
		val markdown = TaskMarkdown(contexts, projectTitles, checklists)

		val open = tasks.filter { it.completedAt == null }.groupBy { it.list }
		LIST_FILES.forEach { (list, file) ->
			val text = StringBuilder("# ${list.label}\n\n")
			markdown.appendTasks(text, open[list].orEmpty().sortedWith(if (list == TaskList.WAITING) WAITING_ORDER else LIST_ORDER))
			if (list == TaskList.NEXT) {
				text.append("\n## ${TaskList.SCHEDULED.label}\n\n")
				markdown.appendTasks(
					text,
					open[TaskList.SCHEDULED].orEmpty().sortedWith(compareBy<TaskBackup>({ it.scheduledDate }) then LIST_ORDER),
				)
			}
			writer.text("tasks/$file", text.toString())
		}
		val logbook = StringBuilder("# Logbook\n\n")
		markdown.appendTasks(
			logbook,
			tasks
				.filter {
					it.completedAt != null
				}.sortedWith(compareByDescending<TaskBackup> { it.completedAt }.thenBy { it.id }),
		)
		writer.text("tasks/logbook.md", logbook.toString())

		val byProject = tasks.filter { it.projectId != null }.groupBy { it.projectId }
		val folders = UniqueNames()
		val folderOf = mutableMapOf<UUID?, Pair<String, UniqueNames>>()
		projects.sortedWith(compareBy({ it.areaId?.let(areas::get)?.position ?: Int.MAX_VALUE }, { it.areaId }, { it.id })).forEach { project ->
			val area = project.areaId?.let(areas::get)
			val (folder, names) = folderOf.getOrPut(area?.id) { folders.claim(fileName(area?.title ?: NO_AREA)) to UniqueNames() }
			val text = StringBuilder("# ${project.title.oneLine()}\n\n")
			text.append("- Status: ${project.status.lowercase().replace('_', ' ')}\n")
			area?.let { text.append("- Area: ${it.title.oneLine()}\n") }
			project.dueDate?.let { text.append("- Due: $it\n") }
			project.reviewDate?.let { text.append("- Review: $it\n") }
			project.completedAt?.let { text.append("- Completed: ${it.date()}\n") }
			project.descriptionMd?.takeIf { it.isNotBlank() }?.let { text.append("\n").append(it.trimEnd()).append("\n") }
			text.append("\n## Tasks\n\n")
			val projectTasks = byProject[project.id].orEmpty()
			val (done, todo) = projectTasks.partition { it.completedAt != null }
			markdown.appendTasks(
				text,
				todo.sortedWith(LIST_ORDER) + done.sortedWith(compareBy<TaskBackup> { it.completedAt }.thenBy { it.id }),
				nextStep = project.nextTaskId,
				showProject = false,
			)
			writer.text("projects/$folder/${names.claim(fileName(project.title))}.md", text.toString())
		}
	}

	override fun import(reader: BackupDataReader) {
		reader.json<List<AreaBackup>>(AREAS).orEmpty().forEach { area ->
			jdbc.update(
				"insert into area (id, created_at, updated_at, version, title, position) values (?, ?, ?, ?, ?, ?)",
				area.id,
				area.createdAt.timestamp(),
				area.updatedAt.timestamp(),
				area.version,
				area.title,
				area.position,
			)
		}
		reader.json<List<ContextBackup>>(CONTEXTS).orEmpty().forEach { context ->
			jdbc.update(
				"insert into context (id, created_at, updated_at, version, name, color, position) values (?, ?, ?, ?, ?, ?, ?)",
				context.id,
				context.createdAt.timestamp(),
				context.updatedAt.timestamp(),
				context.version,
				context.name,
				context.color,
				context.position,
			)
		}
		val projects = reader.json<List<ProjectBackup>>(PROJECTS).orEmpty()
		// Next steps are tasks, so they are set once the tasks exist.
		projects.forEach { project ->
			jdbc.update(
				"""insert into project (id, created_at, updated_at, version, deleted_at, title, description_md, area_id, status,
				due_date, review_date, completed_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				project.id,
				project.createdAt.timestamp(),
				project.updatedAt.timestamp(),
				project.version,
				project.deletedAt?.timestamp(),
				project.title,
				project.descriptionMd,
				project.areaId,
				project.status,
				project.dueDate,
				project.reviewDate,
				project.completedAt?.timestamp(),
			)
			if (project.deletedAt == null) search.upsert(ItemType.PROJECT, project.id, project.title, project.descriptionMd)
		}
		// Next occurrences are tasks too: set once all tasks exist.
		val nextOccurrences = mutableMapOf<UUID, UUID>()
		reader.jsonLines<TaskBackup>(TASKS) { task ->
			insert(task)
			task.nextOccurrenceId?.let { nextOccurrences[task.id] = it }
		}
		nextOccurrences.forEach { (task, next) -> jdbc.update("update task set next_occurrence_id = ? where id = ?", next, task) }
		projects.filter { it.nextTaskId != null }.forEach { project ->
			jdbc.update("update project set next_task_id = ? where id = ?", project.nextTaskId, project.id)
		}
		reader.jsonLines<ChecklistItemBackup>(CHECKLIST_ITEMS) { item ->
			jdbc.update(
				"insert into checklist_item (id, created_at, updated_at, version, task_id, text, done, position) values (?, ?, ?, ?, ?, ?, ?, ?)",
				item.id,
				item.createdAt.timestamp(),
				item.updatedAt.timestamp(),
				item.version,
				item.taskId,
				item.text,
				item.done,
				item.position,
			)
		}
	}

	private fun insert(task: TaskBackup) {
		jdbc.update(
			"""insert into task (id, created_at, updated_at, version, deleted_at, title, notes_md, list, project_id, position,
			energy, creativity, time_estimate_min, due_date, due_time, scheduled_date, tickler_date, waiting_for_contact_id,
			waiting_for_text, waiting_since, recurrence_rule, recurrence_mode, completed_at)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			task.id,
			task.createdAt.timestamp(),
			task.updatedAt.timestamp(),
			task.version,
			task.deletedAt?.timestamp(),
			task.title,
			task.notesMd,
			task.list.name,
			task.projectId,
			task.position,
			task.energy,
			task.creativity,
			task.timeEstimateMin,
			task.dueDate,
			task.dueTime,
			task.scheduledDate,
			task.ticklerDate,
			task.waitingForContactId,
			task.waitingForText,
			task.waitingSince,
			task.recurrenceRule,
			task.recurrenceMode,
			task.completedAt?.timestamp(),
		)
		task.contextIds.forEach { context ->
			jdbc.update("insert into task_context (task_id, context_id) values (?, ?)", task.id, context)
		}
		if (task.deletedAt == null) search.upsert(ItemType.TASK, task.id, task.title, task.notesMd)
	}

	private fun areas(): List<AreaBackup> =
		jdbc.query("select * from area order by id") { rs, _ ->
			AreaBackup(
				id = rs.uuid("id"),
				createdAt = rs.instant("created_at"),
				updatedAt = rs.instant("updated_at"),
				version = rs.getLong("version"),
				title = rs.getString("title"),
				position = rs.getInt("position"),
			)
		}

	private fun contexts(): List<ContextBackup> =
		jdbc.query("select * from context order by id") { rs, _ ->
			ContextBackup(
				id = rs.uuid("id"),
				createdAt = rs.instant("created_at"),
				updatedAt = rs.instant("updated_at"),
				version = rs.getLong("version"),
				name = rs.getString("name"),
				color = rs.getString("color"),
				position = rs.getInt("position"),
			)
		}

	private fun projects(): List<ProjectBackup> =
		jdbc.query("select * from project order by id") { rs, _ ->
			ProjectBackup(
				id = rs.uuid("id"),
				createdAt = rs.instant("created_at"),
				updatedAt = rs.instant("updated_at"),
				version = rs.getLong("version"),
				deletedAt = rs.instantOrNull("deleted_at"),
				title = rs.getString("title"),
				descriptionMd = rs.getString("description_md"),
				areaId = rs.uuidOrNull("area_id"),
				status = rs.getString("status"),
				nextTaskId = rs.uuidOrNull("next_task_id"),
				dueDate = rs.getObject("due_date", LocalDate::class.java),
				reviewDate = rs.getObject("review_date", LocalDate::class.java),
				completedAt = rs.instantOrNull("completed_at"),
			)
		}

	/** Every task with its context ids, streamed from the database in id order. */
	private fun <R> tasks(block: (Sequence<TaskBackup>) -> R): R =
		streamed(
			"""select t.*, array(select tc.context_id from task_context tc where tc.task_id = t.id order by tc.context_id) as context_ids
			from task t order by t.id""",
			block,
		) { rs ->
			TaskBackup(
				id = rs.uuid("id"),
				createdAt = rs.instant("created_at"),
				updatedAt = rs.instant("updated_at"),
				version = rs.getLong("version"),
				deletedAt = rs.instantOrNull("deleted_at"),
				title = rs.getString("title"),
				notesMd = rs.getString("notes_md"),
				list = TaskList.valueOf(rs.getString("list")),
				projectId = rs.uuidOrNull("project_id"),
				position = rs.getInt("position"),
				energy = rs.getString("energy"),
				creativity = rs.getString("creativity"),
				timeEstimateMin = (rs.getObject("time_estimate_min") as Number?)?.toInt(),
				contextIds = (rs.getArray("context_ids").array as Array<*>).map { it as UUID },
				dueDate = rs.getObject("due_date", LocalDate::class.java),
				dueTime = rs.getObject("due_time", LocalTime::class.java),
				scheduledDate = rs.getObject("scheduled_date", LocalDate::class.java),
				ticklerDate = rs.getObject("tickler_date", LocalDate::class.java),
				waitingForContactId = rs.uuidOrNull("waiting_for_contact_id"),
				waitingForText = rs.getString("waiting_for_text"),
				waitingSince = rs.getObject("waiting_since", LocalDate::class.java),
				recurrenceRule = rs.getString("recurrence_rule"),
				recurrenceMode = rs.getString("recurrence_mode"),
				nextOccurrenceId = rs.uuidOrNull("next_occurrence_id"),
				completedAt = rs.instantOrNull("completed_at"),
			)
		}

	/** Every checklist item, streamed from the database by task and position. */
	private fun <R> checklistItems(block: (Sequence<ChecklistItemBackup>) -> R): R =
		streamed("select * from checklist_item order by task_id, position, id", block) { rs ->
			ChecklistItemBackup(
				id = rs.uuid("id"),
				createdAt = rs.instant("created_at"),
				updatedAt = rs.instant("updated_at"),
				version = rs.getLong("version"),
				taskId = rs.uuid("task_id"),
				text = rs.getString("text"),
				done = rs.getBoolean("done"),
				position = rs.getInt("position"),
			)
		}

	/**
	 * The rows of [sql], mapped and passed to [block] as a sequence. The fetch size makes PgJDBC read them through a
	 * cursor (the export runs in a transaction) instead of loading them all at once.
	 */
	private fun <T, R> streamed(
		sql: String,
		block: (Sequence<T>) -> R,
		map: (ResultSet) -> T,
	): R =
		jdbc
			.queryForStream(
				PreparedStatementCreator { connection -> connection.prepareStatement(sql).apply { fetchSize = FETCH_SIZE } },
			) { rs, _ -> map(rs) }
			.use { block(it.iterator().asSequence()) }

	companion object {
		const val ID = "gtd"
		const val AREAS = "areas"
		const val CONTEXTS = "contexts"
		const val PROJECTS = "projects"
		const val TASKS = "tasks"
		const val CHECKLIST_ITEMS = "checklist_items"
		private const val FETCH_SIZE = 1000
		private const val NO_AREA = "No area"

		private val LIST_FILES =
			linkedMapOf(
				TaskList.INBOX to "inbox.md",
				TaskList.NEXT to "next.md",
				TaskList.WAITING to "waiting.md",
				TaskList.SOMEDAY to "someday.md",
			)

		private val LIST_ORDER = compareBy<TaskBackup>({ it.position }, { it.id })

		/** The Waiting For list is sorted by waiting-since (SPEC.md §5.3); tasks without a date come last. */
		private val WAITING_ORDER = compareBy<TaskBackup, LocalDate?>(nullsLast()) { it.waitingSince } then LIST_ORDER
	}
}
