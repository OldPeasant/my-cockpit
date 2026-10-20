package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.domain.BackupFormat
import ch.sonensei.cockpit.backup.domain.BackupJobService
import ch.sonensei.cockpit.backup.domain.BackupJobState
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.backup.domain.RestoreService
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.backupcontrib.UserBackupFixture
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * Restore over the API (SPEC.md §10.1): upload → validation report without changing data → confirm → pre-restore
 * backup and full replace; invalid backups and failing imports change nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtureBackupConfiguration::class, FailingImportBackupConfiguration::class)
@WithCockpitUser
@ExtendWith(OutputCaptureExtension::class)
class RestoreWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val backups: BackupService,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactions: TransactionTemplate,
	@Autowired private val users: UserAccountRepository,
	@Autowired private val testFixtures: TestFixtureBackupContributor,
	@Autowired private val failing: FailingImportBackupContributor,
	@Autowired private val jobs: BackupJobService,
	@Autowired private val clock: Clock,
	@Autowired private val restores: RestoreService,
) {
	private val mapper get() = backups.canonicalJson

	@BeforeEach
	fun setUp() {
		RestoreTestDatabases.wipe(jdbc, dataDir)
		transactions.executeWithoutResult {
			TestFixtureBackupFixture(jdbc, listOf(testFixtures)).seed()
			UserBackupFixture(users).seed()
		}
	}

	@AfterEach
	fun tearDown() {
		failing.fail = false
		failing.failExport = false
		failing.failWarnings = false
		failing.exportGate?.countDown()
		failing.exportGate = null
	}

	private fun export(): ByteArray = ByteArrayOutputStream().also { backups.export(it) }.toByteArray()

	private fun titles(): List<String> =
		jdbc.queryForList("select title from test_persistence_fixture order by title", String::class.java).filterNotNull()

	/** Every file of the data directory but `pre-restore/`, with its content. */
	private fun dataFiles(): Map<String, String> =
		Files.walk(dataDir).use { paths ->
			paths
				.filter { Files.isRegularFile(it) }
				.toList()
				.map { dataDir.relativize(it).joinToString("/") }
				.filterNot { it.startsWith("${RestoreService.PRE_RESTORE_DIR}/") }
				.associateWith { Zips.sha256(dataDir.resolve(it).readBytes()) }
		}

	/** Changes the current data after the export: one row more, its blob too. */
	private fun changeData() {
		val id = UUID.randomUUID()
		jdbc.update(
			"insert into test_persistence_fixture (id, created_at, updated_at, version, title) values (?, now(), now(), 0, 'Changed later')",
			id,
		)
		Files.write(testFixtures.blob(id), "later".toByteArray())
	}

	private fun upload(zip: ByteArray): ResultActionsDsl =
		mockMvc.post(BASE) {
			with(csrf())
			contentType = ZIP
			content = zip
		}

	private fun report(zip: ByteArray): JsonNode =
		mapper.readTree(
			upload(zip)
				.andExpect { status { isOk() } }
				.andReturn()
				.response.contentAsString,
		)

	private fun confirm(id: String): ResultActionsDsl = mockMvc.post("$BASE/$id/confirm") { with(csrf()) }

	@Test
	fun `upload returns the validation report and changes nothing`() {
		val zip = export()
		changeData()
		val before = titles()
		val files = dataFiles()

		upload(zip).andExpect {
			status { isOk() }
			jsonPath("$.valid") { value(true) }
			jsonPath("$.id") { isString() }
			jsonPath("$.formatVersion") { value(BackupFormat.VERSION) }
			jsonPath("$.appVersion") { value(backups.appVersion) }
			jsonPath("$.createdAt") { isString() }
			jsonPath("$.counts.test_fixtures") { value(3) }
			jsonPath("$.counts.user") { value(1) }
			jsonPath("$.problems") { isEmpty() }
			jsonPath("$.warnings") { value(hasItem(containsString("sign in with one of the 2 unused recovery codes"))) }
		}

		titles() shouldBe before
		dataFiles() shouldBe files
	}

	@Test
	fun `confirm saves the current data to pre-restore and replaces all data with the backup`() {
		val zip = export()
		val restoredTitles = titles()
		val restoredFiles = dataFiles()
		changeData()
		val id = report(zip)["id"].asString()

		val result =
			confirm(id)
				.andExpect {
					status { isOk() }
					jsonPath("$.counts.test_fixtures") { value(3) }
				}.andReturn()
				.response.contentAsString
		val preRestore = mapper.readTree(result)["preRestoreBackup"].asString()

		titles() shouldBe restoredTitles
		dataFiles() shouldBe restoredFiles
		preRestore shouldStartWith "${RestoreService.PRE_RESTORE_DIR}/cockpit-backup-"
		val saved = Zips.read(dataDir.resolve(preRestore).readBytes())
		String(saved.getValue("data/test_fixtures.jsonl")).contains("Changed later") shouldBe true
		dataDir.listDirectoryEntries().map { it.name } shouldContainExactlyInAnyOrder listOf("pre-restore", "test-fixtures")
		// The upload is used up.
		confirm(id).andExpect { status { isNotFound() } }
	}

	@Test
	fun `a corrupt zip is rejected with a report and changes nothing`() {
		val before = titles()
		val report = report("PK this is not a zip".toByteArray())

		report["valid"].asBoolean() shouldBe false
		report["id"].isNull shouldBe true
		report["problems"].values().map { it.asString() } shouldContainExactly listOf("Not a zip file")
		titles() shouldBe before
	}

	@Test
	fun `a wrong format version is rejected with a report and changes nothing`() {
		val zip =
			Zips.edit(export(), mapper, updateManifest = false) { entries ->
				val manifest = mapper.readTree(entries.getValue(BackupFormat.MANIFEST)) as tools.jackson.databind.node.ObjectNode
				manifest.put("formatVersion", 2)
				entries[BackupFormat.MANIFEST] = mapper.writeValueAsBytes(manifest)
			}
		changeData()
		val before = titles()

		val report = report(zip)

		report["valid"].asBoolean() shouldBe false
		report["id"].isNull shouldBe true
		report["problems"].values().map { it.asString() } shouldContainExactly listOf("Unsupported format version 2 (supported: 1)")
		titles() shouldBe before
	}

	@Test
	fun `a sha256 mismatch is rejected with a report and changes nothing`() {
		val zip =
			Zips.edit(export(), mapper, updateManifest = false) { entries ->
				val name = entries.keys.first { it.startsWith("files/") }
				entries[name] = "tampered".toByteArray()
			}
		val before = titles()
		val files = dataFiles()

		val report = report(zip)

		report["valid"].asBoolean() shouldBe false
		report["formatVersion"].asInt() shouldBe BackupFormat.VERSION
		report["counts"]["test_fixtures"].asLong() shouldBe 3
		report["problems"].single().asString() shouldBe
			"${Zips.read(zip).keys.first { it.startsWith("files/") }} does not match its sha256 in the manifest"
		titles() shouldBe before
		dataFiles() shouldBe files
	}

	@Test
	fun `confirm of an unknown upload is 404`() {
		confirm(UUID.randomUUID().toString()).andExpect { status { isNotFound() } }
	}

	@Test
	fun `a failing import rolls back the database and the data directory`() {
		val zip = export()
		changeData()
		val before = titles()
		val files = dataFiles()
		val id = report(zip)["id"].asString()
		failing.fail = true

		confirm(id).andExpect {
			status { isUnprocessableContent() }
			content { contentType("application/problem+json") }
			jsonPath("$.detail") { value("The backup could not be restored; nothing was changed") }
			jsonPath("$.problems") { isEmpty() }
		}

		titles() shouldBe before
		dataFiles() shouldBe files
		dataDir
			.listDirectoryEntries()
			.map { it.name }
			.filter { it.startsWith(".restore-") }
			.shouldBeEmpty()
	}

	@Test
	fun `data that does not parse fails the confirm with its file and line, and changes nothing`() {
		val zip =
			Zips.edit(export(), mapper, updateManifest = true) { entries ->
				entries["data/test_fixtures.jsonl"] = "null\n".toByteArray()
			}
		changeData()
		val before = titles()
		val id = report(zip)["id"].asString()

		confirm(id).andExpect {
			status { isUnprocessableContent() }
			jsonPath("$.problems[0]") { value("data/test_fixtures.jsonl line 1 is not valid") }
		}

		titles() shouldBe before
	}

	private fun awaitFinished(job: UUID) {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
		while (jobs.get(job).state == BackupJobState.RUNNING) {
			check(System.nanoTime() < deadline) { "Backup $job did not finish" }
			Thread.sleep(10)
		}
	}

	@Test
	fun `confirm while a backup runs is 409, keeps the upload and changes nothing`() {
		val zip = export()
		changeData()
		val before = titles()
		val id = report(zip)["id"].asString()
		val gate = CountDownLatch(1).also { failing.exportGate = it }
		val running = jobs.start()

		confirm(id).andExpect {
			status { isConflict() }
			content { contentType("application/problem+json") }
		}
		titles() shouldBe before
		Files.exists(dataDir.resolve(RestoreService.PRE_RESTORE_DIR)) shouldBe false

		failing.exportGate = null
		gate.countDown()
		awaitFinished(running.id)
		jobs.get(running.id).state shouldBe BackupJobState.COMPLETED
		withClue("the upload was kept, so the restore can be confirmed once the backup is finished") {
			confirm(id).andExpect { status { isOk() } }
		}
		titles() shouldNotBe before
	}

	@Test
	fun `a new upload discards the previous one`() {
		val zip = export()
		val first = report(zip)["id"].asString()
		val second = report(zip)["id"].asString()

		second shouldNotBe first
		confirm(first).andExpect { status { isNotFound() } }
		confirm(second).andExpect { status { isOk() } }
	}

	@Test
	fun `an invalid upload discards the previous valid one too`() {
		val first = report(export())["id"].asString()

		report("not a zip".toByteArray())["valid"].asBoolean() shouldBe false

		confirm(first).andExpect { status { isNotFound() } }
	}

	@Test
	fun `an upload sent as application octet-stream is accepted`() {
		mockMvc
			.post(BASE) {
				with(csrf())
				contentType = MediaType.APPLICATION_OCTET_STREAM
				content = export()
			}.andExpect {
				status { isOk() }
				jsonPath("$.valid") { value(true) }
				jsonPath("$.id") { isString() }
			}
	}

	@Test
	fun `an upload of another content type is 415`() {
		mockMvc
			.post(BASE) {
				with(csrf())
				contentType = MediaType.TEXT_PLAIN
				content = export()
			}.andExpect { status { isUnsupportedMediaType() } }
	}

	@Test
	fun `a backup of another app version is valid with a warning`() {
		val zip =
			Zips.edit(export(), mapper, updateManifest = false) { entries ->
				val manifest = mapper.readTree(entries.getValue(BackupFormat.MANIFEST)) as ObjectNode
				manifest.put("appVersion", "0.0.1-fictitious")
				entries[BackupFormat.MANIFEST] = mapper.writeValueAsBytes(manifest)
			}

		val report = report(zip)

		report["valid"].asBoolean() shouldBe true
		report["appVersion"].asString() shouldBe "0.0.1-fictitious"
		report["warnings"].values().map { it.asString() } shouldContain
			"Created by My Cockpit 0.0.1-fictitious; this is version ${backups.appVersion}"
	}

	@Test
	fun `a backup of the same app version has no version warning`() {
		report(export())["warnings"].values().map { it.asString() }.none { it.startsWith("Created by My Cockpit") } shouldBe true
	}

	@Test
	fun `a backup without owner account warns that the setup starts again`() {
		jdbc.update("delete from app_user")

		val report = report(export())

		report["valid"].asBoolean() shouldBe true
		report["warnings"].values().map { it.asString() } shouldContain
			"The backup has no owner account: the setup starts again after the restore"
	}

	@Test
	fun `data a contributor cannot read for its warnings makes the upload invalid, with the file named and no id`() {
		val zip =
			Zips.edit(export(), mapper, updateManifest = true) { entries ->
				entries["data/user.json"] = "{\"username\":\"jdoe\"".toByteArray()
			}
		val before = titles()

		val report = report(zip)

		report["valid"].asBoolean() shouldBe false
		report["id"].isNull shouldBe true
		report["formatVersion"].asInt() shouldBe BackupFormat.VERSION
		report["problems"].values().map { it.asString() } shouldContainExactly listOf("data/user.json is not valid")
		report.toString() shouldNotContain "jdoe"
		titles() shouldBe before
	}

	@Test
	fun `a null recovery code makes the upload invalid with the file named`() {
		val zip =
			Zips.edit(export(), mapper, updateManifest = true) { entries ->
				val user = mapper.readTree(entries.getValue("data/user.json")) as ObjectNode
				user.putArray("recoveryCodes").addNull()
				entries["data/user.json"] = mapper.writeValueAsBytes(user)
			}
		val before = titles()

		val report = report(zip)

		report["valid"].asBoolean() shouldBe false
		report["id"].isNull shouldBe true
		report["problems"].values().map { it.asString() } shouldContainExactly listOf("data/user.json is not valid")
		report.toString() shouldNotContain "jdoe"
		titles() shouldBe before
	}

	@Test
	fun `a contributor failing on its warnings makes the upload invalid with a content-free problem`(output: CapturedOutput) {
		val zip = export()
		val before = titles()
		failing.failWarnings = true

		val report = report(zip)

		report["valid"].asBoolean() shouldBe false
		report["id"].isNull shouldBe true
		report["problems"].values().map { it.asString() } shouldContainExactly listOf("The backup's data is not valid")
		report.toString() shouldNotContain "Secret title"
		output.all shouldContain "Reading the backup's data for its restore warnings failed: java.lang.IllegalStateException"
		output.all shouldNotContain "Secret title"
		titles() shouldBe before
	}

	@Test
	fun `a pre-restore backup never overwrites an earlier one of the same minute`() {
		val zip = export()
		val preRestoreDir = Files.createDirectories(dataDir.resolve(RestoreService.PRE_RESTORE_DIR))
		// Both minutes the confirm may run in; the placeholders stand for earlier restores.
		val now = clock.instant()
		val earlier =
			listOf(now, now.plusSeconds(60)).map { preRestoreDir.resolve(BackupFormat.fileName(it)) }.distinct().onEach {
				Files.writeString(it, "earlier pre-restore backup")
			}
		val id = report(zip)["id"].asString()

		val result =
			confirm(id)
				.andExpect { status { isOk() } }
				.andReturn()
				.response.contentAsString
		val preRestore = mapper.readTree(result)["preRestoreBackup"].asString()

		preRestore shouldEndWith "-2.zip"
		earlier.forEach { Files.readString(it) shouldBe "earlier pre-restore backup" }
		Zips.read(dataDir.resolve(preRestore).readBytes()).keys shouldContain BackupFormat.MANIFEST
	}

	@Test
	fun `a failing pre-restore backup changes nothing, leaves no partial zip and uses up the upload`() {
		val zip = export()
		changeData()
		val before = titles()
		val files = dataFiles()
		val id = report(zip)["id"].asString()
		failing.failExport = true

		confirm(id).andExpect {
			status { isInternalServerError() }
			content { contentType("application/problem+json") }
			content { string(not(containsString("Secret title"))) }
			jsonPath("$.detail") { value("The current data could not be backed up; nothing was changed") }
		}

		titles() shouldBe before
		dataFiles() shouldBe files
		dataDir.resolve(RestoreService.PRE_RESTORE_DIR).listDirectoryEntries().shouldBeEmpty()
		dataDir
			.listDirectoryEntries()
			.map { it.name }
			.filter { it.startsWith(".restore-") }
			.shouldBeEmpty()
		failing.failExport = false
		confirm(id).andExpect { status { isNotFound() } }
	}

	@Test
	fun `a failing pre-restore backup logs no user content from the contributor's error`(output: CapturedOutput) {
		val id = report(export())["id"].asString()
		failing.failExport = true

		confirm(id).andExpect { status { isInternalServerError() } }

		// Like a failing import (RestoreService.replace) and a failing backup job: the class name only at WARN and above.
		output.all shouldNotContain "Secret title"
	}

	@Test
	@WithAnonymousUser
	fun `anonymous upload and confirm are 401, keep the pending upload and change nothing`() {
		val zip = export()
		changeData()
		val before = titles()
		val id = restores.upload(zip.inputStream()).id!!

		upload(zip).andExpect { status { isUnauthorized() } }
		confirm(id.toString()).andExpect { status { isUnauthorized() } }

		titles() shouldBe before
		Files.exists(dataDir.resolve(RestoreService.PRE_RESTORE_DIR)) shouldBe false
		withClue("the anonymous requests neither replaced nor discarded the pending upload") {
			restores.confirm(id).counts["test_fixtures"] shouldBe 3
		}
	}

	@Test
	fun `upload and confirm without CSRF token are 403 and change nothing`() {
		val zip = export()
		changeData()
		val before = titles()
		val id = report(zip)["id"].asString()

		mockMvc
			.post(BASE) {
				contentType = ZIP
				content = zip
			}.andExpect { status { isForbidden() } }
		mockMvc.post("$BASE/$id/confirm").andExpect { status { isForbidden() } }

		titles() shouldBe before
		Files.exists(dataDir.resolve(RestoreService.PRE_RESTORE_DIR)) shouldBe false
		withClue("the rejected requests did not discard the pending upload") {
			confirm(id).andExpect { status { isOk() } }
		}
	}

	@Test
	fun `confirm also empties tables that are not in the backup, such as sessions`() {
		val userId = jdbc.queryForObject("select id from app_user", UUID::class.java)
		jdbc.update(
			"insert into auth_session (id, created_at, updated_at, version, user_id, token_hash, remember_device, last_seen_at, expires_at) " +
				"values (?, now(), now(), 0, ?, 'fictitious-token-hash', false, now(), now() + interval '1 hour')",
			UUID.randomUUID(),
			userId,
		)
		val id = report(export())["id"].asString()

		confirm(id).andExpect { status { isOk() } }

		jdbc.queryForObject("select count(*) from auth_session", Long::class.java) shouldBe 0L
		jdbc.queryForList("select username from app_user", String::class.java) shouldContainExactly listOf("jdoe")
	}

	@Test
	fun `a failing import uses up the upload`() {
		val id = report(export())["id"].asString()
		failing.fail = true
		confirm(id).andExpect { status { isUnprocessableContent() } }

		failing.fail = false
		confirm(id).andExpect { status { isNotFound() } }
	}

	@Test
	fun `an upload whose stream breaks off fails, keeps nothing pending and changes nothing`() {
		val zip = export()
		changeData()
		val before = titles()
		val previous = restores.upload(zip.inputStream()).id!!
		val broken =
			object : java.io.InputStream() {
				private var left = 100

				override fun read(): Int = if (left-- > 0) 0x50 else throw java.io.IOException("Connection reset (fictitious)")
			}

		shouldThrow<java.io.IOException> { restores.upload(broken) }

		titles() shouldBe before
		withClue("the broken upload discarded the previous one and left none of its own") {
			confirm(previous.toString()).andExpect { status { isNotFound() } }
		}
		restores.upload(zip.inputStream()).valid shouldBe true
	}

	@Test
	fun `an empty upload is rejected as not a zip, without id`() {
		val report = report(ByteArray(0))

		report["valid"].asBoolean() shouldBe false
		report["id"].isNull shouldBe true
		report["formatVersion"].isNull shouldBe true
		report["counts"].size() shouldBe 0
		report["problems"].values().map { it.asString() } shouldContainExactly listOf("Not a zip file")
	}

	@Test
	fun `confirm with an id that is not a UUID is 400`() {
		confirm("not-a-uuid").andExpect { status { isBadRequest() } }
	}

	companion object {
		private const val BASE = "/api/v1/restore"
		private val ZIP = org.springframework.http.MediaType("application", "zip")
		private val dataDir: Path = Files.createTempDirectory("cockpit-restore-data")

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) = RestoreTestDatabases.register(registry, "backup_restore", dataDir)
	}
}
