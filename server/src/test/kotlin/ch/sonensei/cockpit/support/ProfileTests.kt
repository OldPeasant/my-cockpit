package ch.sonensei.cockpit.support

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeBlank
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles

class ProfileTests {
	@Nested
	@SpringBootTest
	@ActiveProfiles("dev")
	inner class Dev(
		@Autowired private val env: Environment,
	) : PostgresTestBase() {
		@Test
		fun `dev profile starts with plain-text logs`() {
			env.getProperty("logging.structured.format.console").orEmpty().shouldBeBlank()
		}
	}

	@Nested
	@SpringBootTest
	@ActiveProfiles("e2e")
	inner class E2e(
		@Autowired private val env: Environment,
	) : PostgresTestBase() {
		@Test
		fun `e2e profile starts with JSON logs`() {
			env.getProperty("logging.structured.format.console") shouldBe "logstash"
		}
	}
}
