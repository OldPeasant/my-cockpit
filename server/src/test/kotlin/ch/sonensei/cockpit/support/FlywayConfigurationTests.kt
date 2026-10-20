package ch.sonensei.cockpit.support

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate

/**
 * Proves the Flyway settings from `application.properties` that parallel tasks rely on: module migrations
 * in subfolders of the location are found, and an older-versioned migration merged after a newer one is
 * still applied (`out-of-order`). Uses fixture migrations from the test classpath in a throwaway schema, so
 * the real `db/migration` location and the shared schema stay untouched.
 */
@SpringBootTest
class FlywayConfigurationTests(
	@Autowired private val flyway: Flyway,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	@Test
	fun `application Flyway scans db migration and allows out-of-order migrations`() {
		flyway.configuration.locations.map { it.descriptor } shouldContainExactly listOf("classpath:db/migration")
		flyway.configuration.isOutOfOrder shouldBe true
	}

	@Test
	fun `module subfolders are scanned and a later-merged older migration is still applied`() {
		val schema = "flyway_fixture"
		try {
			fixtureFlyway(schema, "$FIXTURES/base").migrate().migrationsExecuted shouldBe 1

			val result = fixtureFlyway(schema, "$FIXTURES/base", "$FIXTURES/late").migrate()

			result.migrationsExecuted shouldBe 1
			appliedVersions(schema) shouldContainExactly listOf("202610200900", "202610200800")
			tableCount(schema, "calendar_fixture") shouldBe 1
		} finally {
			jdbc.execute("drop schema if exists $schema cascade")
		}
	}

	/** Same configuration as the application's Flyway (incl. out-of-order), only locations/schema differ. */
	private fun fixtureFlyway(
		schema: String,
		vararg locations: String,
	): Flyway =
		Flyway
			.configure()
			.configuration(flyway.configuration)
			.schemas(schema)
			.defaultSchema(schema)
			.locations(*locations)
			.load()

	private fun appliedVersions(schema: String): List<String?> =
		jdbc.queryForList(
			"select version from $schema.flyway_schema_history where success and version is not null order by installed_rank",
			String::class.java,
		)

	private fun tableCount(
		schema: String,
		table: String,
	): Int =
		jdbc.queryForObject(
			"select count(*) from information_schema.tables where table_schema = ? and table_name = ?",
			Int::class.java,
			schema,
			table,
		)!!

	private companion object {
		const val FIXTURES = "classpath:ch/sonensei/cockpit/support/flyway"
	}
}
