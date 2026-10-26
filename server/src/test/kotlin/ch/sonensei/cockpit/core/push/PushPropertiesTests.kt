package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.push.infra.PushProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/** `cockpit.push.*` refuses settings the push services would reject (RFC 8292 `sub`, RFC 8030 `TTL`). */
class PushPropertiesTests {
	private fun properties(
		subject: String = "https://cockpit.example.test",
		ttl: Duration = Duration.ofHours(12),
	) = PushProperties(subject, ttl, Duration.ofSeconds(5), Duration.ofSeconds(10))

	@Test
	fun `the subject must be a mailto or https URL`() {
		properties(subject = "mailto:owner@example.test").subject shouldBe "mailto:owner@example.test"
		properties(subject = "https://cockpit.example.test").subject shouldBe "https://cockpit.example.test"
		shouldThrow<IllegalArgumentException> { properties(subject = "http://cockpit.example.test") }
		shouldThrow<IllegalArgumentException> { properties(subject = "owner@example.test") }
	}

	@Test
	fun `the TTL may be zero but not negative`() {
		properties(ttl = Duration.ZERO).ttl shouldBe Duration.ZERO
		shouldThrow<IllegalArgumentException> { properties(ttl = Duration.ofSeconds(-1)) }
	}
}
