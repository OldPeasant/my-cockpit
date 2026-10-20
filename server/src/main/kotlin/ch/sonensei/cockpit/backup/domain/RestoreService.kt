package ch.sonensei.cockpit.backup.domain

import ch.sonensei.cockpit.backup.infra.ZipBackupReader
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Validation report of an uploaded backup. [id] is set only when it is [valid], to confirm the restore with; the other
 * fields come from the manifest as far as it can be read.
 */
data class RestoreReport(
	val id: UUID?,
	val valid: Boolean,
	val formatVersion: Int?,
	val appVersion: String?,
	val createdAt: Instant?,
	val counts: Map<String, Long>,
	/** Why the backup cannot be restored: corrupt zip, wrong format version, sha256 mismatches, ... */
	val problems: List<String>,
	/** What the owner should know before confirming; they don't prevent the restore. */
	val warnings: List<String>,
)

/** A completed restore: the records restored per data name and where the data it replaced was saved. */
data class RestoreResult(
	val counts: Map<String, Long>,
	/** The backup of the replaced data, relative to the data directory (`pre-restore/cockpit-backup-....zip`). */
	val preRestoreBackup: String,
)

/** The backup passed validation, but its data could not be imported (e.g. it breaks a constraint); nothing changed. */
class RestoreFailedException : RuntimeException("The backup could not be imported; nothing was changed")

/**
 * Restore (SPEC.md §10.1): [upload] stores a backup zip and returns its validation report without changing any data;
 * [confirm] replaces all data with it. The replace saves the current data as a backup to
 * `${COCKPIT_DATA_DIR}/pre-restore/` first, then empties the database and imports in one transaction; the data
 * directory (blobs) is moved aside meanwhile and moved back if the import fails, so a failed restore changes nothing.
 *
 * One upload is pending at a time (in a temp dir, in memory only): a new upload discards the previous one.
 */
