package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.domain.BackupFormat
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.backup.domain.RestoreService
import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.auth.infra.UserAccountRepository
import ch.sonensei.cockpit.core.backupcontrib.UserBackupFixture
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
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
class RestoreWebTests(
	@Autowired private val mockMvc: MockMvc,
	@Autowired private val backups: BackupService,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactions: TransactionTemplate,
	@Autowired private val users: UserAccountRepository,
	@Autowired private val testFixtures: TestFixtureBackupContributor,
	@Autowired private val failing: FailingImportBackupContributor,
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

	companion object {
		private const val BASE = "/api/v1/restore"
		private val ZIP = org.springframework.http.MediaType("application", "zip")
		private val dataDir: Path = Files.createTempDirectory("cockpit-restore-data")

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) = RestoreTestDatabases.register(registry, "backup_restore", dataDir)
	}
}
