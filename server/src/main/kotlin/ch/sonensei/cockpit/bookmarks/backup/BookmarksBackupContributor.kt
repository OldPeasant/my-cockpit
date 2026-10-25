package ch.sonensei.cockpit.bookmarks.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupReadableWriter
import ch.sonensei.cockpit.backup.domain.jsonLines
import ch.sonensei.cockpit.bookmarks.domain.Bookmark
import ch.sonensei.cockpit.bookmarks.domain.indexBookmark
import ch.sonensei.cockpit.core.backupcontrib.instant
import ch.sonensei.cockpit.core.backupcontrib.instantOrNull
import ch.sonensei.cockpit.core.backupcontrib.toTimestamp
import ch.sonensei.cockpit.core.backupcontrib.toTimestampOrNull
import ch.sonensei.cockpit.core.backupcontrib.uuid
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.stereotype.Component
import java.io.OutputStreamWriter
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * Bookmarks in backups (SPEC.md §8.4, §10.2): `data/bookmarks.jsonl` with every bookmark, those in the Trash included,
 * each with its tags (sorted), read-later state and metadata fetch state. A snapshot is a drive file: the files
 * contributor backs it up, a bookmark keeps only its file id.
 *
 * Mirror: `readable/bookmarks.html` in the Netscape bookmark file format that browsers import (Firefox, Chrome), with
 * every bookmark not in the Trash, newest first as the app lists them: `ADD_DATE`/`LAST_MODIFIED` in Unix seconds,
 * `TAGS` comma-separated (a comma inside a tag becomes a space, the format has no escape for it), `ICON_URI` for the
 * favicon, `TOREAD` for the unread ones in the read-later list and the description as `<DD>`. No file when there is no
 * such bookmark.
 *
 * Restore keeps ids, times, versions and the metadata fetch state (a `PENDING` fetch is requeued when the server
 * starts) and indexes the bookmarks not in the Trash for search again. Links are restored by the links contributor.
 *
 * Rows are read and written with JDBC rather than JPA: a restore must keep ids, `created_at`, `updated_at` and
 * `version` exactly as backed up, which JPA auditing and versioning would overwrite.
 */
