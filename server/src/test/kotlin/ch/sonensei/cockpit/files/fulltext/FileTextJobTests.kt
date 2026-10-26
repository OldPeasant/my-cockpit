package ch.sonensei.cockpit.files.fulltext

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.boot.context.annotation.UserConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskHolder
import java.time.Duration

/**
 * [FileTextJob] (task pdf-fulltext): the batch loop with a mocked [FileTextIndexer], and the scheduling switch of
 * [FileTextJobConfiguration] in a minimal context.
 */
class FileTextJobTests {
	private val indexer = mockk<FileTextIndexer>()
	private val job = FileTextJob(indexer)

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
