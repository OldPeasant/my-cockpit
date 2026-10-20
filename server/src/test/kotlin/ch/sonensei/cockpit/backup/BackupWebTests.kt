package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupFormat
import ch.sonensei.cockpit.backup.domain.BackupJobService
import ch.sonensei.cockpit.backup.domain.BackupReadableWriter
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.event.TransactionalEventListener
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** A contributor whose export can be held ([gate]) or made to fail ([fail]). */
class ControllableBackupContributor : BackupContributor {
	override val id = "controllable"

	@Volatile var gate: CountDownLatch? = null

	@Volatile var fail = false

	override fun export(writer: BackupDataWriter) {
		gate?.await(10, TimeUnit.SECONDS)
		check(!fail) { "Export failed for 'Secret title'" }
		writer.jsonLines("controllable_items", sequenceOf(mapOf("title" to "Call plumber")))
		writer.file("Documents/plan.txt", "plan".byteInputStream())
	}

	override fun readable(writer: BackupReadableWriter) = writer.text("controllable.md", "- Call plumber\n")

	override fun import(reader: BackupDataReader) = Unit
}

/** Records backup change events the way SSE receives them: after the publishing transaction commits. */
class BackupEventRecorder {
	val events = CopyOnWriteArrayList<ChangeEvent>()

	@TransactionalEventListener(fallbackExecution = true)
	fun onChange(event: ChangeEvent) {
		if (event.type == BackupJobService.EVENT_TYPE) events += event
	}
}

@TestConfiguration(proxyBeanMethods = false)
class ControllableBackupConfiguration {
	@Bean
	fun controllableBackupContributor() = ControllableBackupContributor()

	@Bean
	fun backupEventRecorder() = BackupEventRecorder()
}

