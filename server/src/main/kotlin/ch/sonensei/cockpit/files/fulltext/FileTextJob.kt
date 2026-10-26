package ch.sonensei.cockpit.files.fulltext

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [FileTextJob] unless `cockpit.files.fulltext.scheduler.enabled=false` (the test configuration turns it off, so
 * tests call [FileTextIndexer.indexPending] themselves).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = ["cockpit.files.fulltext.scheduler.enabled"], havingValue = "true", matchIfMissing = true)
class FileTextJobConfiguration {
	@Bean
	fun fileTextJob(indexer: FileTextIndexer) = FileTextJob(indexer, FileTextJob.newExecutor())
}

/**
 * Every `cockpit.files.fulltext.interval` (default 30 s, first run 1 min after the start) extracts the text of new
 * files in batches ([FileTextIndexer.indexPending]), until none is left, the run's batch limit is reached or the run
 * took [MAX_RUN_TIME]. Files stored before full-text search existed are caught up the same way.
 *
 * The extraction runs on [executor] (its own thread), not on the scheduler thread the other jobs share, so a large
 * backlog or a slow PDF never delays reminders or syncs. A tick while the previous run is still busy does nothing.
 * [close] (at the context's shutdown) stops the job cooperatively: no new batch starts, the running extraction gets
 * [SHUTDOWN_GRACE] to finish, and only then is the thread interrupted (the indexer stores nothing for an interrupted
 * extraction), so no healthy file is marked failed and no thread keeps writing while the database pool closes.
 */
class FileTextJob(
	private val indexer: FileTextIndexer,
	private val executor: Executor,
	private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
	private val log = LoggerFactory.getLogger(javaClass)
	private val busy = AtomicBoolean()
	private val stopping = AtomicBoolean()

	@Scheduled(
		fixedDelayString = "\${cockpit.files.fulltext.interval:PT30S}",
		initialDelayString = "\${cockpit.files.fulltext.initial-delay:PT1M}",
	)
	fun run() {
		if (stopping.get() || !busy.compareAndSet(false, true)) return
		try {
			executor.execute {
				try {
					extract()
				} finally {
					busy.set(false)
				}
			}
		} catch (_: RejectedExecutionException) {
			// Shutting down.
			busy.set(false)
		}
	}

	private fun extract() {
		val deadline = nanoTime() + MAX_RUN_TIME.toNanos()
		try {
			for (batch in 1..MAX_BATCHES_PER_RUN) {
				if (stopped() || indexer.indexPending() == 0 || nanoTime() - deadline >= 0) break
			}
		} catch (e: RuntimeException) {
			// The database or a bug; retried on the next run.
			log.warn("File text extraction failed: {}", e.javaClass.name)
		}
	}

	private fun stopped() = stopping.get() || Thread.currentThread().isInterrupted

	override fun close() {
		stopping.set(true)
		val service = executor as? ExecutorService ?: return
		service.shutdown()
		try {
			if (!service.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
				service.shutdownNow()
				service.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)
			}
		} catch (_: InterruptedException) {
			service.shutdownNow()
			Thread.currentThread().interrupt()
		}
	}

	companion object {
		/** Bounds one run; a larger backlog continues on the next run. */
		const val MAX_BATCHES_PER_RUN = 50

		/** Bounds one run by time; no new batch starts after it, a larger backlog continues on the next run. */
		val MAX_RUN_TIME: Duration = Duration.ofMinutes(5)

		/** How long [close] waits for the running extraction before it interrupts it, and again after that. */
		val SHUTDOWN_GRACE: Duration = Duration.ofSeconds(5)

		/** A single daemon thread named `file-text`. */
		fun newExecutor(): ExecutorService =
			Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "file-text").apply { isDaemon = true } }
	}
}
