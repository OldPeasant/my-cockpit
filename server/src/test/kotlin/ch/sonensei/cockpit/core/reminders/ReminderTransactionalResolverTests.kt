package ch.sonensei.cockpit.core.reminders

import ch.sonensei.cockpit.core.auth.WithCockpitUser
import ch.sonensei.cockpit.core.events.domain.ClientNotification
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemResolver
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.reminders.api.ReminderResponse
import ch.sonensei.cockpit.core.reminders.domain.Reminder
import ch.sonensei.cockpit.core.reminders.domain.ReminderChannel
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.reminders.infra.ReminderRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Task items behind a `@Transactional` Spring proxy, like the real module resolvers (e.g. `GtdItemResolvers`): when
 * [resolve] throws inside a caller's transaction, the transaction interceptor marks that transaction rollback-only.
 * It is no [ItemResolver] bean itself (that would clash with the real `TASK` resolver); [TransactionalTaskResolver]
 * adapts it.
 */
@Transactional(readOnly = true)
class TransactionalTaskItems {
	private val titles = ConcurrentHashMap<UUID, String>()
	private val failing: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

	fun add(title: String): ItemRef {
		val id = UuidV7.generate()
		titles[id] = title
		return ItemRef(ItemType.TASK, id)
	}

	fun fail(id: UUID) {
		failing += id
	}

	fun reset() {
		titles.clear()
		failing.clear()
	}

	fun resolve(id: UUID): ResolvedItem? {
		check(id !in failing) { "transactional resolver failure" }
		return titles[id]?.let { ResolvedItem(it, icon = "task_alt", deepLink = "/tasks/$id") }
	}
}

/** The `TASK` resolver of the test registry; it calls the proxied [TransactionalTaskItems]. */
class TransactionalTaskResolver(
	private val items: TransactionalTaskItems,
) : ItemResolver {
	override val type = ItemType.TASK

	override fun resolve(id: UUID): ResolvedItem? = items.resolve(id)
}

@TestConfiguration
class TransactionalResolverConfiguration {
	@Bean
	fun transactionalTaskItems() = TransactionalTaskItems()

	@Bean
	@Primary
	fun transactionalResolverRegistry(items: TransactionalTaskItems) = ItemResolverRegistry(listOf(TransactionalTaskResolver(items)))
}

/** A failing `@Transactional` resolver must not roll back the firing batch or break reads (review finding CR-1). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TransactionalResolverConfiguration::class, MutableClockTestConfiguration::class)
@RecordApplicationEvents
@WithCockpitUser
class ReminderTransactionalResolverTests(
	@Autowired private val service: ReminderService,
	@Autowired private val repository: ReminderRepository,
	@Autowired private val resolver: TransactionalTaskItems,
	@Autowired private val clock: MutableClock,
	@Autowired private val mockMvc: MockMvc,
) : PostgresTestBase() {
	private val start = MutableClockTestConfiguration.START

	@Autowired
	private lateinit var events: ApplicationEvents

	@BeforeEach
	fun setUp() {
		repository.deleteAll()
		resolver.reset()
		clock.set(start)
	}

	private fun reminderNotifications() =
		events
			.stream(ClientNotification::class.java)
			.filter { it.name == "reminder" }
			.map { it.data as ReminderResponse }
			.toList()

	@Test
	fun `a throwing transactional resolver does not roll back the firing batch`() {
		val broken = resolver.add("Broken module row")
		val fine = resolver.add("Water plants")
		val first = service.create(broken, start).reminder
		val second = service.create(fine, start).reminder
		resolver.fail(broken.id)

		service.fireDue() shouldBe 2

		repository.findById(first.id).get().firedAt shouldBe start
		repository.findById(second.id).get().firedAt shouldBe start
		val fired = reminderNotifications().associateBy { it.id }
		fired.keys shouldContainExactlyInAnyOrder listOf(first.id, second.id)
		fired.getValue(first.id).item.available shouldBe false
		fired.getValue(first.id).item.title shouldBe null
		fired.getValue(second.id).item.title shouldBe "Water plants"
		service.fireDue() shouldBe 0
	}

	@Test
	fun `reads with a throwing transactional resolver answer with the item unavailable`() {
		val broken = resolver.add("Broken module row")
		val created = service.create(broken, start).reminder
		resolver.fail(broken.id)

		service.get(created.id).item shouldBe null
		service.ofItem(broken).single().item shouldBe null
		service.upcoming(start).single().item shouldBe null
		mockMvc.get("/api/v1/reminders?itemType=TASK&itemId=${broken.id}").andExpect {
			status { isOk() }
			jsonPath("$.items[0].item.available") { value(false) }
		}
		mockMvc.get("/api/v1/reminders/${created.id}").andExpect {
			status { isOk() }
			jsonPath("$.item.available") { value(false) }
		}
	}

	@Test
	fun `a reminder whose item is gone is still marked fired silently`() {
		val created = repository.save(Reminder(ItemRef(ItemType.TASK, UuidV7.generate()), start, ReminderChannel.NOTIFICATION))

		service.fireDue() shouldBe 1

		repository.findById(created.id).get().firedAt shouldNotBe null
		reminderNotifications().size shouldBe 0
	}
}
