package ch.sonensei.cockpit.core.trash

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.trash.domain.TrashContributor
import ch.sonensei.cockpit.core.trash.domain.TrashContributorRegistry
import ch.sonensei.cockpit.core.trash.domain.TrashedItem
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory contributor: items are active once [addActive]ed, in the Trash once [addDeleted]ed; [purged] records
 * permanent deletes; [failing] makes every call throw, [failingPurge] only purges of that id.
 */
class FakeTrashContributor(
	override val type: ItemType,
) : TrashContributor {
	private val active = ConcurrentHashMap<UUID, String>()
	private val deleted = ConcurrentHashMap<UUID, TrashedItem>()
	val purged: MutableList<UUID> = Collections.synchronizedList(mutableListOf())

	@Volatile
	var failing = false

	@Volatile
	var failingPurge: UUID? = null

	fun addDeleted(
		title: String,
		deletedAt: Instant,
		id: UUID = UuidV7.generate(),
	): ItemRef {
		val path = type.name.lowercase()
		deleted[id] = TrashedItem(id, title, deletedAt, "$path subtitle", "${path}_icon")
		return ItemRef(type, id)
	}

	fun addActive(
		title: String,
		id: UUID = UuidV7.generate(),
	): ItemRef {
		active[id] = title
		return ItemRef(type, id)
	}

	fun isActive(id: UUID) = active.containsKey(id)

	fun isDeleted(id: UUID) = deleted.containsKey(id)

	fun reset() {
		active.clear()
		deleted.clear()
		purged.clear()
		failing = false
		failingPurge = null
	}

	override fun listDeleted(): List<TrashedItem> {
		check(!failing) { "fake contributor failure" }
		return deleted.values.toList()
	}

	override fun restore(id: UUID): Boolean {
		check(!failing) { "fake contributor failure" }
		val item = deleted.remove(id) ?: return false
		active[id] = item.title
		return true
	}

	override fun purge(id: UUID): Boolean {
		check(!failing && failingPurge != id) { "fake contributor failure" }
		deleted.remove(id) ?: return false
		purged += id
		return true
	}
}

/**
 * Replaces the application's [TrashContributorRegistry] with one that knows only fake TASK and NOTE contributors, so
 * the tests do not depend on the contributors real modules register. Every other type has no Trash.
 */
@TestConfiguration(proxyBeanMethods = false)
class FakeTrashContributorsConfiguration {
	@Bean
	fun fakeTrashContributors(): FakeTrashContributors = FakeTrashContributors()

	@Bean
	@Primary
	fun fakeTrashContributorRegistry(fakes: FakeTrashContributors): TrashContributorRegistry =
		TrashContributorRegistry(listOf(fakes.tasks, fakes.notes))
}

class FakeTrashContributors {
	val tasks = FakeTrashContributor(ItemType.TASK)
	val notes = FakeTrashContributor(ItemType.NOTE)

	fun reset() {
		tasks.reset()
		notes.reset()
	}
}
