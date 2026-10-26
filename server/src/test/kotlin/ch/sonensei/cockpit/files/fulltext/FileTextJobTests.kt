package ch.sonensei.cockpit.files.fulltext

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.boot.context.annotation.UserConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * [FileTextJob] (task pdf-fulltext): the batch loop with a mocked [FileTextIndexer] (on an executor that runs the
 * extraction right away), the hand-off to its own executor, and the scheduling switch of [FileTextJobConfiguration] in a
 * minimal context.
 */
class FileTextJobTests {
	private val indexer = mockk<FileTextIndexer>()
	private val direct = Executor { it.run() }
	private val job = FileTextJob(indexer, direct)

	private val contextRunner =
		ApplicationContextRunner()
			.withConfiguration(UserConfigurations.of(FileTextJobConfiguration::class.java))
			.withBean(FileTextIndexer::class.java, { indexer })

	@Test
	fun `a run indexes batches until none is pending`() {
		every { indexer.indexPending() } returnsMany listOf(20, 3, 0)

		job.run()

		verify(exactly = 3) { indexer.indexPending() }
	}

	@Test
	fun `a run with nothing pending looks once`() {
		every { indexer.indexPending() } returns 0

		job.run()

		verify(exactly = 1) { indexer.indexPending() }
	}

	@Test
	fun `a run stops after 50 batches, the rest of a large backlog waits for the next run`() {
		every { indexer.indexPending() } returns 20

		job.run()

		verify(exactly = 50) { indexer.indexPending() }
	}

	@Test
	fun `a failing batch ends the run without throwing, and the next run tries again`() {
		every { indexer.indexPending() } returns 5 andThenThrows IllegalStateException("database unavailable") andThen 0

		assertDoesNotThrow { job.run() }
		verify(exactly = 2) { indexer.indexPending() }

		job.run()
		verify(exactly = 3) { indexer.indexPending() }
	}

	@Test
	fun `a run starts no new batch after the time limit, the rest waits for the next run`() {
		var now = 0L
		val timed = FileTextJob(indexer, direct) { now }
		every { indexer.indexPending() } answers {
			now += Duration.ofMinutes(2).toNanos()
			20
		}

		timed.run()

		// Batches end at 2, 4 and 6 minutes; the third one passes the 5-minute limit.
		verify(exactly = 3) { indexer.indexPending() }
	}

	@Test
	fun `the extraction runs on the executor, and a tick while it is busy does nothing`() {
		val queued = mutableListOf<Runnable>()
		val handOff = FileTextJob(indexer, Executor { queued += it })
		every { indexer.indexPending() } returns 0

		handOff.run()
		handOff.run()

		queued shouldHaveSize 1
		verify(exactly = 0) { indexer.indexPending() }

		queued.single().run()
		verify(exactly = 1) { indexer.indexPending() }

		handOff.run()
		queued shouldHaveSize 2
	}

	@Test
	fun `a run that throws still frees the job for the next tick`() {
		val queued = mutableListOf<Runnable>()
		val handOff = FileTextJob(indexer, Executor { queued += it })
		every { indexer.indexPending() } throws StackOverflowError() andThen 0

		handOff.run()
		runCatching { queued.single().run() }
		handOff.run()

		queued shouldHaveSize 2
	}

	@Test
	fun `a rejected hand-off (shutdown) frees the job`() {
		var reject = true
		val queued = mutableListOf<Runnable>()
		val handOff =
			FileTextJob(
				indexer,
				Executor {
					if (reject) throw RejectedExecutionException("shut down")
					queued += it
				},
			)

		assertDoesNotThrow { handOff.run() }
		reject = false
		handOff.run()

		queued shouldHaveSize 1
	}

	@Test
	fun `close shuts the executor down`() {
		val executor = FileTextJob.newExecutor()

		FileTextJob(indexer, executor).close()

		executor.isShutdown shouldBe true
	}

	@Test
	fun `after close a tick starts nothing`() {
		every { indexer.indexPending() } returns 0

		job.close()
		job.run()

		verify(exactly = 0) { indexer.indexPending() }
	}

	@Test
	fun `close lets the running extraction finish without interrupting it and starts no further batch`() {
		val executor = FileTextJob.newExecutor()
		val stopping = FileTextJob(indexer, executor)
		val started = CountDownLatch(1)
		var interrupted: Boolean? = null
		every { indexer.indexPending() } answers {
			started.countDown()
			Thread.sleep(300)
			interrupted = Thread.currentThread().isInterrupted
			20
		}

		stopping.run()
		started.await(10, TimeUnit.SECONDS) shouldBe true
		stopping.close()

		executor.isTerminated shouldBe true
		interrupted shouldBe false
		verify(exactly = 1) { indexer.indexPending() }
	}

