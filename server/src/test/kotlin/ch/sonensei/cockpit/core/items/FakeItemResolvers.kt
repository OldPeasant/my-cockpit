package ch.sonensei.cockpit.core.items

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemResolver
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import ch.sonensei.cockpit.core.persistence.UuidV7
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** In-memory resolver: items exist once [add]ed; [failing] makes [resolve] throw. */
class FakeItemResolver(
	override val type: ItemType,
) : ItemResolver {
	private val items = ConcurrentHashMap<UUID, ResolvedItem>()

	@Volatile
	var failing = false

	fun add(
		title: String,
		id: UUID = UuidV7.generate(),
	): ItemRef {
		val path = type.name.lowercase()
		items[id] = ResolvedItem(title, "$path subtitle", "${path}_icon", "/$path/$id")
		return ItemRef(type, id)
	}

	fun remove(id: UUID) {
		items.remove(id)
	}

	fun reset() {
		items.clear()
		failing = false
	}

	override fun resolve(id: UUID): ResolvedItem? {
		check(!failing) { "fake resolver failure" }
		return items[id]
	}
}

/**
 * Replaces the application's [ItemResolverRegistry] with one that knows only fake TASK and NOTE resolvers, so the
 * tests do not depend on (or clash with) the resolvers real modules register. Every other type has no resolver.
 */
@TestConfiguration(proxyBeanMethods = false)
class FakeItemResolversConfiguration {
	@Bean
	fun fakeResolvers(): FakeItemResolvers = FakeItemResolvers()

	@Bean
	@Primary
	fun fakeItemResolverRegistry(fakes: FakeItemResolvers): ItemResolverRegistry = ItemResolverRegistry(listOf(fakes.tasks, fakes.notes))
}

class FakeItemResolvers {
	val tasks = FakeItemResolver(ItemType.TASK)
	val notes = FakeItemResolver(ItemType.NOTE)

	fun reset() {
		tasks.reset()
		notes.reset()
	}
}
