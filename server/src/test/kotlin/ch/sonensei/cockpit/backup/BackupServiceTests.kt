package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.api.RestoreCli
import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupContributorRegistry
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupFormat
import ch.sonensei.cockpit.backup.domain.BackupProgress
import ch.sonensei.cockpit.backup.domain.BackupReadableWriter
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.backup.domain.InvalidBackupException
import ch.sonensei.cockpit.backup.domain.jsonLines
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipFile
import kotlin.io.path.outputStream

/** [BackupService] without Spring: orchestration order, progress, README, manifest, transactions. */
class BackupServiceTests {
	@TempDir
	lateinit var dir: Path

	/** Records the transactions it is asked for and what happened to them. */
	private class RecordingTransactions : AbstractPlatformTransactionManager() {
		val log = mutableListOf<String>()
		var active = false
		var lastDefinition: TransactionDefinition? = null

		override fun doGetTransaction(): Any = Any()

		override fun doBegin(
			transaction: Any,
			definition: TransactionDefinition,
		) {
			lastDefinition = definition
			active = true
			log += "begin"
		}

		override fun doCommit(status: DefaultTransactionStatus) {
			active = false
			log += "commit"
		}

		override fun doRollback(status: DefaultTransactionStatus) {
			active = false
			log += "rollback"
		}
	}

	data class Item(
		val title: String,
	)

	private inner class Module(
		override val id: String,
		override val dependsOn: Set<String> = emptySet(),
		private val items: List<Item> = listOf(Item("Call plumber")),
	) : BackupContributor {
		var failExport = false
		var failImport = false
		val imported = mutableListOf<Item>()

		private val dataName = id.replace('-', '_') + "_items"

		override fun export(writer: BackupDataWriter) {
			calls += "$id: export (transaction ${if (transactions.active) "open" else "closed"})"
			check(!failExport) { "export of $id failed" }
			writer.jsonLines(dataName, items.asSequence())
		}

		override fun readable(writer: BackupReadableWriter) {
			calls += "$id: readable (transaction ${if (transactions.active) "open" else "closed"})"
			writer.text("$id.md", items.joinToString("\n") { "- ${it.title}" })
		}

		override fun import(reader: BackupDataReader) {
			calls += "$id: import (transaction ${if (transactions.active) "open" else "closed"})"
			reader.jsonLines<Item>(dataName) { imported += it }
			check(!failImport) { "import of $id failed" }
		}
	}

	private val calls = mutableListOf<String>()
	private val transactions = RecordingTransactions()
	private val clock = Clock.fixed(Instant.parse("2026-10-20T15:30:12.789Z"), ZoneOffset.UTC)
	private val jsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

	private fun service(vararg modules: BackupContributor) =
		BackupService(BackupContributorRegistry(modules.toList()), jsonMapper, clock, transactions)

	private fun entries(zip: Path): Map<String, String> =
		ZipFile(zip.toFile()).use { file ->
			file.entries().toList().associate { it.name to String(file.getInputStream(it).readAllBytes(), Charsets.UTF_8) }
		}

	@Test
	fun `exports data of every contributor in dependency order, then mirrors, in one read-only repeatable-read snapshot`() {
		val gtd = Module("gtd", setOf("core"))
		val core = Module("core")
		val progress = mutableListOf<BackupProgress>()

		service(gtd, core).export(ByteArrayOutputStream()) { progress += it }

		calls shouldContainExactly
			listOf(
				"core: export (transaction open)",
				"gtd: export (transaction open)",
				"core: readable (transaction open)",
				"gtd: readable (transaction open)",
			)
		transactions.log shouldContainExactly listOf("begin", "commit")
		transactions.lastDefinition!!.isReadOnly shouldBe true
		transactions.lastDefinition!!.isolationLevel shouldBe TransactionDefinition.ISOLATION_REPEATABLE_READ
		progress shouldContainExactly
			listOf(
				BackupProgress("core: data", 0, 5),
				BackupProgress("gtd: data", 1, 5),
				BackupProgress("core: readable", 2, 5),
				BackupProgress("gtd: readable", 3, 5),
				BackupProgress("manifest", 4, 5),
				BackupProgress("done", 5, 5),
			)
	}

	@Test
	fun `export steps are data and mirrors per contributor plus the manifest`() {
		service().exportSteps shouldBe 1
		service(Module("core"), Module("gtd")).exportSteps shouldBe 5
	}

