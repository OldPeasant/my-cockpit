package ch.sonensei.cockpit.core.events

import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The [ChangeEvent] value and how [ChangeEventPublisher] stamps and publishes it, without a Spring context. */
class ChangeEventTests {
	private enum class Kind { TASK, REMINDER }

	private val id = UUID.fromString("0192f0c4-7a1e-7c3d-9b2a-1f2e3d4c5b6a")
	private val now = Instant.parse("2026-03-14T09:26:53.589Z")
	private val published = mutableListOf<Any>()
	private val publisher = ChangeEventPublisher(ApplicationEventPublisher { published += it }, Clock.fixed(now, ZoneOffset.UTC))

	@Test
	fun `a blank type is rejected`() {
		for (type in listOf("", " ", "\t\n")) {
			shouldThrow<IllegalArgumentException> { ChangeEvent(type, id, ChangeOp.CREATED, now) }
		}
	}

	@Test
	fun `an enum type becomes the constant's name`() {
		ChangeEvent(Kind.REMINDER, id, ChangeOp.DELETED, now) shouldBe ChangeEvent("REMINDER", id, ChangeOp.DELETED, now)
	}

	@Test
	fun `publishing by enum stamps the event with the application clock`() {
		publisher.publish(Kind.TASK, id, ChangeOp.UPDATED)

		published shouldContainExactly listOf(ChangeEvent("TASK", id, ChangeOp.UPDATED, now))
	}

	@Test
	fun `publishing by name stamps the event with the application clock`() {
		publisher.publish("REMINDER", id, ChangeOp.CREATED)

		published shouldContainExactly listOf(ChangeEvent("REMINDER", id, ChangeOp.CREATED, now))
	}

	@Test
	fun `a complete event is published unchanged`() {
		val earlier = ChangeEvent("TASK", id, ChangeOp.DELETED, Instant.parse("2026-01-02T03:04:05Z"))

		publisher.publish(earlier)

		published shouldContainExactly listOf(earlier)
	}

	@Test
	fun `publishing a blank type fails and publishes nothing`() {
		shouldThrow<IllegalArgumentException> { publisher.publish(" ", id, ChangeOp.CREATED) }

		published.shouldBeEmpty()
	}
}
