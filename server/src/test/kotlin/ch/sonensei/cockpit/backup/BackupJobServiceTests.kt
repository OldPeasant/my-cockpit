package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupContributorRegistry
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupJobService
import ch.sonensei.cockpit.backup.domain.BackupJobState
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.NotFoundException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** [BackupJobService] without Spring: job states, the order of its change events, and the zips it leaves on disk. */
class BackupJobServiceTests {
	private object NoTransactions : AbstractPlatformTransactionManager() {
		override fun doGetTransaction(): Any = Any()

		override fun doBegin(
			transaction: Any,
			definition: TransactionDefinition,
		) = Unit

		override fun doCommit(status: DefaultTransactionStatus) = Unit

		override fun doRollback(status: DefaultTransactionStatus) = Unit
	}

	private class Module : BackupContributor {
		override val id = "gtd"

		@Volatile var gate: CountDownLatch? = null

		@Volatile var fail = false

		@Volatile var unimplemented = false

		@Volatile var fatal = false

		override fun export(writer: BackupDataWriter) {
			gate?.await(10, TimeUnit.SECONDS)
			check(!fail) { "export failed" }
			if (unimplemented) TODO("export of tasks")
			// A real StackOverflowError would be thrown the same way; this one is cheap and deterministic.
			if (fatal) throw StackOverflowError("fictitious fatal error")
			writer.jsonLines("tasks", sequenceOf(mapOf("title" to "Call plumber")))
		}

		override fun import(reader: BackupDataReader) = Unit
	}

	private val clock = Clock.fixed(Instant.parse("2026-10-20T15:30:12Z"), ZoneOffset.UTC)
	private val module = Module()
	private val events = CopyOnWriteArrayList<ChangeEvent>()
	private val publisher = ChangeEventPublisher(ApplicationEventPublisher { events += it as ChangeEvent }, clock)
	private val backups = BackupService(BackupContributorRegistry(listOf(module)), JsonMapper.builder().build(), clock, NoTransactions)
	private val jobs = BackupJobService(backups, publisher, clock)

	@AfterEach
	fun tearDown() {
		module.gate?.countDown()
		jobs.destroy()
	}

	private fun awaitFinished(id: UUID): BackupJobState {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
		while (jobs.get(id).state == BackupJobState.RUNNING) {
			check(System.nanoTime() < deadline) { "Backup $id did not finish" }
			Thread.sleep(5)
		}
		return jobs.get(id).state
	}

	private fun awaitEvents(count: Int) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
		while (events.size < count) {
			check(System.nanoTime() < deadline) { "Only ${events.size} of $count events" }
			Thread.sleep(5)
		}
	}

	@Test
	fun `a job announces itself once, then every progress step and the completion, in order`() {
		val job = jobs.start()
		job.state shouldBe BackupJobState.RUNNING
		job.step shouldBe "queued"
		job.stepsDone shouldBe 0
		job.stepsTotal shouldBe 3

		awaitFinished(job.id) shouldBe BackupJobState.COMPLETED
		// 3 steps + "done" + the completion
		awaitEvents(6)

		events.map { it.op } shouldContainExactly listOf(ChangeOp.CREATED) + List(5) { ChangeOp.UPDATED }
		events.map { it.type }.toSet() shouldBe setOf(BackupJobService.EVENT_TYPE)
		events.map { it.id }.toSet() shouldBe setOf(job.id)
		val done = jobs.get(job.id)
		done.stepsDone shouldBe 3
		done.step shouldBe "done"
		done.finishedAt shouldBe clock.instant()
		done.fileName shouldBe "cockpit-backup-2026-10-20T1530Z.zip"
		done.size shouldBe Files.size(jobs.zip(job.id).second)
	}

	@Test
	fun `only one backup runs at a time`() {
		module.gate = CountDownLatch(1)
		val running = jobs.start()

		shouldThrow<ConflictException> { jobs.start() }
		shouldThrow<ConflictException> { jobs.zip(running.id) }

		module.gate!!.countDown()
		awaitFinished(running.id) shouldBe BackupJobState.COMPLETED
		jobs.start()
	}

	@Test
	fun `unknown jobs are not found`() {
		shouldThrow<NotFoundException> { jobs.get(UUID.randomUUID()) }
		shouldThrow<NotFoundException> { jobs.zip(UUID.randomUUID()) }
	}

	@Test
	fun `a failed export leaves no partial zip, and a new backup deletes the previous zip`() {
		val first = jobs.start()
		awaitFinished(first.id) shouldBe BackupJobState.COMPLETED
		val firstZip = jobs.zip(first.id).second
		val workDir: Path = firstZip.parent

		module.fail = true
		val failed = jobs.start()
		awaitFinished(failed.id) shouldBe BackupJobState.FAILED

		Files.exists(firstZip) shouldBe false
		Files.exists(workDir.resolve("${failed.id}.zip")) shouldBe false
		jobs.get(failed.id).finishedAt shouldBe clock.instant()
		jobs.get(failed.id).fileName shouldBe null
		shouldThrow<ConflictException> { jobs.zip(failed.id) }
	}

	@Test
	fun `a contributor throwing an Error fails the job and does not block the next backup`() {
		module.unimplemented = true
		val failed = jobs.start()
		awaitFinished(failed.id) shouldBe BackupJobState.FAILED
		jobs.get(failed.id).finishedAt shouldBe clock.instant()
		shouldThrow<ConflictException> { jobs.zip(failed.id) }

		module.unimplemented = false
		val next = jobs.start()
		awaitFinished(next.id) shouldBe BackupJobState.COMPLETED
	}

	@Test
	fun `a fatal VirtualMachineError still ends the job as FAILED, announces it, and the next backup runs`() {
		module.fatal = true
		val failed = jobs.start()
		awaitFinished(failed.id) shouldBe BackupJobState.FAILED
		jobs.get(failed.id).finishedAt shouldBe clock.instant()
		jobs.get(failed.id).fileName shouldBe null
		// CREATED, the progress step that was reached, and the FAILED update
		awaitEvents(3)
		events.last().op shouldBe ChangeOp.UPDATED
		events.last().id shouldBe failed.id
		shouldThrow<ConflictException> { jobs.zip(failed.id) }

		// The error is rethrown on the backup thread; the executor must still run the next backup.
		module.fatal = false
		val next = jobs.start()
		awaitFinished(next.id) shouldBe BackupJobState.COMPLETED
		Files.exists(jobs.zip(next.id).second) shouldBe true
	}

	@Test
	fun `shutting down deletes the finished zips`() {
		val job = jobs.start()
		awaitFinished(job.id) shouldBe BackupJobState.COMPLETED
		val zip = jobs.zip(job.id).second

		jobs.destroy()

		Files.exists(zip) shouldBe false
	}
}
