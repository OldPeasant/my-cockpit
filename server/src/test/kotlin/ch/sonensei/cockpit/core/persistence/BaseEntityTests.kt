package ch.sonensei.cockpit.core.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** Entity rules that need no database: id handling, equality and the `Persistable` new-state. */
class BaseEntityTests {
	@Test
	fun `a new entity gets a fresh UUIDv7 id and counts as new`() {
		val first = PersistenceFixture("First")
		val second = PersistenceFixture("Second")

		UuidV7.isUuidV7(first.id) shouldBe true
		first.id shouldNotBe second.id
		first.isNew shouldBe true
		first.version shouldBe 0
		first.createdAt shouldBe null
		first.isDeleted shouldBe false
	}

	@Test
	fun `a client-supplied UUIDv7 id is kept`() {
		val clientId = UuidV7.generate()

		PersistenceFixture("Offline capture", clientId).id shouldBe clientId
	}

	@Test
	fun `a version 7 id with a non-RFC variant is rejected`() {
		shouldThrow<IllegalArgumentException> {
			PersistenceFixture("Bad variant", UUID.fromString(V7_WITH_MICROSOFT_VARIANT))
		}
	}

	@Test
	fun `the nil id is rejected`() {
		shouldThrow<IllegalArgumentException> {
			PersistenceFixture("Nil id", UUID(0, 0))
		}
	}

	@Test
	fun `entities are equal by type and id`() {
		val id = UuidV7.generate()
		val a = PersistenceFixture("Copy A", id)
		val b = PersistenceFixture("Copy B", id)

		a shouldBe b
		a.hashCode() shouldBe b.hashCode()
		a shouldNotBe PersistenceFixture("Other", UuidV7.generate())
		val sameInstance: Any = a
		val notAnEntity: Any = id
		(a.equals(sameInstance)) shouldBe true
		(a.equals(OtherKind(id))) shouldBe false
		(a.equals(notAnEntity)) shouldBe false
		a.toString() shouldBe "PersistenceFixture(id=$id)"
	}

	/** A second, non-JPA subclass: same id, different type. */
	private class OtherKind(
		id: UUID,
	) : BaseEntity(id)

	private companion object {
		/** Version nibble 7, but variant bits `110` (Microsoft) instead of `10` (RFC 9562). */
		const val V7_WITH_MICROSOFT_VARIANT = "018f3c1e-7b2a-7abc-c123-456789abcdef"
	}
}