	@Test
	fun `close interrupts an extraction that outlasts the grace period`() {
		val service = mockk<ExecutorService>(relaxed = true)
		every { service.awaitTermination(any(), any()) } returns false andThen true

		FileTextJob(indexer, service).close()

		verifyOrder {
			service.shutdown()
			service.awaitTermination(FileTextJob.SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)
			service.shutdownNow()
			service.awaitTermination(FileTextJob.SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)
		}
	}

	@Test
	fun `close does not interrupt an extraction that ends within the grace period`() {
		val service = mockk<ExecutorService>(relaxed = true)
		every { service.awaitTermination(any(), any()) } returns true

		FileTextJob(indexer, service).close()

		verify(exactly = 1) { service.shutdown() }
		verify(exactly = 0) { service.shutdownNow() }
	}

	@Test
	fun `close interrupted while it waits stops the executor at once and keeps the interrupt`() {
		val service = mockk<ExecutorService>(relaxed = true)
		every { service.awaitTermination(any(), any()) } throws InterruptedException("fictitious")

		val interrupted =
			try {
				FileTextJob(indexer, service).close()
				Thread.currentThread().isInterrupted
			} finally {
				Thread.interrupted()
			}

		interrupted shouldBe true
		verify(exactly = 1) { service.shutdownNow() }
	}

	@Test
	fun `close with a plain executor only stops new ticks`() {
		val queued = mutableListOf<Runnable>()
		val plain = FileTextJob(indexer, Executor { queued += it })

		assertDoesNotThrow { plain.close() }
		plain.run()

		queued.shouldBeEmpty()
	}

	@Test
	fun `a run on an interrupted thread starts no batch`() {
		every { indexer.indexPending() } returns 0

		try {
			Thread.currentThread().interrupt()
			job.run()
		} finally {
			Thread.interrupted()
		}

		verify(exactly = 0) { indexer.indexPending() }
	}

	@Test
	fun `close during a run starts no further batch`() {
		every { indexer.indexPending() } answers {
			job.close()
			20
		}

		job.run()

		verify(exactly = 1) { indexer.indexPending() }
	}

	@Test
	fun `the configured job extracts on its own file-text thread`() {
		val latch = CountDownLatch(1)
		var thread: String? = null
		every { indexer.indexPending() } answers {
			thread = Thread.currentThread().name
			latch.countDown()
			0
		}
		contextRunner.run { context ->
			context.getBean(FileTextJob::class.java).run()
			latch.await(10, TimeUnit.SECONDS) shouldBe true
		}
		thread.shouldNotBeNull() shouldBe "file-text"
	}

	@Test
	fun `by default the job is scheduled every 30 seconds, first one minute after the start`() {
		contextRunner.run { context ->
			context.getBeansOfType(FileTextJob::class.java).values shouldHaveSize 1
			val tasks = context.getBean(ScheduledTaskHolder::class.java).scheduledTasks.map { it.task }
			tasks shouldHaveSize 1
			val task = tasks.single().shouldBeInstanceOf<FixedDelayTask>()
			task.intervalDuration shouldBe Duration.ofSeconds(30)
			task.initialDelayDuration shouldBe Duration.ofMinutes(1)
			task.runnable.toString() shouldBe "${FileTextJob::class.java.name}.run"
		}
	}

	@Test
	fun `the interval and the initial delay are configurable`() {
		contextRunner
			.withPropertyValues("cockpit.files.fulltext.interval=PT5M", "cockpit.files.fulltext.initial-delay=PT10S")
			.run { context ->
				val task =
					context
						.getBean(ScheduledTaskHolder::class.java)
						.scheduledTasks
						.single()
						.task
						.shouldBeInstanceOf<FixedDelayTask>()
				task.intervalDuration shouldBe Duration.ofMinutes(5)
				task.initialDelayDuration shouldBe Duration.ofSeconds(10)
			}
	}

	@Test
	fun `scheduler enabled false creates no job`() {
		contextRunner.withPropertyValues("cockpit.files.fulltext.scheduler.enabled=false").run { context ->
			context.getBeansOfType(FileTextJob::class.java).values.shouldBeEmpty()
		}
	}
}
