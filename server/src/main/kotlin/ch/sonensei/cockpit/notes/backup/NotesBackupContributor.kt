package ch.sonensei.cockpit.notes.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupReadableWriter
import ch.sonensei.cockpit.backup.domain.jsonLines
import ch.sonensei.cockpit.core.backupcontrib.instant
import ch.sonensei.cockpit.core.backupcontrib.instantOrNull
import ch.sonensei.cockpit.core.backupcontrib.toTimestamp
import ch.sonensei.cockpit.core.backupcontrib.toTimestampOrNull
import ch.sonensei.cockpit.core.backupcontrib.uuid
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.files.backup.FilesBackupContributor
import ch.sonensei.cockpit.notes.domain.Note
import ch.sonensei.cockpit.notes.domain.indexNote
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.sql.ResultSet
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Notes in backups (SPEC.md §8.1, §10.2): `data/notes.jsonl` with every note (those in the Trash included) and its
 * tags, and `data/note_revisions.jsonl` with the kept revisions of their content.
 *
 * Mirror: `readable/notes/<yyyy-mm-dd>-<slug>.md` per note not in the Trash, named after the day it was created (UTC)
 * and its title (`-2`, `-3`, ... on a clash). Front matter with id, title, tags, the linked items (`type:id`, both
 * directions, each once), times and state; then the body with every image `cockpit-file:{id}` pointing to the file
 * in the backup, `../../files/<path>` (paths from [FilesBackupContributor]).
 *
 * Restore keeps ids, times and versions and indexes the notes not in the Trash for search again. Links (also the
 * inline ones) are restored by the links contributor, the images by the files contributor.
 *
 * Rows are read and written with JDBC rather than JPA: a restore must keep ids, `created_at`, `updated_at` and
 * `version` exactly as backed up, which JPA auditing and versioning would overwrite.
 */
