package ch.sonensei.cockpit.backup.domain

import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import java.io.InputStream
import java.io.OutputStream

/**
 * One module's part of a backup (SPEC.md §10.2). Each module registers one contributor as a Spring bean;
 * [BackupContributorRegistry] orders them by [dependsOn] and [BackupService] calls them.
 *
 * - [export] writes the module's canonical data (`data/`, lossless, the only part restore reads) and blobs (`files/`).
 * - [readable] writes human-readable mirrors (`readable/`: Markdown, ICS, vCard, ...), regenerated on every backup.
 * - [import] recreates the module's data from a backup into an empty install (the caller wipes it first).
 *
 * All contributors export and write mirrors in one read-only transaction (one consistent snapshot) and import in one
 * transaction, in dependency order: a contributor's dependencies are exported and imported before it.
 */
interface BackupContributor {
	/** Unique, stable id (a-z, 0-9, `-`), e.g. `gtd`; [dependsOn] refers to it. */
	val id: String

	/** Ids of the contributors whose data must be imported before this one's (e.g. the user before settings). */
	val dependsOn: Set<String> get() = emptySet()

	fun export(writer: BackupDataWriter)

	fun readable(writer: BackupReadableWriter) {}

	fun import(reader: BackupDataReader)
}

/**
 * Writes the canonical part of a backup. Data names (`a-z`, `0-9`, `_`) become `data/<name>.json` or
 * `data/<name>.jsonl`; each name may be written once per backup, across all contributors. Values are serialized as
 * canonical JSON (properties and map keys sorted, ISO-8601 dates), so equal data gives byte-equal files.
 */
interface BackupDataWriter {
	/** `data/<name>.json`: one document. Counted in the manifest as its size if it is a collection, else 1. */
	fun json(
		name: String,
		value: Any,
	)

	/** `data/<name>.jsonl`: one record per line, streamed (for large sets). Counted as the number of records. */
	fun jsonLines(
		name: String,
		records: Sequence<Any>,
	)

	/**
	 * `files/<path>`: a blob, streamed. [path] is relative (`/`-separated, no `.`/`..` segments) and unique; the
	 * contributor resolves name clashes and keeps the mapping in its data.
	 */
	fun file(
		path: String,
		content: InputStream,
	)
}

/** Writes human-readable mirrors to `readable/<path>` (relative, `/`-separated, unique). */
interface BackupReadableWriter {
	fun text(
		path: String,
		content: String,
	)

	fun stream(
		path: String,
		write: (OutputStream) -> Unit,
	)
}

/** Reads the canonical part of a backup that has been validated (format version, sha256 of every file). */
interface BackupDataReader {
	/** Format version of the backup; [BackupFormat.VERSION] once migrations of older versions exist. */
	val formatVersion: Int

	/** Whether `data/<name>.json` or `data/<name>.jsonl` exists. */
	fun has(name: String): Boolean

	/** `data/<name>.json`, or `null` when the backup has none. */
	fun <T : Any> json(
		name: String,
		type: TypeReference<T>,
	): T?

	/** Every record of `data/<name>.jsonl` in order, streamed; nothing when the backup has none. */
	fun <T : Any> jsonLines(
		name: String,
		type: TypeReference<T>,
		consumer: (T) -> Unit,
	)

	/** Opens `files/<path>`, or `null` when missing. The caller closes the stream. */
	fun file(path: String): InputStream?

	/** Paths (relative to `files/`) of every blob in the backup, sorted. */
	fun files(): List<String>
}

inline fun <reified T : Any> BackupDataReader.json(name: String): T? = json(name, object : TypeReference<T>() {})

inline fun <reified T : Any> BackupDataReader.jsonLines(
	name: String,
	noinline consumer: (T) -> Unit,
) = jsonLines(name, object : TypeReference<T>() {}, consumer)

/**
 * All [BackupContributor]s in dependency order: each after the ones it depends on, otherwise by id. Fails at startup
 * on duplicate ids, invalid ids, unknown dependencies and cycles.
 */
@Component
class BackupContributorRegistry(
	contributors: List<BackupContributor>,
) {
	val ordered: List<BackupContributor> = order(contributors)

	companion object {
		private val ID = Regex("[a-z0-9]+(-[a-z0-9]+)*")

		fun order(contributors: List<BackupContributor>): List<BackupContributor> {
			val byId =
				contributors.groupBy { it.id }.mapValues { (id, found) ->
					check(ID.matches(id)) { "Invalid BackupContributor id '$id'" }
					check(found.size == 1) { "More than one BackupContributor with id '$id': ${found.map { it.javaClass.name }}" }
					found.single()
				}
			byId.values.forEach { contributor ->
				val unknown = contributor.dependsOn - byId.keys
				check(unknown.isEmpty()) { "BackupContributor '${contributor.id}' depends on unknown contributors $unknown" }
			}
			// Kahn's algorithm; among the ready ones the smallest id goes first, so the order is deterministic.
			val pending = byId.mapValuesTo(mutableMapOf()) { (_, it) -> it.dependsOn.toMutableSet() }
			val ordered = mutableListOf<BackupContributor>()
			while (pending.isNotEmpty()) {
				val next = pending.filterValues { it.isEmpty() }.keys.minOrNull()
				checkNotNull(next) { "BackupContributor dependencies form a cycle among ${pending.keys.sorted()}" }
				pending.remove(next)
				pending.values.forEach { it.remove(next) }
				ordered += byId.getValue(next)
			}
			return ordered
		}
	}
}
