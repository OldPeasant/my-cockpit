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
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.StandardEnvironment
import java.util.Base64

/** Starts the real application (not a test context) against the test Postgres, with and without a master key. */
@ExtendWith(OutputCaptureExtension::class)
class MasterKeyStartupTests : PostgresTestBase() {
	@Test
	fun `application refuses to start without a master key and says why`(output: CapturedOutput) {
		val error = shouldThrowAny { start("--cockpit.crypto.master-key=") }

		generateSequence(error) { it.cause }.last().shouldBeInstanceOf<InvalidMasterKeyException>()
		output.out shouldContain "COCKPIT_MASTER_KEY is not set"
		output.out shouldContain "Provide COCKPIT_MASTER_KEY"
	}

	@Test
	fun `application refuses to start with an invalid master key without logging it`(output: CapturedOutput) {
		shouldThrowAny { start("--cockpit.crypto.master-key=bm90LWEtcmVhbC1rZXk=") }

		output.out shouldContain "must decode to 32 bytes, but has 14"
		output.out shouldNotContain "bm90LWEtcmVhbC1rZXk="
	}

	@Test
	fun `application starts with a valid master key`() {
		start("--cockpit.crypto.master-key=Y29ja3BpdC10ZXN0LW1hc3Rlci1rZXktbm90LXJlYWw=").use { context ->
			context.isActive shouldBe true
		}
	}

	@Test
	fun `an exported COCKPIT_MASTER_KEY does not override the fictitious test key`() {
		val environment =
			object : StandardEnvironment() {
				override fun getSystemEnvironment(): Map<String, Any> = System.getenv() + (MASTER_KEY_ENV to "AAAA")
			}

		start(environment = environment).use { context ->
			context.isActive shouldBe true
			context.environment.getProperty("cockpit.crypto.master-key") shouldBe TEST_KEY
		}
	}

	@Test
	fun `outside tests the master key is read from the COCKPIT_MASTER_KEY variable`() {
		val deploymentKey = Base64.getEncoder().encodeToString(ByteArray(MasterKey.KEY_BYTES) { 42 })
		val environment =
			object : StandardEnvironment() {
				override fun getSystemEnvironment(): Map<String, Any> = System.getenv() + (MASTER_KEY_ENV to deploymentKey)
			}

		// Only the main application.properties, as in a deployment: no fictitious key from src/test/resources/config.
		start("--spring.config.location=classpath:/application.properties", environment = environment).use { context ->
			context.environment.getProperty("cockpit.crypto.master-key") shouldBe deploymentKey
			val stored = context.getBean(SecretCipher::class.java).encrypt("fictitious-imap-password")
			SecretCipher(Base64.getDecoder().decode(deploymentKey)).decrypt(stored) shouldBe "fictitious-imap-password"
		}
	}

	private fun start(
		vararg args: String,
		environment: ConfigurableEnvironment? = null,
	) = SpringApplication(CockpitServerApplication::class.java)
		.apply { environment?.let { setEnvironment(it) } }
		.run(
			"--server.port=0",
			"--spring.datasource.url=${postgres.jdbcUrl}",
			"--spring.datasource.username=${postgres.username}",
			"--spring.datasource.password=${postgres.password}",
			*args,
		)

	private companion object {
		/** The fictitious key from `src/test/resources/config/application.properties`. */
		const val TEST_KEY = "Y29ja3BpdC10ZXN0LW1hc3Rlci1rZXktbm90LXJlYWw="
	}
}
