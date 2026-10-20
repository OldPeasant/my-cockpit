package ch.sonensei.cockpit.core.backupcontrib

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupReadableWriter
import ch.sonensei.cockpit.backup.domain.jsonLines
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * `data/reminders.jsonl`: every reminder (SPEC.md §4.6), fired ones too, so a restore never fires a reminder twice.
 * Mirror: `readable/reminders.md` with the reminders not fired yet.
 */
@Component
class RemindersBackupContributor(
	private val jdbc: JdbcTemplate,
) : BackupContributor {
	override val id = ID

	override fun export(writer: BackupDataWriter) {
		reminders("select * from reminder order by id") { writer.jsonLines(REMINDERS, it) }
	}

	override fun readable(writer: BackupReadableWriter) {
		writer.stream("reminders.md") { out ->
			val text = out.bufferedWriter(Charsets.UTF_8)
			text.appendLine("# Reminders")
			text.appendLine()
			text.appendLine("Reminders not fired yet, earliest first (times in UTC).")
			text.appendLine()
			text.appendLine("| When | Item | Channel |")
			text.appendLine("| --- | --- | --- |")
			reminders("select * from reminder where fired_at is null order by remind_at, id") { pending ->
				pending.forEach { reminder ->
					text.appendLine(
						"| ${reminder.remindAt} | ${reminder.itemType.lowercase()}:${reminder.itemId} | ${reminder.channel.lowercase()} |",
					)
				}
			}
			text.flush()
		}
	}

	override fun import(reader: BackupDataReader) {
		reader.jsonLines<ReminderBackup>(REMINDERS) { reminder ->
			jdbc.update(
				"""insert into reminder (id, created_at, updated_at, version, item_type, item_id, remind_at, channel, fired_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				reminder.id,
				reminder.createdAt.toTimestamp(),
				reminder.updatedAt.toTimestamp(),
				reminder.version,
				reminder.itemType,
				reminder.itemId,
				reminder.remindAt.toTimestamp(),
				reminder.channel,
				reminder.firedAt.toTimestampOrNull(),
			)
		}
	}

	/** The reminders [sql] selects, streamed from the database. */
	private fun <R> reminders(
		sql: String,
		block: (Sequence<ReminderBackup>) -> R,
	): R =
		jdbc
			.queryForStream(sql) { rs, _ ->
				ReminderBackup(
					id = rs.uuid("id"),
					createdAt = rs.instant("created_at"),
					updatedAt = rs.instant("updated_at"),
					version = rs.getLong("version"),
					itemType = rs.getString("item_type"),
					itemId = rs.uuid("item_id"),
					remindAt = rs.instant("remind_at"),
					channel = rs.getString("channel"),
					firedAt = rs.instantOrNull("fired_at"),
				)
			}.use { block(it.iterator().asSequence()) }

	companion object {
		const val ID = "reminders"
		const val REMINDERS = "reminders"
	}
}

/** One line of `data/reminders.jsonl`. [itemType] is an `ItemType` name, [channel] a `ReminderChannel` name. */
data class ReminderBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val itemType: String,
	val itemId: UUID,
	val remindAt: Instant,
	val channel: String,
	/** When it fired; `null` while pending. */
	val firedAt: Instant?,
)
