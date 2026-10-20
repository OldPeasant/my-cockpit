package ch.sonensei.cockpit.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupReadableWriter
import ch.sonensei.cockpit.backup.domain.json
import ch.sonensei.cockpit.backup.domain.jsonLines
import ch.sonensei.cockpit.core.persistence.UuidV7
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * A test-only module for [BackupRoundTripTest]: rows of the test table `test_persistence_fixture` (the column layout of
 * every module table, including soft deletion) plus one blob per row in the data directory under `test-fixtures/`.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestFixtureBackupConfiguration {
	@Bean
	fun testFixtureBackupContributor(
		jdbc: JdbcTemplate,
		@Value("\${cockpit.data-dir}") dataDir: Path,
	) = TestFixtureBackupContributor(jdbc, dataDir)
}

data class TestFixtureRecord(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val deletedAt: Instant?,
	val title: String,
	/** Path of the row's blob below `files/`. */
	val file: String,
)

data class TestFixtureSummary(
	val titles: Map<String, Int>,
)

class TestFixtureBackupContributor(
	private val jdbc: JdbcTemplate,
	private val dataDir: Path,
) : BackupContributor {
	override val id = ID

	override fun export(writer: BackupDataWriter) {
		val records = records()
		writer.jsonLines(RECORDS, records.asSequence())
		writer.json(SUMMARY, TestFixtureSummary(records.groupingBy { it.title }.eachCount()))
		records.forEach { record -> Files.newInputStream(blob(record.id)).use { writer.file(record.file, it) } }
	}

	override fun readable(writer: BackupReadableWriter) {
		writer.text("test-fixtures.md", records().joinToString("\n", postfix = "\n") { "- ${it.title}" })
	}

	override fun import(reader: BackupDataReader) {
		reader.jsonLines<TestFixtureRecord>(RECORDS) { record ->
			jdbc.update(
				"insert into test_persistence_fixture (id, created_at, updated_at, version, deleted_at, title) values (?, ?, ?, ?, ?, ?)",
				record.id,
				Timestamp.from(record.createdAt),
				Timestamp.from(record.updatedAt),
				record.version,
				record.deletedAt?.let(Timestamp::from),
				record.title,
			)
			val content = checkNotNull(reader.file(record.file)) { "Blob ${record.file} is missing" }
			Files.createDirectories(blob(record.id).parent)
			content.use { Files.copy(it, blob(record.id)) }
		}
		checkNotNull(reader.json<TestFixtureSummary>(SUMMARY))
	}

	private fun records(): List<TestFixtureRecord> =
		jdbc.query("select * from test_persistence_fixture order by id") { rs, _ ->
			val id = rs.getObject("id", UUID::class.java)
			TestFixtureRecord(
				id = id,
				createdAt = rs.getTimestamp("created_at").toInstant(),
				updatedAt = rs.getTimestamp("updated_at").toInstant(),
				version = rs.getLong("version"),
				deletedAt = rs.getTimestamp("deleted_at")?.toInstant(),
				title = rs.getString("title"),
				file = "Test fixtures/$id.bin",
			)
		}

	fun blob(id: UUID): Path = dataDir.resolve("test-fixtures").resolve(id.toString())

	companion object {
		const val ID = "test-fixtures"
		const val RECORDS = "test_fixtures"
		const val SUMMARY = "test_fixture_summary"
	}
}

/** Seeds [TestFixtureBackupContributor]: live and soft-deleted rows, unicode and empty titles, text and binary blobs. */
class TestFixtureBackupFixture(
	private val jdbc: JdbcTemplate,
	private val contributors: List<BackupContributor>,
) : BackupFixture {
	override val contributor = TestFixtureBackupContributor.ID

	override fun seed() {
		val module = contributors.filterIsInstance<TestFixtureBackupContributor>().single()
		val created = Instant.parse("2026-03-01T08:00:00.123456Z")
		val rows =
			listOf(
				Triple("Plan the garden", null, "Beds: tomatoes, beans\n".toByteArray()),
				Triple("Steuererklärung 2025 – «Belege» 📎", null, ByteArray(70_000) { (it % 251).toByte() }),
				Triple("", Instant.parse("2026-03-02T09:30:00Z"), ByteArray(0)),
			)
		rows.forEachIndexed { index, (title, deletedAt, blob) ->
			val id = UuidV7.generate()
			jdbc.update(
				"insert into test_persistence_fixture (id, created_at, updated_at, version, deleted_at, title) values (?, ?, ?, ?, ?, ?)",
				id,
				Timestamp.from(created),
				Timestamp.from(created.plusSeconds(index.toLong())),
				index.toLong(),
				deletedAt?.let(Timestamp::from),
				title,
			)
			Files.createDirectories(module.blob(id).parent)
			Files.write(module.blob(id), blob)
		}
	}
}
