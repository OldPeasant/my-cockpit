package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupFileEntry
import ch.sonensei.cockpit.backup.domain.BackupFormat
import ch.sonensei.cockpit.backup.domain.BackupManifest
import ch.sonensei.cockpit.support.PostgresTestBase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.listDirectoryEntries

/**
 * A contributor without data whose import fails on demand ([fail]), after the ones it depends on have imported. Its
 * export waits for [exportGate] when set, to keep a backup running, and fails on demand ([failExport]); its restore
 * warnings fail on demand ([failWarnings]) with an exception other than InvalidBackupException.
 */
class FailingImportBackupContributor : BackupContributor {
	override val id = "failing-import"
	override val dependsOn = setOf(TestFixtureBackupContributor.ID)

	@Volatile var fail = false

	@Volatile var exportGate: CountDownLatch? = null

	@Volatile var failExport = false

	@Volatile var failWarnings = false

	override fun export(writer: BackupDataWriter) {
		exportGate?.await(30, TimeUnit.SECONDS)
		check(!failExport) { "Export failed for 'Secret title'" }
	}

	override fun restoreWarnings(reader: BackupDataReader): List<String> {
		check(!failWarnings) { "Warnings failed for 'Secret title'" }
		return emptyList()
	}

	override fun import(reader: BackupDataReader) = check(!fail) { "Import failed for 'Secret title'" }
}

@TestConfiguration(proxyBeanMethods = false)
class FailingImportBackupConfiguration {
	@Bean
	fun failingImportBackupContributor() = FailingImportBackupContributor()
}

/**
 * Restore tests empty every table, so they run against databases of their own in the shared Postgres container (like
 * [BackupRoundTripTest]).
 */
object RestoreTestDatabases {
	fun create(name: String): Triple<String, String, String> {
		val postgres = PostgresTestBase.postgres
		DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
			val exists =
				connection.prepareStatement("select 1 from pg_database where datname = ?").use { query ->
					query.setString(1, name)
					query.executeQuery().use { it.next() }
				}
			if (!exists) connection.createStatement().use { it.execute("create database $name") }
		}
		return Triple(postgres.jdbcUrl.replaceFirst("/${postgres.databaseName}", "/$name"), postgres.username, postgres.password)
	}

	fun register(
		registry: DynamicPropertyRegistry,
		name: String,
		dataDir: Path,
	) {
		val (url, username, password) = create(name)
		registry.add("spring.datasource.url") { url }
		registry.add("spring.datasource.username") { username }
		registry.add("spring.datasource.password") { password }
		registry.add("cockpit.data-dir") { dataDir.toString() }
	}

	/** Empties every table but Flyway's history, and the data directory. */
	fun wipe(
		jdbc: JdbcTemplate,
		dataDir: Path,
	) {
		val tables =
			jdbc.queryForList(
				"select tablename from pg_tables where schemaname = 'public' and tablename <> 'flyway_schema_history'",
				String::class.java,
			)
		if (tables.isNotEmpty()) jdbc.execute("truncate table ${tables.joinToString { "\"$it\"" }} cascade")
		Files.createDirectories(dataDir)
		dataDir.listDirectoryEntries().forEach { it.toFile().deleteRecursively() }
	}
}

/** Zip bytes as an ordered map of entry name to content, and back. */
object Zips {
	fun read(zip: ByteArray): LinkedHashMap<String, ByteArray> {
		val entries = LinkedHashMap<String, ByteArray>()
		ZipInputStream(ByteArrayInputStream(zip)).use { input ->
			generateSequence { input.nextEntry }.forEach { entries[it.name] = input.readAllBytes() }
		}
		return entries
	}

	fun write(entries: Map<String, ByteArray>): ByteArray {
		val out = ByteArrayOutputStream()
		ZipOutputStream(out).use { zip ->
			entries.forEach { (name, bytes) ->
				zip.putNextEntry(ZipEntry(name))
				zip.write(bytes)
				zip.closeEntry()
			}
		}
		return out.toByteArray()
	}

	/** [change]s the entries of [zip], then lists the sha256 of the changed files in its manifest again (a careful hand-edit). */
	fun edit(
		zip: ByteArray,
		mapper: JsonMapper,
		updateManifest: Boolean,
		change: (MutableMap<String, ByteArray>) -> Unit,
	): ByteArray {
		val entries = read(zip)
		change(entries)
		if (updateManifest) {
			val manifest = mapper.readValue(entries.getValue(BackupFormat.MANIFEST), BackupManifest::class.java)
			val files =
				entries
					.filterKeys { it != BackupFormat.MANIFEST }
					.map { (name, bytes) -> BackupFileEntry(name, sha256(bytes), bytes.size.toLong()) }
					.sortedBy { it.path }
			entries[BackupFormat.MANIFEST] = mapper.writeValueAsBytes(manifest.copy(files = files))
		}
		return write(entries)
	}

	fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
