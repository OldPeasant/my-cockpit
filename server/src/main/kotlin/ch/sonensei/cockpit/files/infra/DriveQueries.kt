package ch.sonensei.cockpit.files.infra

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/** SQL read queries of the drive that span the `folder` and `file` tables. */
@Component
class DriveQueries(
	private val jdbc: JdbcTemplate,
) {
	/** An entry of a listing in its sort order: [kind] ([FOLDER] before [FILE]), then [sortName], then [id]. */
	data class Position(
		val kind: Int,
		val id: UUID,
		val sortName: String,
	)

	data class Totals(
		val usedBytes: Long,
		val fileCount: Long,
		val folderCount: Long,
		val trashBytes: Long,
		val trashFileCount: Long,
	)

	/**
	 * Up to [limit] entries of [folderId] (`null`: the root) not in the Trash, after [after] (`null`: from the start):
	 * folders first, then files, each ordered by lower-case name, then id.
	 */
	fun listing(
		folderId: UUID?,
		after: Position?,
		limit: Int,
	): List<Position> {
		val parent = if (folderId == null) "is null" else "= ?"
		val keyset = if (after == null) "" else "where (kind, sort_name, id) > (?, ?, ?)"
		val sql =
			"""
			select kind, id, sort_name from (
			    select $FOLDER as kind, id, lower(name) as sort_name from folder where parent_id $parent and deleted_at is null
			    union all
			    select $FILE as kind, id, lower(name) as sort_name from file where folder_id $parent and deleted_at is null
			) entry
			$keyset
			order by kind, sort_name, id
			limit ?
			""".trimIndent()
		val args = listOfNotNull<Any>(folderId, folderId) + listOfNotNull<Any>(after?.kind, after?.sortName, after?.id) + limit
		return jdbc.query(sql, { rs, _ -> Position(rs.getInt(1), rs.getObject(2, UUID::class.java), rs.getString(3)) }, *args.toTypedArray())
	}

	fun totals(): Totals =
		jdbc.queryForObject(
			"""
			select
			    coalesce(sum(size) filter (where deleted_at is null), 0),
			    count(*) filter (where deleted_at is null),
			    (select count(*) from folder where deleted_at is null),
			    coalesce(sum(size) filter (where deleted_at is not null), 0),
			    count(*) filter (where deleted_at is not null)
			from file
			""".trimIndent(),
		) { rs, _ -> Totals(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)) }!!

	companion object {
		const val FOLDER = 0
		const val FILE = 1
	}
}
