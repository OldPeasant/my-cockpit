package ch.sonensei.cockpit.journal.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupReadableWriter
import ch.sonensei.cockpit.backup.domain.json
import ch.sonensei.cockpit.backup.domain.jsonLines
import ch.sonensei.cockpit.core.backupcontrib.instant
import ch.sonensei.cockpit.core.backupcontrib.instantOrNull
import ch.sonensei.cockpit.core.backupcontrib.toTimestamp
import ch.sonensei.cockpit.core.backupcontrib.toTimestampOrNull
import ch.sonensei.cockpit.core.backupcontrib.uuid
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.files.backup.FilesBackupContributor
import ch.sonensei.cockpit.journal.entries.domain.JournalEntry
import ch.sonensei.cockpit.journal.entries.domain.indexJournalEntry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Journal and habits in backups (SPEC.md §8.5, §10.2): `data/journal.jsonl` with every entry (those in the Trash
 * included) and its tags, `data/habits.json` with every habit (archived ones included) and `data/habit_checks.jsonl`
 * with their checks.
 *
 * Mirrors:
 * - `readable/journal/<yyyy>/<yyyy-mm-dd>.md` per entry not in the Trash: front matter with id, date, mood, tags, the
 *   linked items (`type:id`, both directions, each once) and times; then the body with every image `cockpit-file:{id}`
 *   pointing to the file in the backup, `../../../files/<path>` (paths from [FilesBackupContributor]).
 * - `readable/habits.csv`: one row per check (habits without checks get one row with empty date and count), by habit
 *   name and date, with the habit's settings on every row.
 *
 * Restore keeps ids, times, versions and the habit's managed reminder id, and indexes habits and the entries not in the
 * Trash for search again. Links (also the inline ones) are restored by the links contributor, reminders by the
 * reminders contributor, the images by the files contributor.
 *
 * Rows are read and written with JDBC rather than JPA: a restore must keep ids, `created_at`, `updated_at` and
 * `version` exactly as backed up, which JPA auditing and versioning would overwrite.
 */
