package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupContributorRegistry
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupJobService
import ch.sonensei.cockpit.backup.domain.BackupJobState
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.backup.domain.RestoreService
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The temp work dirs of [BackupJobService] (`cockpit-backups*`) and [RestoreService] (`cockpit-restores*`), without
 * Spring: created only when used, and deleted with their contents when the bean is destroyed.
 */
class BackupWorkDirCleanupTests {
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

		@Volatile var fail = false

		override fun export(writer: BackupDataWriter) {
			check(!fail) { "export failed" }
			writer.jsonLines("tasks", sequenceOf(mapOf("title" to "Water the fictitious plants")))
		}

		override fun import(reader: BackupDataReader) = Unit
	}

	private val clock = Clock.fixed(Instant.parse("2026-10-20T15:30:12Z"), ZoneOffset.UTC)
	private val module = Module()
	private val publisher = ChangeEventPublisher(ApplicationEventPublisher { }, clock)
	private val backups = BackupService(BackupContributorRegistry(listOf(module)), JsonMapper.builder().build(), clock, NoTransactions)
	private val jobs = BackupJobService(backups, publisher, clock)

	@TempDir
	lateinit var dataDir: Path

	private val restores by lazy { RestoreService(backups, jobs, JdbcTemplate(), clock, dataDir) }

	private val tmp: Path = Paths.get(System.getProperty("java.io.tmpdir"))

	/** The JVM temp dir's folders named [prefix]`*`. */
	private fun tempDirs(prefix: String): Set<Path> = tmp.listDirectoryEntries("$prefix*").filter { it.isDirectory() }.toSet()

	@AfterEach
	fun tearDown() {
		restores.destroy()
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

	private fun validBackup(): ByteArray = ByteArrayOutputStream().also { backups.export(it) }.toByteArray()

	@Test
	fun `a backup service that never made a backup creates no work dir, and destroying it creates none either`() {
		val before = tempDirs(BACKUPS)

		val unused = BackupJobService(backups, publisher, clock)
		unused.destroy()

		tempDirs(BACKUPS) shouldBe before
	}

	@Test
	fun `destroying the backup service deletes its work dir with the zip in it`() {
		val job = jobs.start()
		awaitFinished(job.id) shouldBe BackupJobState.COMPLETED
		val workDir = jobs.zip(job.id).second.parent
		workDir.name.startsWith(BACKUPS) shouldBe true
		workDir.isDirectory() shouldBe true

		jobs.destroy()

		Files.exists(workDir) shouldBe false
	}

	@Test
	fun `destroying the backup service deletes its work dir also when the only backup failed`() {
		val before = tempDirs(BACKUPS)
		module.fail = true
		val failed = jobs.start()
		awaitFinished(failed.id) shouldBe BackupJobState.FAILED
		val created = tempDirs(BACKUPS) - before
		created shouldHaveSize 1

		jobs.destroy()

		created.filter { Files.exists(it) }.shouldBeEmpty()
	}

	@Test
	fun `backups of one service share one work dir until it is destroyed, and destroying twice is harmless`() {
		val before = tempDirs(BACKUPS)
		val first = jobs.start()
		awaitFinished(first.id) shouldBe BackupJobState.COMPLETED
		val firstDir = jobs.zip(first.id).second.parent
		val second = jobs.start()
		awaitFinished(second.id) shouldBe BackupJobState.COMPLETED
		val secondZip = jobs.zip(second.id).second

		secondZip.parent shouldBe firstDir
		Files.exists(secondZip) shouldBe true
		tempDirs(BACKUPS) - before shouldBe setOf(firstDir)

		jobs.destroy()
		jobs.destroy()

		Files.exists(firstDir) shouldBe false
	}

	@Test
	fun `destroying the backup service leaves other cockpit-backups dirs alone`() {
		val other = Files.createTempDirectory(BACKUPS)
		try {
			val job = jobs.start()
			awaitFinished(job.id) shouldBe BackupJobState.COMPLETED

			jobs.destroy()

			Files.exists(other) shouldBe true
		} finally {
			other.toFile().deleteRecursively()
		}
	}

	@Test
	fun `a restore service that never got an upload creates no work dir, and destroying it creates none either`() {
		val before = tempDirs(RESTORES)

		restores.destroy()

		tempDirs(RESTORES) shouldBe before
	}

	@Test
	fun `destroying the restore service deletes its work dir with the pending upload in it`() {
		val before = tempDirs(RESTORES)
		val report = restores.upload(ByteArrayInputStream(validBackup()))
		report.valid shouldBe true
		val created = tempDirs(RESTORES) - before
		created shouldHaveSize 1
		val workDir = created.single()
		val pending = workDir.resolve("${report.id}.zip")
		Files.exists(pending) shouldBe true

		restores.destroy()

		Files.exists(pending) shouldBe false
		Files.exists(workDir) shouldBe false
	}

	@Test
	fun `destroying the restore service deletes its work dir also after an invalid upload`() {
		val before = tempDirs(RESTORES)
		val report = restores.upload(ByteArrayInputStream("not a zip".toByteArray()))
		report.valid shouldBe false
		val created = tempDirs(RESTORES) - before
		created shouldHaveSize 1
		// The invalid upload itself is not kept.
		created.single().listDirectoryEntries().shouldBeEmpty()

		restores.destroy()

		created.filter { Files.exists(it) }.shouldBeEmpty()
	}

	@Test
	fun `uploads of one restore service share one work dir, a new upload replaces the pending one`() {
		val before = tempDirs(RESTORES)
		val first = restores.upload(ByteArrayInputStream(validBackup()))
		val second = restores.upload(ByteArrayInputStream(validBackup()))
		first.valid shouldBe true
		second.valid shouldBe true

		val created = tempDirs(RESTORES) - before
		created shouldHaveSize 1
		created.single().listDirectoryEntries().map { it.name } shouldBe listOf("${second.id}.zip")

		restores.destroy()
		restores.destroy()

		created.filter { Files.exists(it) }.shouldBeEmpty()
	}

	@Test
	fun `destroying the restore service leaves other cockpit-restores dirs alone`() {
		val other = Files.createTempDirectory(RESTORES)
		try {
			restores.upload(ByteArrayInputStream(validBackup())).valid shouldBe true

			restores.destroy()

			Files.exists(other) shouldBe true
		} finally {
			other.toFile().deleteRecursively()
		}
	}

	private companion object {
		const val BACKUPS = "cockpit-backups"
		const val RESTORES = "cockpit-restores"
	}
}
