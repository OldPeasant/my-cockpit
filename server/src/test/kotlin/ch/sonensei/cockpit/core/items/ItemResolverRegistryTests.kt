package ch.sonensei.cockpit.core.items

import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

class ItemResolverRegistryTests {
	@Test
	fun `ItemType has every type of SPEC section 4_3`() {
		ItemType.entries.map { it.name } shouldBe
			listOf("TASK", "PROJECT", "NOTE", "EVENT", "EMAIL", "FILE", "FOLDER", "CONTACT", "BOOKMARK", "JOURNAL_ENTRY", "HABIT")
	}

	@Test
	fun `finds the resolver of a type`() {
		val tasks = FakeItemResolver(ItemType.TASK)
		val notes = FakeItemResolver(ItemType.NOTE)
		val registry = ItemResolverRegistry(listOf(tasks, notes))

		registry.find(ItemType.TASK) shouldBeSameInstanceAs tasks
		registry.find(ItemType.NOTE) shouldBeSameInstanceAs notes
	}

	@Test
	fun `a type without a resolver gives null`() {
		ItemResolverRegistry(listOf(FakeItemResolver(ItemType.TASK))).find(ItemType.HABIT).shouldBeNull()
		ItemResolverRegistry(emptyList()).find(ItemType.TASK).shouldBeNull()
	}

	@Test
	fun `two resolvers for the same type fail at startup`() {
		val error =
			shouldThrow<IllegalStateException> {
				ItemResolverRegistry(listOf(FakeItemResolver(ItemType.TASK), FakeItemResolver(ItemType.TASK)))
			}
		error.message shouldContain "TASK"
	}

	@Test
	fun `exists follows resolve`() {
		val tasks = FakeItemResolver(ItemType.TASK)
		val ref = tasks.add("Call plumber")

		tasks.exists(ref.id) shouldBe true
		tasks.remove(ref.id)
		tasks.exists(ref.id) shouldBe false
	}
}
