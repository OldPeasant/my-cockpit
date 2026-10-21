package ch.sonensei.cockpit.backup.domain

import tools.jackson.databind.MapperFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The backup zip layout, format version 1 (SPEC.md §10.2). */
object BackupFormat {
	const val VERSION = 1

	const val README = "README.md"
	const val MANIFEST = "manifest.json"
	const val DATA_DIR = "data/"
	const val FILES_DIR = "files/"
	const val READABLE_DIR = "readable/"

	private val FILE_NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmm'Z'").withZone(ZoneOffset.UTC)

	/** `cockpit-backup-2026-10-20T1530Z.zip` */
	fun fileName(createdAt: Instant): String = "cockpit-backup-${FILE_NAME_TIME.format(createdAt)}.zip"

	/**
	 * [mapper] reconfigured for canonical JSON: all properties (constructor ones too) and map keys sorted, so equal data
	 * gives byte-equal files.
	 */
	fun canonical(mapper: JsonMapper): JsonMapper =
		mapper
			.rebuild()
			.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
			.disable(MapperFeature.SORT_CREATOR_PROPERTIES_FIRST)
			.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
			.build()

	private val SEGMENT_FORBIDDEN = Regex("[\\\\\u0000-\u001f]")

	/**
	 * Checks a relative zip path below `files/` or `readable/`: `/`-separated, no empty, `.` or `..` segments, no
	 * backslashes or control characters, so it can never escape its folder when extracted.
	 */
	fun requireSafePath(path: String) {
		val segments = path.split('/')
		require(path.isNotEmpty() && segments.none { it.isEmpty() || it == "." || it == ".." || SEGMENT_FORBIDDEN.containsMatchIn(it) }) {
			"Invalid backup path '$path'"
		}
	}
}

/**
 * `manifest.json`. [counts] has the number of records per data name (`tasks` for `data/tasks.jsonl`); [files] has every
 * other file of the zip with its sha256 (hex) and size, sorted by path.
 */
data class BackupManifest(
	val formatVersion: Int,
	val appVersion: String,
	val createdAt: Instant,
	val counts: Map<String, Long>,
	val files: List<BackupFileEntry>,
)

data class BackupFileEntry(
	val path: String,
	val sha256: String,
	val size: Long,
)

/** The backup cannot be read: not a zip, no manifest, unsupported format version, or files that don't match it. */
open class InvalidBackupException(
	val problems: List<String>,
) : RuntimeException("Invalid backup: ${problems.joinToString("; ")}")
