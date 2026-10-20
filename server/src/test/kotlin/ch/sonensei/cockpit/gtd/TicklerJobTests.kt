package ch.sonensei.cockpit.gtd

import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.gtd.domain.TaskService
import ch.sonensei.cockpit.gtd.domain.TicklerJob
import ch.sonensei.cockpit.gtd.infra.GtdSchedulingConfiguration
import ch.sonensei.cockpit.gtd.infra.UserZoneCronTrigger
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.boot.context.annotation.UserConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.scheduling.config.ScheduledTaskRegistrar
import org.springframework.scheduling.support.SimpleTriggerContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** The tickler job ([TicklerJob]) and its daily schedule ([GtdSchedulingConfiguration]), without a database. */
class TicklerJobTests {
	private val tasks = mockk<TaskService>()
	private val job = TicklerJob(tasks)

	@Test
	fun `the job returns the number of moved tasks`() {
		every { tasks.releaseDue() } returns 3

		job.run() shouldBe 3
	}

	@Test
	fun `a failing run is swallowed, so a read never fails because of it, and the next run tries again`() {
		every { tasks.releaseDue() } throws OptimisticLockingFailureException("a concurrent run moved the task") andThen 1

		job.run() shouldBe 0
		job.run() shouldBe 1
		verify(exactly = 2) { tasks.releaseDue() }
	}

	@Test
	fun `the schedule registers one trigger task that runs the job at 00_05 in the user's zone`() {
		every { tasks.releaseDue() } returns 0
		val settings = mockk<SettingsService>()
		every { settings.timeZone() } returns ZoneId.of("Europe/Zurich")
		val registrar = ScheduledTaskRegistrar()

		GtdSchedulingConfiguration(job, settings, "0 5 0 * * *").configureTasks(registrar)

		registrar.triggerTaskList shouldHaveSize 1
		val task = registrar.triggerTaskList.single()
		val trigger = task.trigger.shouldBeInstanceOf<UserZoneCronTrigger>()
		val context = SimpleTriggerContext(Clock.fixed(Instant.parse("2026-03-01T08:00:00Z"), ZoneOffset.UTC))
		trigger.nextExecution(context) shouldBe Instant.parse("2026-03-01T23:05:00Z")
		task.runnable.run()
		verify(exactly = 1) { tasks.releaseDue() }
	}

	private val contextRunner =
		ApplicationContextRunner()
			.withConfiguration(UserConfigurations.of(GtdSchedulingConfiguration::class.java))
			.withBean(TicklerJob::class.java, { job })
			.withBean(SettingsService::class.java, { mockk<SettingsService>(relaxed = true) })

	@Test
	fun `the schedule is on by default`() {
		contextRunner.run { context ->
			context.getBeansOfType(GtdSchedulingConfiguration::class.java).size shouldBe 1
		}
	}

	@Test
	fun `the schedule is off when the scheduler is disabled, as in the tests`() {
		contextRunner.withPropertyValues("cockpit.gtd.tickler.scheduler.enabled=false").run { context ->
			context.getBeansOfType(GtdSchedulingConfiguration::class.java).size shouldBe 0
		}
	}
}