	@Test
	fun `the manifest has the format version, the creation time in whole seconds and the counts`() {
		val zip = dir.resolve("backup.zip")
		val manifest =
			zip.outputStream().use {
				service(Module("core", items = listOf(Item("Call plumber"), Item("Buy milk"))), Module("gtd")).export(it)
			}

		manifest.formatVersion shouldBe BackupFormat.VERSION
		manifest.createdAt shouldBe Instant.parse("2026-10-20T15:30:12Z")
		manifest.counts shouldBe mapOf("core_items" to 2L, "gtd_items" to 1L)
		manifest.appVersion.isNotBlank() shouldBe true
		manifest.files.map { it.path } shouldContainExactly
			listOf("README.md", "data/core_items.jsonl", "data/gtd_items.jsonl", "readable/core.md", "readable/gtd.md")
		manifest.files.map { it.path } shouldNotContain BackupFormat.MANIFEST
		BackupFormat.fileName(manifest.createdAt) shouldBe "cockpit-backup-2026-10-20T1530Z.zip"

		val written = jsonMapper.readTree(entries(zip).getValue(BackupFormat.MANIFEST))
		written["createdAt"].asString() shouldBe "2026-10-20T15:30:12Z"
		written["counts"].toString() shouldBe "{\"core_items\":2,\"gtd_items\":1}"
	}

	@Test
	fun `the README explains the structure, the format version, how to restore, and lists the counts`() {
		val zip = dir.resolve("backup.zip")
		zip.outputStream().use { service(Module("core", items = listOf(Item("Call plumber"), Item("Buy milk")))).export(it) }

		val readme = entries(zip).getValue(BackupFormat.README)
		readme shouldContain "format version 1"
		readme shouldContain "Created 2026-10-20T15:30:12Z"
		listOf("`manifest.json`", "`data/`", "`files/`", "`readable/`", "## Restore", RestoreCli.OPTION).forEach { readme shouldContain it }
		withClue("how to give the passphrase to the restore CLI") { readme shouldContain "`${RestoreCli.PASSPHRASE_ENV}`" }
		readme shouldContain "## Record counts\n\n- core_items: 2\n"
		readme shouldNotContain "No data."
	}

	@Test
	fun `a backup without contributors is still valid and says it has no data`() {
		val zip = dir.resolve("empty.zip")
		val manifest = zip.outputStream().use { service().export(it) }

		manifest.counts shouldBe emptyMap()
		entries(zip).keys shouldBe setOf(BackupFormat.README, BackupFormat.MANIFEST)
		entries(zip).getValue(BackupFormat.README) shouldContain "No data."
		service().import(zip).formatVersion shouldBe 1
	}

	@Test
	fun `a contributor without mirrors writes data only`() {
		val dataOnly =
			object : BackupContributor {
				override val id = "settings"

				override fun export(writer: BackupDataWriter) = writer.json("settings", mapOf("theme" to "dark"))

				override fun import(reader: BackupDataReader) = Unit
			}
		val zip = dir.resolve("backup.zip")

		zip.outputStream().use { service(dataOnly).export(it) }

		entries(zip).keys shouldBe setOf(BackupFormat.README, BackupFormat.MANIFEST, "data/settings.json")
	}

	@Test
	fun `export leaves the caller's stream open`() {
		var closed = false
		val target =
			object : ByteArrayOutputStream() {
				override fun close() {
					closed = true
				}
			}

		service(Module("core")).export(target)

		closed shouldBe false
		target.size() shouldBe target.toByteArray().size
		(target.size() > 0) shouldBe true
	}

	@Test
	fun `a failing contributor fails the export and rolls back the snapshot`() {
		val core = Module("core").apply { failExport = true }

		shouldThrow<IllegalStateException> { service(core, Module("gtd", setOf("core"))).export(ByteArrayOutputStream()) }

		transactions.log shouldContainExactly listOf("begin", "rollback")
		calls shouldContainExactly listOf("core: export (transaction open)")
	}

	@Test
	fun `import reads every contributor in dependency order in one transaction`() {
		val zip = dir.resolve("backup.zip")
		zip.outputStream().use {
			service(Module("gtd", setOf("core"), listOf(Item("Plan holiday"))), Module("core", items = listOf(Item("Buy milk")))).export(it)
		}
		calls.clear()
		transactions.log.clear()
		val gtd = Module("gtd", setOf("core"))
		val core = Module("core")

		val manifest = service(gtd, core).import(zip)

		calls shouldContainExactly listOf("core: import (transaction open)", "gtd: import (transaction open)")
		transactions.log shouldContainExactly listOf("begin", "commit")
		transactions.lastDefinition!!.isReadOnly shouldBe false
		core.imported shouldContainExactly listOf(Item("Buy milk"))
		gtd.imported shouldContainExactly listOf(Item("Plan holiday"))
		manifest.counts shouldBe mapOf("core_items" to 1L, "gtd_items" to 1L)
	}

	@Test
	fun `a failing import rolls back the whole transaction`() {
		val zip = dir.resolve("backup.zip")
		zip.outputStream().use { service(Module("core"), Module("gtd")).export(it) }
		transactions.log.clear()

		shouldThrow<IllegalStateException> { service(Module("core"), Module("gtd").apply { failImport = true }).import(zip) }

		transactions.log shouldContainExactly listOf("begin", "rollback")
	}

	@Test
	fun `an invalid backup is rejected before any contributor or transaction runs`() {
		val zip = dir.resolve("broken.zip").also { Files.writeString(it, "not a zip") }

		shouldThrow<InvalidBackupException> { service(Module("core")).import(zip) }

		calls shouldBe emptyList()
		transactions.log shouldBe emptyList()
	}
}