@SpringBootTest
@AutoConfigureMockMvc
@Import(ControllableBackupConfiguration::class)
@WithCockpitUser
class BackupWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val contributor: ControllableBackupContributor,
	@Autowired private val recorder: BackupEventRecorder,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val jobs: BackupJobService,
	@Autowired private val backups: BackupService,
) : PostgresTestBase() {
	@AfterEach
	fun tearDown() {
		contributor.gate?.countDown()
		contributor.gate = null
		contributor.fail = false
		// Leave no backup running for the next test.
		lastId?.let { awaitFinished(it) }
	}

	private var lastId: UUID? = null

	private fun start(): UUID {
		val body =
			mockMvc
				.post(BASE) { with(csrf()) }
				.andExpect {
					status { isAccepted() }
					header { string("Location", org.hamcrest.Matchers.containsString("$BASE/")) }
				}.andReturn()
		return body.let { result ->
			UUID.fromString(jsonMapper.readTree(result.response.contentAsString)["id"].asString()).also { lastId = it }
		}
	}

	private fun job(id: UUID) =
		jsonMapper.readTree(
			mockMvc
				.get("$BASE/$id")
				.andReturn()
				.response.contentAsString,
		)

	private fun awaitFinished(id: UUID): String {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
		while (System.nanoTime() < deadline) {
			val state = job(id)["state"].asString()
			if (state != "RUNNING") return state
			Thread.sleep(20)
		}
		error("Backup $id did not finish")
	}

	@Test
	fun `POST starts a backup that completes and downloads as a zip`() {
		val id = start()

		awaitFinished(id) shouldBe "COMPLETED"
		mockMvc.get("$BASE/$id").andExpect {
			status { isOk() }
			jsonPath("$.id") { value(id.toString()) }
			jsonPath("$.state") { value("COMPLETED") }
			jsonPath("$.stepsDone") { value(3) }
			jsonPath("$.stepsTotal") { value(3) }
			jsonPath("$.fileName") { value(org.hamcrest.Matchers.matchesRegex("cockpit-backup-\\d{4}-\\d{2}-\\d{2}T\\d{4}Z\\.zip")) }
			jsonPath("$.finishedAt") { exists() }
		}

		val download =
			mockMvc
				.get("$BASE/$id/download")
				.andExpect {
					status { isOk() }
					content { contentType("application/zip") }
					header { string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment; filename=\"cockpit-backup-")) }
				}.andReturn()
				.response
		val names = mutableListOf<String>()
		ZipInputStream(ByteArrayInputStream(download.contentAsByteArray)).use { zip ->
			generateSequence { zip.nextEntry }.forEach { names += it.name }
		}
		names shouldContainAll
			listOf(
				"README.md",
				"manifest.json",
				"data/controllable_items.jsonl",
				"files/Documents/plan.txt",
				"readable/controllable.md",
			)
		download.getHeader("Content-Length")?.toLong() shouldBe download.contentAsByteArray.size.toLong()
	}

	@Test
	fun `progress is announced as BACKUP change events while the export runs`() {
		contributor.gate = CountDownLatch(1)
		val id = start()

		// The export is held inside its snapshot transaction: its progress must already have been delivered.
		awaitEvent(id, ChangeOp.UPDATED)
		job(id)["step"].asString() shouldBe "controllable: data"
		contributor.gate!!.countDown()
		awaitFinished(id) shouldBe "COMPLETED"

		val ops = recorder.events.filter { it.id == id }.map { it.op }
		ops.first() shouldBe ChangeOp.CREATED
		ops shouldContain ChangeOp.UPDATED
	}

	private fun awaitEvent(
		id: UUID,
		op: ChangeOp,
	) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
		while (recorder.events.none { it.id == id && it.op == op }) {
			check(System.nanoTime() < deadline) { "No $op event for backup $id" }
			Thread.sleep(20)
		}
	}

	@Test
	fun `a running backup is not downloadable and blocks a second one`() {
		contributor.gate = CountDownLatch(1)
		val id = start()

		job(id)["state"].asString() shouldBe "RUNNING"
		mockMvc.get("$BASE/$id/download").andExpect {
			status { isConflict() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		mockMvc.post(BASE) { with(csrf()) }.andExpect { status { isConflict() } }

		contributor.gate!!.countDown()
		awaitFinished(id) shouldBe "COMPLETED"
	}

	@Test
	fun `a failed backup reports FAILED without details and cannot be downloaded`() {
		contributor.fail = true
		val id = start()

		awaitFinished(id) shouldBe "FAILED"
		val body =
			mockMvc
				.get("$BASE/$id")
				.andReturn()
				.response.contentAsString
		body.contains("Secret title") shouldBe false
		mockMvc.get("$BASE/$id/download").andExpect { status { isConflict() } }
	}

	@Test
	fun `a new backup discards the previous one`() {
		val first = start()
		awaitFinished(first)
		val second = start()
		awaitFinished(second)

		mockMvc.get("$BASE/$first").andExpect { status { isNotFound() } }
		mockMvc.get("$BASE/$first/download").andExpect { status { isNotFound() } }
		mockMvc.get("$BASE/$second/download").andExpect { status { isOk() } }
	}

	@Test
	fun `the downloaded zip has a manifest with counts and the sha256 of every file, named like the job says`() {
		val id = start()
		awaitFinished(id) shouldBe "COMPLETED"
		val job = job(id)

		val download =
			mockMvc
				.get("$BASE/$id/download")
				.andReturn()
				.response
		download.getHeader("Content-Disposition") shouldBe "attachment; filename=\"${job["fileName"].asString()}\""
		job["size"].asLong() shouldBe download.contentAsByteArray.size.toLong()
		val entries = mutableMapOf<String, ByteArray>()
		ZipInputStream(ByteArrayInputStream(download.contentAsByteArray)).use { zip ->
			generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readAllBytes() }
		}
		val manifest = jsonMapper.readTree(entries.getValue("manifest.json"))
		manifest["formatVersion"].asInt() shouldBe 1
		manifest["appVersion"].asString().isNotBlank() shouldBe true
		manifest["counts"]["controllable_items"].asLong() shouldBe 1
		val listed = manifest["files"].associate { it["path"].asString() to it["sha256"].asString() }
		listed.keys shouldBe entries.keys - "manifest.json"
		listed.forEach { (path, sha) -> sha shouldBe sha256(entries.getValue(path)) }
		BackupFormat.fileName(Instant.parse(manifest["createdAt"].asString())) shouldBe job["fileName"].asString()
	}

	@Test
	fun `a new backup deletes the previous zip from disk`() {
		val first = start()
		awaitFinished(first) shouldBe "COMPLETED"
		val zip = jobs.zip(first).second
		Files.exists(zip) shouldBe true

		val second = start()
		awaitFinished(second)

		Files.exists(zip) shouldBe false
	}

	@Test
	fun `a failed backup leaves no zip behind and the next backup can start`() {
		contributor.fail = true
		val failed = start()
		awaitFinished(failed) shouldBe "FAILED"
		job(failed)["finishedAt"].isNull shouldBe false
		(job(failed)["fileName"]?.isNull ?: true) shouldBe true
		contributor.fail = false

		val next = start()
		awaitFinished(next) shouldBe "COMPLETED"
		mockMvc.get("$BASE/$failed").andExpect { status { isNotFound() } }
	}

	private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

	data class CanonicalSample(
		val zeta: String,
		val due: LocalDate,
		val at: Instant,
		val alpha: Map<String, Int>,
		val deletedAt: Instant?,
	)

	@Test
	fun `canonical JSON of the application's mapper has sorted keys and lossless ISO-8601 dates`() {
		val sample =
			CanonicalSample(
				zeta = "Call plumber",
				due = LocalDate.parse("2026-11-03"),
				at = Instant.parse("2026-03-01T08:00:00.123456Z"),
				alpha = linkedMapOf("b" to 2, "a" to 1),
				deletedAt = null,
			)

		val json = backups.canonicalJson.writeValueAsString(sample)

		json shouldBe
			"{\"alpha\":{\"a\":1,\"b\":2},\"at\":\"2026-03-01T08:00:00.123456Z\",\"deletedAt\":null," +
			"\"due\":\"2026-11-03\",\"zeta\":\"Call plumber\"}"
		backups.canonicalJson.readValue(json, CanonicalSample::class.java) shouldBe sample
	}

	@Test
	fun `unknown and invalid ids give 404 and 400`() {
		mockMvc.get("$BASE/${UUID.randomUUID()}").andExpect {
			status { isNotFound() }
			content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
		}
		mockMvc.get("$BASE/not-a-uuid").andExpect { status { isBadRequest() } }
	}

	@Test
	@WithAnonymousUser
	fun `anonymous requests are rejected`() {
		mockMvc.post(BASE) { with(csrf()) }.andExpect { status { isUnauthorized() } }
		mockMvc.get("$BASE/${UUID.randomUUID()}/download").andExpect { status { isUnauthorized() } }
	}

	@Test
	fun `POST without CSRF token is rejected`() {
		mockMvc.post(BASE).andExpect { status { isForbidden() } }
	}

	companion object {
		private const val BASE = "/api/v1/backup"
	}
}
