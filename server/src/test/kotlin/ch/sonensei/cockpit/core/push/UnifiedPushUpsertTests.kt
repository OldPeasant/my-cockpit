package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.push.domain.UnifiedPushService
import ch.sonensei.cockpit.core.push.domain.WebPushKeys
import ch.sonensei.cockpit.core.push.infra.UnifiedPushRegistrationRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration

/**
 * The native upsert behind [UnifiedPushService.register]: the row metadata it writes (UUIDv7 id, timestamps from the
 * injected clock, optimistic-lock version) and that a caller's persistence context does not keep a stale copy.
 */
@SpringBootTest
@Import(MutableClockTestConfiguration::class)
class UnifiedPushUpsertTests(
	@Autowired private val service: UnifiedPushService,
	@Autowired private val repository: UnifiedPushRegistrationRepository,
	@Autowired private val clock: MutableClock,
	@Autowired private val tx: TransactionTemplate,
) : PostgresTestBase() {
	private val start = MutableClockTestConfiguration.START
	private val keys = WebPushKeys(PushTestKeys.P256DH, PushTestKeys.AUTH)

	@BeforeEach
	fun setUp() {
		repository.deleteAll()
		clock.set(start)
	}

	@Test
	fun `a first registration inserts a row with a UUIDv7 id, clock timestamps and version 0`() {
		service.register("phone", "https://ntfy.example.test/up", keys)

		val stored = repository.findByInstanceId("phone").shouldNotBeNull()
		UuidV7.isUuidV7(stored.id) shouldBe true
		stored.createdAt shouldBe start
		stored.updatedAt shouldBe start
		stored.version shouldBe 0L
		stored.endpoint shouldBe "https://ntfy.example.test/up"
		stored.keys shouldBe keys
	}

	@Test
	fun `registering again keeps id and createdAt, moves updatedAt to the clock, bumps the version and clears keys`() {
		service.register("phone", "https://ntfy.example.test/upOld", keys)
		val first = repository.findByInstanceId("phone").shouldNotBeNull()

		clock.advance(Duration.ofHours(1))
		service.register("phone", "https://ntfy.example.test/upNew", null)
		clock.advance(Duration.ofMinutes(5))
		service.register("phone", "https://ntfy.example.test/upNewer", null)

		val stored = repository.findAll().single()
		stored.id shouldBe first.id
		stored.createdAt shouldBe start
		stored.updatedAt shouldBe start.plus(Duration.ofMinutes(65))
		stored.version shouldBe 2L
		stored.endpoint shouldBe "https://ntfy.example.test/upNewer"
		stored.keys shouldBe null
	}

	@Test
	fun `an entity loaded earlier in the same transaction is not served stale after a registration`() {
		service.register("phone", "https://ntfy.example.test/upOld", null)

		val reloaded =
			tx.execute {
				repository.findByInstanceId("phone").shouldNotBeNull().endpoint shouldBe "https://ntfy.example.test/upOld"
				service.register("phone", "https://ntfy.example.test/upNew", keys)
				repository.findByInstanceId("phone")
			}
		reloaded.shouldNotBeNull()

		reloaded.endpoint shouldBe "https://ntfy.example.test/upNew"
		reloaded.keys shouldBe keys
		reloaded.version shouldBe 1L
	}
}
