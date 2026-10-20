package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.domain.BackupContributorRegistry
import ch.sonensei.cockpit.backup.domain.BackupFormat
import ch.sonensei.cockpit.backup.domain.BackupService
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.annotation.Import
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.HexFormat
import java.util.zip.ZipFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream

/**
 * The backup round trip (SPEC.md §10.2, §12): seeds every module's [BackupFixture], exports, wipes the database and
 * the data directory, imports, exports again, and deep-compares the canonical JSON (`data/`) and the sha256 of every
 * blob (`files/`) of both exports.
 *
 * It runs against a database of its own in the shared Postgres container, because the wipe truncates every table.
 * Modules join by adding a `BackupContributor` and a [BackupFixture]; this test fails while a contributor has no
 * fixture. [TestFixtureBackupConfiguration] adds a test-only contributor, so the harness itself is covered.
 */
@SpringBootTest
@Import(TestFixtureBackupConfiguration::class)
class BackupRoundTripTest(
	@Autowired private val backups: BackupService,
	@Autowired private val contributors: BackupContributorRegistry,
	@Autowired private val context: ApplicationContext,
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val transactions: TransactionTemplate,
	@Value("\${cockpit.data-dir}") private val dataDir: Path,
) {
	@Test
	fun `every contributor's data survives export, wipe and import unchanged`(
		@TempDir work: Path,
	) {
		wipe()
		val fixtures = fixtures()
		withClue("Every BackupContributor needs a BackupFixture (SPEC.md §14)") {
			(contributors.ordered.map { it.id }.toSet() - fixtures.map { it.contributor }.toSet()).shouldBeEmpty()
		}
		transactions.executeWithoutResult { fixtures.forEach { it.seed() } }

		val original = export(work.resolve("original.zip"))
		withClue("Every exported data name has records, so the round trip compares something") {
			original.counts.shouldNotBeEmpty()
			original.counts
				.filterValues { it == 0L }
				.keys
				.shouldBeEmpty()
		}

		wipe()
		val wiped = export(work.resolve("wiped.zip"))
		withClue("After the wipe no seeded data or blob is left, so the import has to bring all of it back") {
			original.data
				.filter { (path, json) -> wiped.data[path] == json }
				.keys
				.shouldBeEmpty()
			wiped.blobs.keys.shouldBeEmpty()
		}

		backups.import(work.resolve("original.zip"))
		val restored = export(work.resolve("restored.zip"))

		restored.counts shouldBe original.counts
		restored.data.keys shouldBe original.data.keys
		original.data.forEach { (path, json) -> withClue(path) { restored.data[path] shouldBe json } }
		restored.blobs shouldBe original.blobs
	}

	/** The parts of an export the round trip compares. */
	private class Export(
		val counts: Map<String, Long>,
		/** Canonical JSON per `data/` file; JSON Lines as one array of their records. */
		val data: Map<String, JsonNode>,
		/** sha256 per `files/` blob. */
		val blobs: Map<String, String>,
	)

	private fun export(target: Path): Export {
		val manifest = target.outputStream().use { backups.export(it) }
		ZipFile(target.toFile()).use { zip ->
			val entries = zip.entries().toList().filterNot { it.isDirectory }
			val data =
				entries.filter { it.name.startsWith(BackupFormat.DATA_DIR) }.associate { entry ->
					val text = zip.getInputStream(entry).use { String(it.readAllBytes(), Charsets.UTF_8) }
					val json =
						if (entry.name.endsWith(".jsonl")) {
							backups.canonicalJson.readTree("[" + text.lines().filter { it.isNotBlank() }.joinToString(",") + "]")
						} else {
							backups.canonicalJson.readTree(text)
						}
					entry.name to json
				}
			val blobs =
				entries.filter { it.name.startsWith(BackupFormat.FILES_DIR) }.associate { entry ->
					entry.name to zip.getInputStream(entry).use { sha256(it.readAllBytes()) }
				}
			return Export(manifest.counts, data, blobs)
		}
	}

	/** Every [BackupFixture] on the test classpath, created with Spring beans injected. */
	private fun fixtures(): List<BackupFixture> =
		ClassPathScanningCandidateComponentProvider(false)
			.apply { addIncludeFilter(AssignableTypeFilter(BackupFixture::class.java)) }
			.findCandidateComponents("ch.sonensei.cockpit")
			.map { Class.forName(it.beanClassName) }
			.sortedBy { it.name }
			.map { context.autowireCapableBeanFactory.createBean(it) as BackupFixture }

	/** Empties every table but Flyway's history, and the data directory. */
	private fun wipe() {
		val tables =
			jdbc.queryForList(
				"select tablename from pg_tables where schemaname = 'public' and tablename <> 'flyway_schema_history'",
				String::class.java,
			)
		if (tables.isNotEmpty()) jdbc.execute("truncate table ${tables.joinToString { "\"$it\"" }} cascade")
		Files.createDirectories(dataDir)
		dataDir.listDirectoryEntries().forEach { it.toFile().deleteRecursively() }
	}

	private fun sha256(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

	companion object {
		private const val DATABASE = "backup_round_trip"
		private val dataDir: Path = Files.createTempDirectory("cockpit-round-trip-data")

		@JvmStatic
		@DynamicPropertySource
		fun properties(registry: DynamicPropertyRegistry) {
			val postgres = PostgresTestBase.postgres
			DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
				val exists =
					connection.prepareStatement("select 1 from pg_database where datname = ?").use { query ->
						query.setString(1, DATABASE)
						query.executeQuery().use { it.next() }
					}
				if (!exists) connection.createStatement().use { it.execute("create database $DATABASE") }
			}
			registry.add("spring.datasource.url") { postgres.jdbcUrl.replaceFirst("/${postgres.databaseName}", "/$DATABASE") }
			registry.add("spring.datasource.username") { postgres.username }
			registry.add("spring.datasource.password") { postgres.password }
			registry.add("cockpit.data-dir") { dataDir.toString() }
		}
	}
}
