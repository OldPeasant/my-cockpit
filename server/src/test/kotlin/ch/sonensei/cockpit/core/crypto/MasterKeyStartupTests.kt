package ch.sonensei.cockpit.core.crypto

import ch.sonensei.cockpit.CockpitServerApplication
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.SpringApplication
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

/** Starts the real application (not a test context) against the test Postgres, with and without a master key. */
@ExtendWith(OutputCaptureExtension::class)
class MasterKeyStartupTests : PostgresTestBase() {
	@Test
	fun `application refuses to start without a master key and says why`(output: CapturedOutput) {
		val error = shouldThrowAny { start("--cockpit.master-key=") }

		generateSequence(error) { it.cause }.last().shouldBeInstanceOf<InvalidMasterKeyException>()
		output.out shouldContain "COCKPIT_MASTER_KEY is not set"
		output.out shouldContain "Provide COCKPIT_MASTER_KEY"
	}

	@Test
	fun `application refuses to start with an invalid master key without logging it`(output: CapturedOutput) {
		shouldThrowAny { start("--cockpit.master-key=bm90LWEtcmVhbC1rZXk=") }

		output.out shouldContain "must decode to 32 bytes, but has 14"
		output.out shouldNotContain "bm90LWEtcmVhbC1rZXk="
	}

	@Test
	fun `application starts with a valid master key`() {
		start("--cockpit.master-key=Y29ja3BpdC10ZXN0LW1hc3Rlci1rZXktbm90LXJlYWw=").use { context ->
			context.isActive shouldBe true
		}
	}

	private fun start(vararg args: String) =
		SpringApplication(CockpitServerApplication::class.java).run(
			"--server.port=0",
			"--spring.datasource.url=${postgres.jdbcUrl}",
			"--spring.datasource.username=${postgres.username}",
			"--spring.datasource.password=${postgres.password}",
			*args,
		)
}
