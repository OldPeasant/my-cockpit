package ch.sonensei.cockpit.support

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeBlank
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import javax.sql.DataSource

class ProfileTests {
	@Nested
	@SpringBootTest
	inner class Default(
		@Autowired private val env: Environment,
	) : PostgresTestBase() {
		@Test
		fun `default profile logs JSON in Logstash format`() {
			env.activeProfiles.toList().shouldBeEmpty()
			env.getProperty("logging.structured.format.console") shouldBe "logstash"
		}
	}

	@Nested
	@SpringBootTest
	@ActiveProfiles("dev")
	inner class Dev(
		@Autowired private val env: Environment,
		@Autowired private val dataSource: DataSource,
	) : PostgresTestBase() {
		@Test
		fun `dev profile starts with plain-text logs`() {
			env.getProperty("logging.structured.format.console").orEmpty().shouldBeBlank()
		}

		@Test
		fun `service connection overrides the dev profile's local datasource`() {
			dataSource.connection.use { it.metaData.url } shouldBe postgres.jdbcUrl
		}
	}

	@Nested
	@SpringBootTest
	@ActiveProfiles("e2e")
	inner class E2e(
		@Autowired private val env: Environment,
		@Autowired private val dataSource: DataSource,
	) : PostgresTestBase() {
		@Test
		fun `e2e profile starts with JSON logs`() {
			env.getProperty("logging.structured.format.console") shouldBe "logstash"
		}

		@Test
		fun `service connection overrides the e2e profile's datasource`() {
			dataSource.connection.use { it.metaData.url } shouldBe postgres.jdbcUrl
		}
	}
}
