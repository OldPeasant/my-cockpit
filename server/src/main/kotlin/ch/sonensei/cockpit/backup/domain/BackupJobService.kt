package ch.sonensei.cockpit.backup.domain

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.NotFoundException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class BackupJobState { RUNNING, COMPLETED, FAILED }

/** A snapshot of a backup job. [fileName] and [size] are set once it is [BackupJobState.COMPLETED]. */
data class BackupJob(
	val id: UUID,
	val state: BackupJobState,
	val createdAt: Instant,
	val step: String,
	val stepsDone: Int,
	val stepsTotal: Int,
	val finishedAt: Instant? = null,
	val fileName: String? = null,
	val size: Long? = null,
)

/**
 * Backups on demand (SPEC.md §10.1): [start] builds the zip on a background thread, one backup at a time. Every
 * progress step is announced as a [ChangeOp.UPDATED] change event of type [EVENT_TYPE] on the SSE channel, so
 * clients refetch the job. Jobs live in memory only; starting a new backup discards finished ones and their zips.
 */
@Service
class BackupJobService(
	private val backups: BackupService,
	private val changes: ChangeEventPublisher,
	private val clock: Clock,
) : DisposableBean {
	private val log = LoggerFactory.getLogger(javaClass)
	private val jobs = ConcurrentHashMap<UUID, BackupJob>()
	private val zips = ConcurrentHashMap<UUID, Path>()
	private val workDir: Path by lazy { Files.createTempDirectory("cockpit-backups") }
	private val executor: ExecutorService =
		Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "backup").apply { isDaemon = true } }

	/**
	 * Publishes the change events, in order, outside of the export's snapshot transaction: SSE delivers change events
	 * after the publishing transaction commits, which would hold back all progress until the export is finished.
	 */
	private val events: ExecutorService =
		Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "backup-events").apply { isDaemon = true } }

	/**
	 * Starts a backup and returns its job in state [BackupJobState.RUNNING]. With a [passphrase], the backup includes
	 * the secrets, encrypted; the passphrase is kept only until the backup is written.
	 *
	 * @throws ConflictException (409) while another backup is running
	 */
	@Synchronized
	fun start(passphrase: String? = null): BackupJob {
		if (jobs.values.any { it.state == BackupJobState.RUNNING }) throw ConflictException("A backup is already running")
		discardFinished()
		val job =
			BackupJob(
				id = UuidV7.generate(),
				state = BackupJobState.RUNNING,
				createdAt = clock.instant(),
				step = "queued",
				stepsDone = 0,
				stepsTotal = backups.exportSteps,
			)
		jobs[job.id] = job
		publish(job.id, ChangeOp.CREATED)
		executor.execute { run(job.id, passphrase) }
		return job
	}

	/**
	 * Runs [block] (a restore) while no backup runs: [start] waits until it is finished.
	 *
	 * @throws ConflictException (409) while a backup is running
	 */
	@Synchronized
	fun <T> exclusive(block: () -> T): T {
		if (jobs.values.any { it.state == BackupJobState.RUNNING }) throw ConflictException("A backup is running")
		return block()
	}

	/** @throws NotFoundException (404) for an unknown or discarded job */
	fun get(id: UUID): BackupJob = jobs[id] ?: throw NotFoundException("Backup not found")

	/**
	 * The zip of a completed job.
	 *
	 * @throws NotFoundException (404) for an unknown or discarded job
	 * @throws ConflictException (409) while the job is running or when it failed
	 */
	fun zip(id: UUID): Pair<BackupJob, Path> {
		val job = get(id)
		val zip = zips[id]
		if (job.state != BackupJobState.COMPLETED || zip == null) throw ConflictException("The backup is not ready")
		return job to zip
	}

	private fun run(
		id: UUID,
		passphrase: String?,
	) {
		val target = workDir.resolve("$id.zip")
		try {
			val manifest =
				Files.newOutputStream(target).use { out ->
					backups.export(out, passphrase) { progress ->
						update(id) { it.copy(step = progress.step, stepsDone = progress.done, stepsTotal = progress.total) }
					}
				}
			zips[id] = target
			update(id) {
				it.copy(
					state = BackupJobState.COMPLETED,
					finishedAt = clock.instant(),
					fileName = BackupFormat.fileName(manifest.createdAt),
					size = Files.size(target),
				)
			}
		} catch (e: Throwable) {
			// Errors too (e.g. NotImplementedError from TODO()): a job left RUNNING would block every later backup.
			// Contributors' messages may quote user content: details go to the log (at WARN without content), not
			// to the client.
			log.warn("Backup {} failed: {}", id, e.javaClass.name)
			log.debug("Backup {} failure", id, e)
			runCatching { Files.deleteIfExists(target) }
			update(id) { it.copy(state = BackupJobState.FAILED, finishedAt = clock.instant()) }
			if (e is VirtualMachineError) throw e
		}
	}

	private fun update(
		id: UUID,
		change: (BackupJob) -> BackupJob,
	) {
		jobs.computeIfPresent(id) { _, job -> change(job) }
		publish(id, ChangeOp.UPDATED)
	}

	private fun publish(
		id: UUID,
		op: ChangeOp,
	) = events.execute { changes.publish(EVENT_TYPE, id, op) }

	private fun discardFinished() {
		jobs.values.filter { it.state != BackupJobState.RUNNING }.forEach { job ->
			jobs.remove(job.id)
			zips.remove(job.id)?.let { runCatching { Files.deleteIfExists(it) } }
		}
	}

	override fun destroy() {
		executor.shutdownNow()
		events.shutdownNow()
		zips.values.forEach { runCatching { Files.deleteIfExists(it) } }
	}

	companion object {
		/** The `type` of the change events that announce backup progress. */
		const val EVENT_TYPE = "BACKUP"
	}
}
