package ch.sonensei.cockpit.core.push

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import ch.sonensei.cockpit.core.push.domain.UnifiedPushService
import ch.sonensei.cockpit.core.push.domain.WebPushKeys
import ch.sonensei.cockpit.core.push.infra.UnifiedPushRegistrationRepository
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.support.PostgresTestBase
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.util.Base64
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** [UnifiedPushService] against Postgres: one row per instance, the gone-endpoint race, endpoint checks, logging. */
@SpringBootTest
class UnifiedPushServiceTests(
	@Autowired private val service: UnifiedPushService,
	@Autowired private val repository: UnifiedPushRegistrationRepository,
	@Autowired private val jdbc: JdbcTemplate,
) : PostgresTestBase() {
	private val logger = LoggerFactory.getLogger(UnifiedPushService::class.java) as Logger
	private val appender = ListAppender<ILoggingEvent>()
	private var previousLevel: Level? = null

	@BeforeEach
	fun setUp() {
		repository.deleteAll()
		previousLevel = logger.level
		logger.level = Level.DEBUG
		appender.start()
		logger.addAppender(appender)
	}

	@AfterEach
	fun tearDown() {
		logger.detachAppender(appender)
		logger.level = previousLevel
	}

	private val keys = WebPushKeys(PushTestKeys.P256DH, PushTestKeys.AUTH)

	@Test
	fun `registering an instance again updates its row in place`() {
		service.register("phone", "https://ntfy.example.test/upOld", null)
		val first = repository.findByInstanceId("phone").shouldNotBeNull()

		service.register("phone", "https://ntfy.example.test/upNew", keys)

		val stored = repository.findAll().single()
		stored.id shouldBe first.id
		stored.endpoint shouldBe "https://ntfy.example.test/upNew"
		stored.keys shouldBe keys
	}

	@Test
	fun `concurrent registrations of the same instance all succeed and leave one row`() {
		val threads = 8
		repeat(5) { round ->
			val instance = "phone-$round"
			val start = CyclicBarrier(threads)
			val executor = Executors.newFixedThreadPool(threads)
			try {
				val results =
					(1..threads).map { n ->
						executor.submit<Unit> {
							start.await()
							service.register(instance, "https://ntfy.example.test/up$n", if (n % 2 == 0) keys else null)
							// A second wave updates the now existing row concurrently.
							service.register(instance, "https://ntfy.example.test/again$n", null)
						}
					}
				results.forEach { it.get(30, TimeUnit.SECONDS) }
			} finally {
				executor.shutdownNow()
			}
			repository.findByInstanceId(instance).shouldNotBeNull().endpoint shouldStartWith "https://ntfy.example.test/again"
		}
		repository.findAll() shouldHaveSize 5
	}

	@Test
	fun `instances are independent of each other`() {
		service.register("phone", "https://ntfy.example.test/a", null)
		service.register("tablet", "https://ntfy.example.test/b", keys)

		service.unregister("phone")

		repository.findAll().map { it.instanceId } shouldBe listOf("tablet")
	}

	@Test
	fun `a gone endpoint is removed while it is still the registered one`() {
		service.register("phone", "https://ntfy.example.test/up", null)
		val snapshot = service.all().single()

		service.removeGone(snapshot)

		repository.findAll().shouldBeEmpty()
	}

	@Test
	fun `a gone answer for an old endpoint does not remove the endpoint re-registered meanwhile`() {
		service.register("phone", "https://ntfy.example.test/upOld", null)
		val inFlight = service.all().single()

		service.register("phone", "https://ntfy.example.test/upNew", null)
		service.removeGone(inFlight)

		repository.findByInstanceId("phone").shouldNotBeNull().endpoint shouldBe "https://ntfy.example.test/upNew"
	}

	@Test
	fun `a gone answer does not remove a new registration of the same instance after unregister`() {
		service.register("phone", "https://ntfy.example.test/up", null)
		val inFlight = service.all().single()

		service.unregister("phone")
		service.register("phone", "https://ntfy.example.test/up", null)
		service.removeGone(inFlight)

		repository.findByInstanceId("phone").shouldNotBeNull()
	}

	@Test
	fun `removing an already removed registration is a no-op`() {
		service.register("phone", "https://ntfy.example.test/up", null)
		val snapshot = service.all().single()
		service.unregister("phone")

		service.removeGone(snapshot)
		service.unregister("phone")

		repository.findAll().shouldBeEmpty()
	}

	@Test
	fun `endpoints that are not absolute http or https URLs with a host are rejected`() {
		listOf(
			"/relative/path",
			"https:///no-host",
			"mailto:jane.doe@example.test",
			"javascript:alert(1)",
			"ftp://ntfy.example.test/up",
			"https://jane:fictitious-pw@ntfy.example.test/up",
			"https://ntfy.example.test/a b",
			"",
		).forEach { endpoint ->
			val error = shouldThrow<InvalidRequestException> { service.register("phone", endpoint, null) }
			error.fieldErrors.single().field shouldBe "endpoint"
		}

		repository.findAll().shouldBeEmpty()
	}

	@Test
	fun `http for a LAN distributor and an upper-case scheme are accepted`() {
		service.register("lan", "http://192.168.1.10:8080/up?x=1", null)
		service.register("upper", "HTTPS://ntfy.example.test/up", null)

		repository.findAll().map { it.instanceId }.sorted() shouldBe listOf("lan", "upper")
	}

	@Test
	fun `keys whose public key is not on the curve are rejected as p256dh`() {
		val point = Base64.getUrlDecoder().decode(PushTestKeys.P256DH).also { it[64] = (it[64] + 1).toByte() }
		val offCurve = WebPushKeys(Base64.getUrlEncoder().withoutPadding().encodeToString(point), PushTestKeys.AUTH)

		val error = shouldThrow<InvalidRequestException> { service.register("phone", "https://ntfy.example.test/up", offCurve) }

		error.fieldErrors.single().field shouldBe "p256dh"
		repository.findAll().shouldBeEmpty()
	}

	@Test
	fun `the table allows one row per instance and only both keys or none`() {
		val insert =
			"insert into push_unifiedpush_registration (id, created_at, updated_at, version, instance_id, endpoint, p256dh, auth) " +
				"values (gen_random_uuid(), now(), now(), 0, ?, 'https://ntfy.example.test/up', ?, ?)"
		jdbc.update(insert, "phone", null, null)
		jdbc.update(insert, "tablet", PushTestKeys.P256DH, PushTestKeys.AUTH)

		shouldThrow<DataIntegrityViolationException> { jdbc.update(insert, "phone", null, null) }
		shouldThrow<DataIntegrityViolationException> { jdbc.update(insert, "watch", PushTestKeys.P256DH, null) }
		shouldThrow<DataIntegrityViolationException> { jdbc.update(insert, "watch", null, PushTestKeys.AUTH) }

		repository.findAll().map { it.instanceId }.sorted() shouldBe listOf("phone", "tablet")
	}

	@Test
	fun `registering, unregistering and removing never log the endpoint URL`() {
		val endpoint = "https://ntfy.example.test/upFictitiousCapability123"
		service.register("phone", endpoint, null)
		service.register("phone", endpoint, keys)
		service.removeGone(service.all().single())
		service.register("phone", endpoint, null)
		service.unregister("phone")

		appender.list.shouldNotBeEmpty()
		appender.list.forEach {
			it.formattedMessage shouldNotContain "FictitiousCapability123"
			it.formattedMessage shouldNotContain "ntfy.example.test"
		}
	}
}
