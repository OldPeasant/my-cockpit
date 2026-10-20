package ch.sonensei.cockpit.core.persistence

import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class UuidV7Tests {
	@Test
	fun `generated ids are version 7`() {
		val id = UuidV7.generate()

		id.version() shouldBe 7
		id.variant() shouldBe 2
		UuidV7.isUuidV7(id) shouldBe true
	}

	@Test
	fun `ids are time-ordered and unique, also within the same millisecond`() {
		val ids = List(10_000) { UuidV7.generate() }

		// Text order equals creation order, which is what Postgres `uuid` ordering and indexes use.
		ids.map { it.toString() }.shouldBeSorted()
		ids.toSet() shouldHaveSize ids.size
	}

	@Test
	fun `ids created later have a later timestamp prefix`() {
		val first = UuidV7.generate()
		Thread.sleep(2)
		val second = UuidV7.generate()

		(timestampMillis(second) > timestampMillis(first)) shouldBe true
	}

	@Test
	fun `random v4 ids are not UUIDv7`() {
		UuidV7.isUuidV7(UUID.randomUUID()) shouldBe false
	}

	@Test
	fun `version 7 with a non-RFC variant and the nil id are not UUIDv7`() {
		UuidV7.isUuidV7(UUID.fromString("018f3c1e-7b2a-7abc-c123-456789abcdef")) shouldBe false
		UuidV7.isUuidV7(UUID(0, 0)) shouldBe false
	}

	private fun timestampMillis(id: UUID): Long = id.mostSignificantBits ushr 16
}