@Component
class NotesBackupContributor(
	private val jdbc: JdbcTemplate,
	private val files: FilesBackupContributor,
	private val search: SearchIndexer,
) : BackupContributor {
	override val id = ID

	override fun export(writer: BackupDataWriter) {
		notes("select $NOTE_COLUMNS from note n order by n.id") { writer.jsonLines(NOTES, it) }
		jdbc
			.queryForStream(streamed("select * from note_revision order by note_id, number")) { rs, _ ->
				NoteRevisionBackup(
					id = rs.uuid("id"),
					createdAt = rs.instant("created_at"),
					updatedAt = rs.instant("updated_at"),
					version = rs.getLong("version"),
					noteId = rs.uuid("note_id"),
					number = rs.getInt("number"),
					title = rs.getString("title"),
					bodyMd = rs.getString("body_md"),
					savedAt = rs.instant("saved_at"),
				)
			}.use { writer.jsonLines(REVISIONS, it.iterator().asSequence()) }
	}

	override fun readable(writer: BackupReadableWriter) {
		val active = "select $NOTE_COLUMNS from note n where n.deleted_at is null order by n.created_at, n.id"
		val images =
			notes(active) { all ->
				all
					.flatMap { note ->
						IMAGE.findAll(note.bodyMd).map { UUID.fromString(it.groupValues[1]) }
					}.toSet()
			}
		val paths = files.paths(images)
		val links = links()
		val names = mutableSetOf<String>()
		notes(active) { all ->
			all.forEach { note ->
				val base = "${note.createdAt.atOffset(ZoneOffset.UTC).toLocalDate()}-${slug(displayTitle(note))}"
				val name = generateSequence(1) { it + 1 }.map { n -> if (n == 1) base else "$base-$n" }.first { names.add(it) }
				writer.text("notes/$name.md", markdown(note, links[note.id].orEmpty(), paths))
			}
		}
	}

	override fun import(reader: BackupDataReader) {
		reader.jsonLines<NoteBackup>(NOTES) { note ->
			jdbc.update(
				"""insert into note (id, created_at, updated_at, version, deleted_at, title, body_md, pinned, archived)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				note.id,
				note.createdAt.toTimestamp(),
				note.updatedAt.toTimestamp(),
				note.version,
				note.deletedAt.toTimestampOrNull(),
				note.title,
				note.bodyMd,
				note.pinned,
				note.archived,
			)
			note.tags.forEach { jdbc.update("insert into note_tag (note_id, tag) values (?, ?)", note.id, it) }
			if (note.deletedAt == null) search.indexNote(Note(note.title, note.bodyMd, note.id))
		}
		reader.jsonLines<NoteRevisionBackup>(REVISIONS) { revision ->
			jdbc.update(
				"""insert into note_revision (id, created_at, updated_at, version, note_id, number, title, body_md, saved_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				revision.id,
				revision.createdAt.toTimestamp(),
				revision.updatedAt.toTimestamp(),
				revision.version,
				revision.noteId,
				revision.number,
				revision.title,
				revision.bodyMd,
				revision.savedAt.toTimestamp(),
			)
		}
	}

	/** The notes [sql] selects (with [NOTE_COLUMNS]), streamed from the database. */
	private fun <R> notes(
		sql: String,
		block: (Sequence<NoteBackup>) -> R,
	): R =
		jdbc
			.queryForStream(streamed(sql)) { rs, _ ->
				NoteBackup(
					id = rs.uuid("id"),
					createdAt = rs.instant("created_at"),
					updatedAt = rs.instant("updated_at"),
					version = rs.getLong("version"),
					deletedAt = rs.instantOrNull("deleted_at"),
					title = rs.getString("title"),
					bodyMd = rs.getString("body_md"),
					pinned = rs.getBoolean("pinned"),
					archived = rs.getBoolean("archived"),
					tags = rs.strings("tags"),
				)
			}.use { block(it.iterator().asSequence()) }

	/** [sql] fetched [FETCH_SIZE] rows at a time, so a stream over it does not load the whole result first. */
	private fun streamed(sql: String) =
		PreparedStatementCreator { connection -> connection.prepareStatement(sql).apply { fetchSize = FETCH_SIZE } }

	/** The items linked to each note (`type:id`), each once, oldest link first. */
	private fun links(): Map<UUID, List<String>> {
		val linked = mutableMapOf<UUID, LinkedHashSet<String>>()
		jdbc.query(
			"select source_type, source_id, target_type, target_id from link " +
				"where source_type = ? or target_type = ? order by created_at, id",
			RowCallbackHandler { rs ->
				val source = rs.getString("source_type") to rs.uuid("source_id")
				val target = rs.getString("target_type") to rs.uuid("target_id")
				if (source.first == NOTE) linked.getOrPut(source.second) { LinkedHashSet() } += ref(target)
				if (target.first == NOTE) linked.getOrPut(target.second) { LinkedHashSet() } += ref(source)
			},
			NOTE,
			NOTE,
		)
		return linked.mapValues { it.value.toList() }
	}

	private fun markdown(
		note: NoteBackup,
		links: List<String>,
		paths: Map<UUID, String>,
	): String =
		buildString {
			appendLine("---")
			appendLine("id: ${note.id}")
			appendLine("title: ${quoted(displayTitle(note))}")
			appendLine("tags: ${note.tags.joinToString(", ", "[", "]") { quoted(it) }}")
			appendLine("links: ${links.joinToString(", ", "[", "]") { quoted(it) }}")
			appendLine("created: ${note.createdAt}")
			appendLine("updated: ${note.updatedAt}")
			appendLine("pinned: ${note.pinned}")
			appendLine("archived: ${note.archived}")
			appendLine("---")
			appendLine()
			append(
				IMAGE.replace(note.bodyMd) { match ->
					paths[UUID.fromString(match.groupValues[1])]?.let { "../../files/${encoded(it)}" } ?: match.value
				},
			)
			if (note.bodyMd.isNotEmpty() && !note.bodyMd.endsWith("\n")) appendLine()
		}

	private fun displayTitle(note: NoteBackup) = Note(note.title, note.bodyMd, note.id).displayTitle

	companion object {
		const val ID = "notes"
		const val NOTES = "notes"
		const val REVISIONS = "note_revisions"
		private val NOTE = ItemType.NOTE.name
		private const val FETCH_SIZE = 1000
		private const val MAX_SLUG_LENGTH = 60
		private const val NOTE_COLUMNS = "n.*, array(select t.tag from note_tag t where t.note_id = n.id order by t.tag) as tags"

		/** An image reference `cockpit-file:{id}` in a note body. */
		private val IMAGE = Regex("""cockpit-file:([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})""")
		private val MARKS = Regex("\\p{M}+")
		private val NOT_SLUG = Regex("[^a-z0-9]+")

		/** [title] in lower case ASCII, words joined by `-` (accents dropped), at most [MAX_SLUG_LENGTH] characters. */
		fun slug(title: String): String =
			Normalizer
				.normalize(title, Normalizer.Form.NFD)
				.replace(MARKS, "")
				.lowercase()
				.replace(NOT_SLUG, "-")
				.trim('-')
				.take(MAX_SLUG_LENGTH)
				.trimEnd('-')
				.ifEmpty { "note" }

		private fun ref(item: Pair<String, UUID>) = "${item.first.lowercase()}:${item.second}"

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

/** One line of `data/notes.jsonl`. [tags] are sorted. */
data class NoteBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val deletedAt: Instant?,
	val title: String?,
	val bodyMd: String,
	val pinned: Boolean,
	val archived: Boolean,
	val tags: List<String>,
)

/** One line of `data/note_revisions.jsonl`. */
data class NoteRevisionBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val noteId: UUID,
	val number: Int,
	val title: String?,
	val bodyMd: String,
	val savedAt: Instant,
)

private fun ResultSet.strings(column: String): List<String> = (getArray(column).array as Array<*>).map { it as String }
