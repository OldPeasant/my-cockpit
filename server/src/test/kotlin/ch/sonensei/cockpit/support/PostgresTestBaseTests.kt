package ch.sonensei.cockpit.support

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.sql.DataSource

@SpringBootTest
class PostgresTestBaseTests(
	@Autowired private val jdbc: JdbcTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val dataSource: DataSource,
) : PostgresTestBase() {
	@Test
	fun `datasource points at the shared Postgres 16 container`() {
		val version = jdbc.queryForObject("show server_version", String::class.java)
		version shouldStartWith "16."
		jdbc.queryForObject("select current_database()", String::class.java) shouldBe postgres.databaseName
	}

	@Test
	fun `datasource uses the running shared container through the service connection`() {
		postgres.isRunning shouldBe true
		dataSource.connection.use { it.metaData.url } shouldBe postgres.jdbcUrl
	}

	@Test
	fun `Flyway ran against the container`() {
		val tables =
			jdbc.queryForObject(
				"select count(*) from information_schema.tables where table_name = 'flyway_schema_history'",
				Int::class.java,
			)
		tables shouldBe 1
	}

	@Test
	fun `Jackson writes dates as ISO-8601 strings`() {
		jsonMapper.writeValueAsString(Instant.parse("2026-01-02T03:04:05Z")) shouldBe "\"2026-01-02T03:04:05Z\""
		jsonMapper.writeValueAsString(LocalDate.of(2026, 1, 2)) shouldBe "\"2026-01-02\""
		jsonMapper.writeValueAsString(OffsetDateTime.parse("2026-01-02T03:04:05+01:00")) shouldBe
			"\"2026-01-02T03:04:05+01:00\""
		jsonMapper.writeValueAsString(Duration.ofMinutes(90)) shouldBe "\"PT1H30M\""
	}
}