@Service
class RestoreService(
	private val backups: BackupService,
	private val jobs: BackupJobService,
	private val jdbc: JdbcTemplate,
	private val clock: Clock,
	@Value("\${cockpit.data-dir}") dataDir: Path,
) : DisposableBean {
	private val log = LoggerFactory.getLogger(javaClass)
	private val dataDir: Path = dataDir.toAbsolutePath().normalize()
	private val workDir: Path by lazy { Files.createTempDirectory("cockpit-restores") }
	private var pending: Pair<UUID, Path>? = null

	/** Stores [content] (read to its end, not closed) and validates it. Only a valid upload is kept for [confirm]. */
	@Synchronized
	fun upload(content: InputStream): RestoreReport {
		discardPending()
		val id = UuidV7.generate()
		val path = workDir.resolve("$id.zip")
		try {
			Files.copy(content, path)
			val report = validate(path, id)
			if (report.valid) pending = id to path else Files.deleteIfExists(path)
			return report
		} catch (e: Exception) {
			runCatching { Files.deleteIfExists(path) }
			throw e
		}
	}

	/**
	 * Replaces all data with the upload [id]; the upload is used up, also when the import fails.
	 *
	 * @throws NotFoundException (404) for an unknown or discarded upload
	 * @throws ch.sonensei.cockpit.core.web.ConflictException (409) while a backup is running
	 * @throws InvalidBackupException when the backup's data turns out to be invalid while importing
	 * @throws RestoreFailedException when the import fails for another reason
	 */
	@Synchronized
	fun confirm(id: UUID): RestoreResult {
		val (pendingId, path) = pending?.takeIf { it.first == id } ?: throw NotFoundException("Restore upload not found")
		return jobs
			.exclusive {
				try {
					replace(path)
				} finally {
					discardPending()
				}
			}.also { log.info("Restore {} completed", pendingId) }
	}

	/** Validates the backup at [path] like [upload]; the report's id is [id]. */
	fun validate(
		path: Path,
		id: UUID? = null,
	): RestoreReport {
		val inspection = ZipBackupReader.inspect(path, backups.canonicalJson)
		val manifest = inspection.manifest
		val problems = inspection.problems.toMutableList()
		val warnings = mutableListOf<String>()
		if (problems.isEmpty() && manifest != null) {
			if (manifest.appVersion != backups.appVersion) {
				warnings += "Created by My Cockpit ${manifest.appVersion}; this is version ${backups.appVersion}"
			}
			try {
				warnings += backups.restoreWarnings(path)
			} catch (e: InvalidBackupException) {
				problems += e.problems
			}
		}
		val valid = problems.isEmpty()
		return RestoreReport(
			id = id.takeIf { valid },
			valid = valid,
			formatVersion = manifest?.formatVersion,
			appVersion = manifest?.appVersion,
			createdAt = manifest?.createdAt,
			counts = manifest?.counts.orEmpty(),
			problems = problems,
			warnings = warnings,
		)
	}

	/**
	 * Replaces all data with the valid backup at [path] (see the class comment); also the CLI restore. The caller makes
	 * sure no backup runs ([BackupJobService.exclusive]).
	 *
	 * @throws InvalidBackupException when the backup is invalid
	 * @throws RestoreFailedException when the import fails for another reason
	 */
	fun replace(path: Path): RestoreResult {
		val preRestore = preRestoreBackup()
		val stash = stashDataDir()
		val manifest =
			try {
				backups.replace(path, ::wipeDatabase)
			} catch (e: Throwable) {
				unstash(stash)
				if (e is InvalidBackupException || e is VirtualMachineError) throw e
				// Contributors' messages may quote user content: details only at DEBUG.
				log.warn("Restore failed: {}", e.javaClass.name)
				log.debug("Restore failure", e)
				throw RestoreFailedException()
			}
		if (!stash.toFile().deleteRecursively()) log.warn("Could not delete the previous data directory contents in {}", stash)
		return RestoreResult(manifest.counts, dataDir.relativize(preRestore).joinToString("/"))
	}

	/** Exports the current data to `pre-restore/` and returns the zip's path. */
	private fun preRestoreBackup(): Path {
		val dir = Files.createDirectories(dataDir.resolve(PRE_RESTORE_DIR))
		val name = BackupFormat.fileName(clock.instant()).removeSuffix(".zip")
		val target =
			generateSequence(1) { it + 1 }
				.map { n -> dir.resolve(if (n == 1) "$name.zip" else "$name-$n.zip") }
				.first { !Files.exists(it) }
		try {
			Files.newOutputStream(target).use { backups.export(it) }
		} catch (e: Throwable) {
			runCatching { Files.deleteIfExists(target) }
			throw e
		}
		return target
	}

	/** Moves the data directory's contents (but `pre-restore/` and stashes) into a new stash folder in it. */
	private fun stashDataDir(): Path {
		val stash = Files.createDirectories(dataDir.resolve("$STASH_PREFIX${UuidV7.generate()}"))
		try {
			ownEntries().filter { it != stash }.forEach { Files.move(it, stash.resolve(it.name)) }
		} catch (e: Exception) {
			unstash(stash)
			throw e
		}
		return stash
	}

	/** Deletes what the failed import wrote to the data directory and moves the stashed contents back. */
	private fun unstash(stash: Path) {
		try {
			ownEntries().filter { it != stash }.forEach { it.toFile().deleteRecursively() }
			stash.listDirectoryEntries().forEach { Files.move(it, dataDir.resolve(it.name)) }
			Files.delete(stash)
		} catch (e: Exception) {
			// The previous files are still in the stash and in the pre-restore backup.
			log.error("Could not move the data directory back from {}: {}", stash, e.javaClass.name)
		}
	}

	private fun ownEntries(): List<Path> =
		if (Files.isDirectory(dataDir)) {
			dataDir.listDirectoryEntries().filter { it.name != PRE_RESTORE_DIR && !it.name.startsWith(STASH_PREFIX) }
		} else {
			emptyList()
		}

	/** Empties every table but Flyway's history; runs in the import's transaction. */
	private fun wipeDatabase() {
		val tables =
			jdbc
				.queryForList(
					"select tablename from pg_tables where schemaname = current_schema() and tablename <> 'flyway_schema_history'",
					String::class.java,
				).filterNotNull()
		if (tables.isNotEmpty()) jdbc.execute("truncate table ${tables.joinToString { "\"" + it.replace("\"", "\"\"") + "\"" }} cascade")
	}

	private fun discardPending() {
		pending?.let { (_, path) -> runCatching { Files.deleteIfExists(path) } }
		pending = null
	}

	@Synchronized
	override fun destroy() = discardPending()

	companion object {
		/** Folder of the data directory with the backups made before each restore. */
		const val PRE_RESTORE_DIR = "pre-restore"

		/** Prefix of the folder that holds the data directory's previous contents during a restore. */
		private const val STASH_PREFIX = ".restore-"
	}
}