@Component
class BookmarksBackupContributor(
	private val jdbc: JdbcTemplate,
	private val indexer: SearchIndexer,
) : BackupContributor {
	override val id = ID

	override fun export(writer: BackupDataWriter) {
		bookmarkRows("select * from bookmark order by id") { writer.jsonLines(BOOKMARKS, it) }
	}

	override fun readable(writer: BackupReadableWriter) {
		val active = "select * from bookmark where deleted_at is null order by created_at desc, id desc"
		val any = jdbc.queryForObject("select exists (select 1 from bookmark where deleted_at is null)", Boolean::class.java)
		if (any != true) return
		writer.stream("bookmarks.html") { out ->
			// Not closed: that would close the backup's stream.
			val html = OutputStreamWriter(out, Charsets.UTF_8)
			html.write(HEADER)
			bookmarkRows(active) { all -> all.forEach { html.write(it.netscapeEntry()) } }
			html.write("</DL><p>\n")
			html.flush()
		}
	}

	override fun import(reader: BackupDataReader) {
		reader.jsonLines<BookmarkBackup>(BOOKMARKS) { bookmark ->
			jdbc.update(
				"""insert into bookmark (id, created_at, updated_at, version, deleted_at, url, title, description, favicon,
				read_later, read_at, snapshot_file_id, metadata_status, metadata_fetch)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				bookmark.id,
				bookmark.createdAt.toTimestamp(),
				bookmark.updatedAt.toTimestamp(),
				bookmark.version,
				bookmark.deletedAt.toTimestampOrNull(),
				bookmark.url,
				bookmark.title,
				bookmark.description,
				bookmark.favicon,
				bookmark.readLater,
				bookmark.readAt.toTimestampOrNull(),
				bookmark.snapshotFileId,
				bookmark.metadataStatus,
				bookmark.metadataFetch,
			)
			bookmark.tags.forEach { tag ->
				jdbc.update("insert into bookmark_tag (bookmark_id, tag) values (?, ?)", bookmark.id, tag)
			}
			if (bookmark.deletedAt == null) indexer.indexBookmark(bookmark.toBookmark())
		}
	}

	/** The bookmarks [sql] selects (`bookmark.*`) with their tags, streamed from the database. */
	private fun <R> bookmarkRows(
		sql: String,
		block: (Sequence<BookmarkBackup>) -> R,
	): R {
		// Tags are few and short: one query for all of them keeps the bookmarks themselves streamed. Sorted in code, not
		// by the database collation, so equal data gives equal files on every install.
		val tags = mutableMapOf<UUID, MutableList<String>>()
		jdbc.query("select bookmark_id, tag from bookmark_tag") { rs ->
			tags.getOrPut(rs.uuid("bookmark_id")) { mutableListOf() } += rs.getString("tag")
		}
		return jdbc
			.queryForStream(
				PreparedStatementCreator { connection -> connection.prepareStatement(sql).apply { fetchSize = FETCH_SIZE } },
			) { rs, _ -> row(rs, tags) }
			.use { block(it.iterator().asSequence()) }
	}

	private fun row(
		rs: ResultSet,
		tags: Map<UUID, List<String>>,
	): BookmarkBackup {
		val id = rs.uuid("id")
		return BookmarkBackup(
			id = id,
			createdAt = rs.instant("created_at"),
			updatedAt = rs.instant("updated_at"),
			version = rs.getLong("version"),
			deletedAt = rs.instantOrNull("deleted_at"),
			url = rs.getString("url"),
			title = rs.getString("title"),
			description = rs.getString("description"),
			favicon = rs.getString("favicon"),
			readLater = rs.getBoolean("read_later"),
			readAt = rs.instantOrNull("read_at"),
			snapshotFileId = rs.getObject("snapshot_file_id", UUID::class.java),
			metadataStatus = rs.getString("metadata_status"),
			metadataFetch = rs.getLong("metadata_fetch"),
			tags = tags[id].orEmpty().sorted(),
		)
	}

	/** A detached [Bookmark] with the fields its search document needs ([indexBookmark]). */
	private fun BookmarkBackup.toBookmark(): Bookmark =
		Bookmark(url, id).also {
			it.title = title
			it.description = description
			it.tags = tags.toMutableSet()
		}

	companion object {
		const val ID = "bookmarks"
		const val BOOKMARKS = "bookmarks"
		private const val FETCH_SIZE = 1000

		private val HEADER =
			"""
			<!DOCTYPE NETSCAPE-Bookmark-file-1>
			<!-- This is an automatically generated file.
			     It will be read and overwritten.
			     DO NOT EDIT! -->
			<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
			<TITLE>Bookmarks</TITLE>
			<H1>Bookmarks</H1>
			<DL><p>

			""".trimIndent()

		/** One `<DT><A>` line, plus a `<DD>` line for the description. */
		private fun BookmarkBackup.netscapeEntry(): String =
			buildString {
				append("    <DT><A HREF=\"").append(escape(url)).append('"')
				append(" ADD_DATE=\"").append(createdAt.epochSecond).append('"')
				append(" LAST_MODIFIED=\"").append(updatedAt.epochSecond).append('"')
				favicon?.let { append(" ICON_URI=\"").append(escape(it)).append('"') }
				if (tags.isNotEmpty()) append(" TAGS=\"").append(escape(tags.joinToString(",") { it.replace(',', ' ') })).append('"')
				if (readLater && readAt == null) append(" TOREAD=\"1\"")
				append('>').append(escape(title?.takeIf { it.isNotBlank() } ?: url)).append("</A>\n")
				description?.takeIf { it.isNotBlank() }?.let { append("    <DD>").append(escape(it)).append('\n') }
			}

		/** Escapes text and attribute values; line breaks become spaces so an entry stays one line. */
		private fun escape(value: String): String =
			value
				.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace(Regex("\r\n|\r|\n"), " ")
	}
}

/** One line of `data/bookmarks.jsonl`. [metadataStatus] by name, as stored; [tags] sorted. */
data class BookmarkBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val deletedAt: Instant?,
	val url: String,
	val title: String?,
	val description: String?,
	val favicon: String?,
	val readLater: Boolean,
	val readAt: Instant?,
	val snapshotFileId: UUID?,
	val metadataStatus: String,
	val metadataFetch: Long,
	val tags: List<String>,
)