@Component
class JournalBackupContributor(
	private val jdbc: JdbcTemplate,
	private val files: FilesBackupContributor,
	private val search: SearchIndexer,
) : BackupContributor {
	override val id = ID

	override fun export(writer: BackupDataWriter) {
		entries("select $ENTRY_COLUMNS from journal_entry e order by e.id") { writer.jsonLines(JOURNAL, it) }
		writer.json(HABITS, habits())
		jdbc
			.queryForStream(streamed("select * from habit_check order by habit_id, check_date")) { rs, _ ->
				HabitCheckBackup(
					id = rs.uuid("id"),
					createdAt = rs.instant("created_at"),
					updatedAt = rs.instant("updated_at"),
					version = rs.getLong("version"),
					habitId = rs.uuid("habit_id"),
					date = rs.getObject("check_date", LocalDate::class.java),
					count = rs.getInt("count"),
				)
			}.use { writer.jsonLines(HABIT_CHECKS, it.iterator().asSequence()) }
	}

	override fun readable(writer: BackupReadableWriter) {
		journalMirror(writer)
		habitsMirror(writer)
	}

	override fun import(reader: BackupDataReader) {
		reader.jsonLines<JournalEntryBackup>(JOURNAL) { entry ->
			jdbc.update(
				"""insert into journal_entry (id, created_at, updated_at, version, deleted_at, entry_date, body_md, mood)
				values (?, ?, ?, ?, ?, ?, ?, ?)""",
				entry.id,
				entry.createdAt.toTimestamp(),
				entry.updatedAt.toTimestamp(),
				entry.version,
				entry.deletedAt.toTimestampOrNull(),
				entry.date,
				entry.bodyMd,
				entry.mood,
			)
			entry.tags.forEach { jdbc.update("insert into journal_entry_tag (entry_id, tag) values (?, ?)", entry.id, it) }
			if (entry.deletedAt == null) {
				search.indexJournalEntry(JournalEntry(entry.date, entry.id).apply { bodyMd = entry.bodyMd })
			}
		}
		reader.json<List<HabitBackup>>(HABITS).orEmpty().forEach { habit ->
			jdbc.update(
				"""insert into habit (id, created_at, updated_at, version, name, schedule, times_per_week, weekdays,
				target_count, reminder_time, archived, reminder_id)
				values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""",
				habit.id,
				habit.createdAt.toTimestamp(),
				habit.updatedAt.toTimestamp(),
				habit.version,
				habit.name,
				habit.schedule,
				habit.timesPerWeek,
				WEEKDAYS_JSON.writeValueAsString(habit.weekdays),
				habit.targetCount,
				habit.reminderTime,
				habit.archived,
				habit.reminderId,
			)
			search.upsert(ItemType.HABIT, habit.id, habit.name, null)
		}
		reader.jsonLines<HabitCheckBackup>(HABIT_CHECKS) { check ->
			jdbc.update(
				"""insert into habit_check (id, created_at, updated_at, version, habit_id, check_date, count)
				values (?, ?, ?, ?, ?, ?, ?)""",
				check.id,
				check.createdAt.toTimestamp(),
				check.updatedAt.toTimestamp(),
				check.version,
				check.habitId,
				check.date,
				check.count,
			)
		}
	}

	private fun journalMirror(writer: BackupReadableWriter) {
		val active = "select $ENTRY_COLUMNS from journal_entry e where e.deleted_at is null order by e.entry_date, e.id"
		val images =
			entries(active) { all ->
				all.flatMap { entry -> IMAGE.findAll(entry.bodyMd).map { UUID.fromString(it.groupValues[1]) } }.toSet()
			}
		val paths = files.paths(images)
		val links = links()
		entries(active) { all ->
			all.forEach { entry ->
				// One active entry per date (unique index), so the date alone names the file.
				writer.text("journal/${entry.date.year}/${entry.date}.md", markdown(entry, links[entry.id].orEmpty(), paths))
			}
		}
	}

	private fun habitsMirror(writer: BackupReadableWriter) {
		val habits = habits().sortedWith(compareBy<HabitBackup> { it.name.lowercase() }.thenBy { it.id })
		val checks = mutableMapOf<UUID, MutableList<HabitCheckRow>>()
		jdbc.query(
			"select habit_id, check_date, count from habit_check order by check_date",
			RowCallbackHandler { rs ->
				checks.getOrPut(rs.uuid("habit_id")) { mutableListOf() } +=
					HabitCheckRow(rs.getObject("check_date", LocalDate::class.java), rs.getInt("count"))
			},
		)
		writer.stream("habits.csv") { out ->
			val text = out.bufferedWriter(Charsets.UTF_8)
			text.append(CSV_HEADER.joinToString(",")).append("\r\n")
			habits.forEach { habit ->
				val settings =
					listOf(
						habit.id.toString(),
						habit.name,
						habit.schedule,
						habit.timesPerWeek?.toString().orEmpty(),
						habit.weekdays.joinToString(" "),
						habit.targetCount.toString(),
						habit.reminderTime?.toString().orEmpty(),
						habit.archived.toString(),
					)
				val rows = checks[habit.id].orEmpty().map { listOf(it.date.toString(), it.count.toString()) }.ifEmpty { listOf(listOf("", "")) }
				rows.forEach { check -> text.append((settings + check).joinToString(",") { csv(it) }).append("\r\n") }
			}
			text.flush()
		}
	}

	/** The entries [sql] selects (with [ENTRY_COLUMNS]), streamed from the database. */
	private fun <R> entries(
		sql: String,
		block: (Sequence<JournalEntryBackup>) -> R,
	): R =
		jdbc
			.queryForStream(streamed(sql)) { rs, _ ->
				JournalEntryBackup(
					id = rs.uuid("id"),
					createdAt = rs.instant("created_at"),
					updatedAt = rs.instant("updated_at"),
					version = rs.getLong("version"),
					deletedAt = rs.instantOrNull("deleted_at"),
					date = rs.getObject("entry_date", LocalDate::class.java),
					bodyMd = rs.getString("body_md"),
					mood = rs.getObject("mood") as Int?,
					tags = rs.strings("tags"),
				)
			}.use { block(it.iterator().asSequence()) }

	/** Every habit, by id. Habits are few, so they are read at once (and written as one document). */
	private fun habits(): List<HabitBackup> =
		jdbc.query("select * from habit order by id") { rs, _ ->
			HabitBackup(
				id = rs.uuid("id"),
				createdAt = rs.instant("created_at"),
				updatedAt = rs.instant("updated_at"),
				version = rs.getLong("version"),
				name = rs.getString("name"),
				schedule = rs.getString("schedule"),
				timesPerWeek = rs.getObject("times_per_week") as Int?,
				weekdays = WEEKDAYS_JSON.readValue(rs.getString("weekdays"), Array<String>::class.java).toList(),
				targetCount = rs.getInt("target_count"),
				reminderTime = rs.getObject("reminder_time", LocalTime::class.java),
				archived = rs.getBoolean("archived"),
				reminderId = rs.getObject("reminder_id", UUID::class.java),
			)
		}

	/** [sql] fetched [FETCH_SIZE] rows at a time, so a stream over it does not load the whole result first. */
	private fun streamed(sql: String) =
		PreparedStatementCreator { connection -> connection.prepareStatement(sql).apply { fetchSize = FETCH_SIZE } }

	/** The items linked to each journal entry (`type:id`), each once, oldest link first. */
	private fun links(): Map<UUID, List<String>> {
		val linked = mutableMapOf<UUID, LinkedHashSet<String>>()
		jdbc.query(
			"select source_type, source_id, target_type, target_id from link " +
				"where source_type = ? or target_type = ? order by created_at, id",
			RowCallbackHandler { rs ->
				val source = rs.getString("source_type") to rs.uuid("source_id")
				val target = rs.getString("target_type") to rs.uuid("target_id")
				if (source.first == ENTRY) linked.getOrPut(source.second) { LinkedHashSet() } += ref(target)
				if (target.first == ENTRY) linked.getOrPut(target.second) { LinkedHashSet() } += ref(source)
			},
			ENTRY,
			ENTRY,
		)
		return linked.mapValues { it.value.toList() }
	}

	private fun markdown(
		entry: JournalEntryBackup,
		links: List<String>,
		paths: Map<UUID, String>,
	): String =
		buildString {
			appendLine("---")
			appendLine("id: ${entry.id}")
			appendLine("date: ${entry.date}")
			appendLine("mood: ${entry.mood ?: "null"}")
			appendLine("tags: ${entry.tags.joinToString(", ", "[", "]") { quoted(it) }}")
			appendLine("links: ${links.joinToString(", ", "[", "]") { quoted(it) }}")
			appendLine("created: ${entry.createdAt}")
			appendLine("updated: ${entry.updatedAt}")
			appendLine("---")
			appendLine()
			append(
				IMAGE.replace(entry.bodyMd) { match ->
					paths[UUID.fromString(match.groupValues[1])]?.let { "../../../files/${encoded(it)}" } ?: match.value
				},
			)
			if (entry.bodyMd.isNotEmpty() && !entry.bodyMd.endsWith("\n")) appendLine()
		}

	private data class HabitCheckRow(
		val date: LocalDate,
		val count: Int,
	)

	companion object {
		const val ID = "journal"
		const val JOURNAL = "journal"
		const val HABITS = "habits"
		const val HABIT_CHECKS = "habit_checks"
		private val ENTRY = ItemType.JOURNAL_ENTRY.name
		private const val FETCH_SIZE = 1000
		private const val ENTRY_COLUMNS =
			"e.*, array(select t.tag from journal_entry_tag t where t.entry_id = e.id order by t.tag) as tags"
		private val CSV_HEADER =
			listOf("habit_id", "name", "schedule", "times_per_week", "weekdays", "target_count", "reminder_time", "archived", "date", "count")
		private val WEEKDAYS_JSON: JsonMapper = JsonMapper.builder().build()

		/** An image reference `cockpit-file:{id}` in an entry body. */
		private val IMAGE = Regex("""cockpit-file:([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})""")

		private fun ref(item: Pair<String, UUID>) = "${item.first.lowercase()}:${item.second}"

		/**
		 * A CSV field (RFC 4180): quoted when it holds a comma, quote or line break. A value a spreadsheet would run as a
		 * formula (starting with `=`, `+`, `-`, `@`, tab or CR) gets a leading `'`, so opening the mirror runs nothing.
		 */
		internal fun csv(value: String): String {
			val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
			return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${safe.replace("\"", "\"\"")}\"" else safe
		}

		/**
		 * A YAML double-quoted scalar (the JSON escapes YAML understands). Characters outside YAML's printable set
		 * (C0, DEL, C1, U+FFFE, U+FFFF) and the YAML 1.1 line breaks U+0085, U+2028 and U+2029 become `\uXXXX`.
		 */
		private fun quoted(value: String): String =
			buildString {
				append('"')
				value.forEach { c ->
					when {
						c == '"' -> append("\\\"")
						c == '\\' -> append("\\\\")
						c == '\n' -> append("\\n")
						c == '\t' -> append("\\t")
						c < ' ' || c in '\u007f'..'\u009f' || c == ' ' || c == ' ' || c >= '￾' ->
							append("\\u%04x".format(c.code))
						else -> append(c)
					}
				}
				append('"')
			}

		/** [path] with each segment percent-encoded, so names with spaces or brackets stay one link destination. */
		private fun encoded(path: String): String =
			path.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
	}
}

/** One line of `data/journal.jsonl`. [tags] are sorted. */
data class JournalEntryBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val deletedAt: Instant?,
	val date: LocalDate,
	val bodyMd: String,
	val mood: Int?,
	val tags: List<String>,
)

/**
 * One habit of `data/habits.json`. [schedule] and [weekdays] are the names of `HabitSchedule` and
 * `java.time.DayOfWeek`; [reminderId] is the core reminder the habits module manages for it.
 */
data class HabitBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val name: String,
	val schedule: String,
	val timesPerWeek: Int?,
	val weekdays: List<String>,
	val targetCount: Int,
	val reminderTime: LocalTime?,
	val archived: Boolean,
	val reminderId: UUID?,
)

/** One line of `data/habit_checks.jsonl`. */
data class HabitCheckBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val habitId: UUID,
	val date: LocalDate,
	val count: Int,
)

private fun ResultSet.strings(column: String): List<String> = (getArray(column).array as Array<*>).map { it as String }
