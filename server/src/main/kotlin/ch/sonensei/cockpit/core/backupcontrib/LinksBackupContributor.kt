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
 * `data/links.jsonl`: every link between items (SPEC.md §4.3), manual and sync ones. Item ids are kept as they are, so
 * links survive as long as their items' modules restore their ids too (also external ones, SPEC.md §10.2).
 * Mirror: `readable/links.md`.
 */
@Component
class LinksBackupContributor(
	private val jdbc: JdbcTemplate,
) : BackupContributor {
	override val id = ID

	override fun export(writer: BackupDataWriter) {
		links { writer.jsonLines(LINKS, it) }
	}

	override fun readable(writer: BackupReadableWriter) {
		writer.stream("links.md") { out ->
			val text = out.bufferedWriter(Charsets.UTF_8)
			text.appendLine("# Links")
			text.appendLine()
			text.appendLine("Links between items, by item type and id (see `data/` for the items).")
			text.appendLine()
			text.appendLine("| From | To | Label | Origin |")
			text.appendLine("| --- | --- | --- | --- |")
			links { all ->
				all.forEach { link ->
					text.appendLine(
						"| ${link.sourceType.lowercase()}:${link.sourceId} | ${link.targetType.lowercase()}:${link.targetId} " +
							"| ${link.label?.markdownCell().orEmpty()} | ${link.origin.lowercase()} |",
					)
				}
			}
			text.flush()
		}
	}

	override fun import(reader: BackupDataReader) {
		reader.jsonLines<LinkBackup>(LINKS) { link ->
			jdbc.update(
				"""insert into link (id, created_at, updated_at, version, source_type, source_id, target_type, target_id, label, origin)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				link.id,
				link.createdAt.toTimestamp(),
				link.updatedAt.toTimestamp(),
				link.version,
				link.sourceType,
				link.sourceId,
				link.targetType,
				link.targetId,
				link.label,
				link.origin,
			)
		}
	}

	/** Every link, streamed from the database in id order (oldest first). */
	private fun <R> links(block: (Sequence<LinkBackup>) -> R): R =
		jdbc
			.queryForStream("select * from link order by id") { rs, _ ->
				LinkBackup(
					id = rs.uuid("id"),
					createdAt = rs.instant("created_at"),
					updatedAt = rs.instant("updated_at"),
					version = rs.getLong("version"),
					sourceType = rs.getString("source_type"),
					sourceId = rs.uuid("source_id"),
					targetType = rs.getString("target_type"),
					targetId = rs.uuid("target_id"),
					label = rs.getString("label"),
					origin = rs.getString("origin"),
				)
			}.use { block(it.iterator().asSequence()) }

	companion object {
		const val ID = "links"
		const val LINKS = "links"
	}
}

/** One line of `data/links.jsonl`. Types are `ItemType` names, [origin] a `LinkOrigin` name. */
data class LinkBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val sourceType: String,
	val sourceId: UUID,
	val targetType: String,
	val targetId: UUID,
	val label: String?,
	val origin: String,
)
