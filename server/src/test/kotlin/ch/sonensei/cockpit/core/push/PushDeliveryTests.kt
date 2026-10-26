package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.events.domain.ClientNotification
import ch.sonensei.cockpit.core.items.FakeItemResolvers
import ch.sonensei.cockpit.core.items.FakeItemResolversConfiguration
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.push.domain.PushService
import ch.sonensei.cockpit.core.push.domain.PushSubscription
import ch.sonensei.cockpit.core.push.domain.VapidKeyService
import ch.sonensei.cockpit.core.push.infra.PushNotificationRelay
import ch.sonensei.cockpit.core.push.infra.PushProperties
import ch.sonensei.cockpit.core.push.infra.PushSubscriptionRepository
import ch.sonensei.cockpit.core.push.infra.WebPushCrypto
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.reminders.infra.ReminderRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * Reminders and new mail reach subscribed browsers as encrypted Web Push messages (SPEC.md §4.6); the push service is
 * WireMock, the subscriptions point at it.
 */
@SpringBootTest
@Import(FakeItemResolversConfiguration::class, MutableClockTestConfiguration::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PushDeliveryTests(
	@Autowired private val subscriptions: PushSubscriptionRepository,
	@Autowired private val push: PushService,
	@Autowired private val relay: PushNotificationRelay,
	@Autowired private val vapidKeys: VapidKeyService,
	@Autowired private val events: ApplicationEventPublisher,
	@Autowired private val reminders: ReminderService,
	@Autowired private val reminderRepository: ReminderRepository,
	@Autowired private val fakes: FakeItemResolvers,
	@Autowired private val clock: MutableClock,
	@Autowired private val transactions: TransactionTemplate,
	@Autowired private val jsonMapper: JsonMapper,
	@Autowired private val properties: PushProperties,
) : PostgresTestBase() {
	private val server = WireMockServer(wireMockConfig().dynamicPort())
	private val browser = PushTestBrowser()

	@BeforeAll
	fun start() = server.start()

	@AfterAll
	fun stop() = server.stop()

	@BeforeEach
	fun setUp() {
		server.resetAll()
		server.stubFor(post(urlPathMatching("/push/.*")).willReturn(aResponse().withStatus(201)))
		subscriptions.deleteAll()
		reminderRepository.deleteAll()
		fakes.reset()
		clock.set(MutableClockTestConfiguration.START)
	}

	@AfterEach
	fun tearDown() = subscriptions.deleteAll()

	private fun subscribe(
		path: String,
		target: PushTestBrowser = browser,
	) = subscriptions.save(PushSubscription("http://127.0.0.1:${server.port()}/push/$path", target.p256dh, target.auth))

	private fun received(): List<Map<String, Any?>> =
		server.allServeEvents.reversed().map { event ->
			@Suppress("UNCHECKED_CAST")
			jsonMapper.readValue(browser.decrypt(event.request.body), Map::class.java) as Map<String, Any?>
		}

	@Test
	fun `a fired reminder is pushed to every subscription with VAPID and aes128gcm`() {
		subscribe("a")
		subscribe("b")
		val task = fakes.tasks.add("Call plumber")
		reminders.create(task, clock.instant())

		reminders.fireDue()
		relay.awaitIdle()

		server.allServeEvents shouldHaveSize 2
		server.allServeEvents.map { it.request.url }.sorted() shouldContainExactly listOf("/push/a", "/push/b")
		val request = server.allServeEvents.first().request
		request.getHeader("Content-Encoding") shouldBe "aes128gcm"
		request.getHeader("Urgency") shouldBe "high"
		request.getHeader("TTL") shouldBe Duration.ofHours(12).seconds.toString()
		request.getHeader("Authorization") shouldStartWith "vapid t="
		request.getHeader("Authorization").substringAfter(", k=") shouldBe vapidKeys.current().publicKeyBase64Url
		val message = received().first()
		message["event"] shouldBe "reminder"
		@Suppress("UNCHECKED_CAST")
		val item = (message["data"] as Map<String, Any?>)["item"] as Map<String, Any?>
		item["title"] shouldBe "Call plumber"
		item["id"] shouldBe task.id.toString()
	}

	@Test
	fun `new mail is pushed with normal urgency, other notifications are not pushed`() {
		subscribe("a")

		events.publishEvent(ClientNotification("mail", mapOf("count" to 2)))
		events.publishEvent(ClientNotification("sync-finished", mapOf("x" to 1)))
		relay.awaitIdle()

		server.allServeEvents shouldHaveSize 1
		server.allServeEvents
			.single()
			.request
			.getHeader("Urgency") shouldBe "normal"
		received().single() shouldBe mapOf("event" to "mail", "data" to mapOf("count" to 2))
	}

	@Test
	fun `a notification published in a transaction that rolls back is not pushed`() {
		subscribe("a")

		transactions.execute { status ->
			events.publishEvent(ClientNotification("mail", mapOf("count" to 1)))
			status.setRollbackOnly()
		}
		relay.awaitIdle()

		server.allServeEvents.shouldBeEmpty()
	}

	@Test
	fun `a subscription the push service reports gone is removed, others stay`() {
		subscribe("gone")
		subscribe("expired")
		subscribe("busy")
		subscribe("ok")
		server.stubFor(post(urlPathMatching("/push/gone")).willReturn(aResponse().withStatus(410)))
		server.stubFor(post(urlPathMatching("/push/expired")).willReturn(aResponse().withStatus(404)))
		server.stubFor(post(urlPathMatching("/push/busy")).willReturn(aResponse().withStatus(429)))

		push.send("mail", mapOf("count" to 1)) shouldBe 1

		subscriptions.findAll().map { it.endpoint.substringAfterLast('/') }.sorted() shouldContainExactly listOf("busy", "ok")
	}

	@Test
	fun `an unreachable push service does not stop delivery to the others`() {
		subscriptions.save(PushSubscription("http://127.0.0.1:1/push/down", browser.p256dh, browser.auth))
		subscribe("ok")

		push.send("mail", mapOf("count" to 1)) shouldBe 1

		subscriptions.findAll() shouldHaveSize 2
	}

	@Test
	fun `a message too large for one push leaves the data out`() {
		subscribe("a")

		push.send("mail", mapOf("subject" to "x".repeat(PushService.MAX_MESSAGE_BYTES)))

		received().single() shouldBe mapOf("event" to "mail")
	}

	@Test
	fun `nothing is sent without subscriptions`() {
		push.send("mail", mapOf("count" to 1)) shouldBe 0

		server.allServeEvents.shouldBeEmpty()
	}

	@Test
	fun `a subscription whose keys no longer decode is skipped, the others still get the message`() {
		subscriptions.save(PushSubscription("http://127.0.0.1:${server.port()}/push/broken", "not a key !", browser.auth))
		subscribe("ok")

		push.send("mail", mapOf("count" to 1)) shouldBe 1

		server.allServeEvents.map { it.request.url } shouldContainExactly listOf("/push/ok")
		subscriptions.findAll() shouldHaveSize 2
	}

	@Test
	fun `a server error or a redirect of the push service keeps the subscription and is not followed`() {
		subscribe("failing")
		subscribe("moved")
		server.stubFor(post(urlPathMatching("/push/failing")).willReturn(aResponse().withStatus(500)))
		server.stubFor(
			post(urlPathMatching("/push/moved"))
				.willReturn(aResponse().withStatus(301).withHeader("Location", "http://127.0.0.1:${server.port()}/push/elsewhere")),
		)

		push.send("mail", mapOf("count" to 1)) shouldBe 0

		server.allServeEvents.map { it.request.url }.sorted() shouldContainExactly listOf("/push/failing", "/push/moved")
		subscriptions.findAll() shouldHaveSize 2
	}

	@Test
	fun `the VAPID token is for the push service origin, names the configured subject and expires in 12 hours`() {
		subscribe("a")

		push.send("mail", mapOf("count" to 1))

		val token =
			server.allServeEvents
				.single()
				.request
				.getHeader("Authorization")
				.removePrefix("vapid t=")
				.substringBefore(", k=")

		@Suppress("UNCHECKED_CAST")
		val claims = jsonMapper.readValue(WebPushCrypto.decode(token.split(".")[1]), Map::class.java) as Map<String, Any?>
		claims["aud"] shouldBe "http://127.0.0.1:${server.port()}"
		claims["sub"] shouldBe properties.subject
		(claims["exp"] as Number).toLong() shouldBe clock.instant().plus(Duration.ofHours(12)).epochSecond
	}

	@Test
	fun `a message of exactly the size limit keeps its data`() {
		val overhead = push.message("mail", mapOf("subject" to "")).size
		val fitting = mapOf("subject" to "x".repeat(PushService.MAX_MESSAGE_BYTES - overhead))
		val tooLarge = mapOf("subject" to "x".repeat(PushService.MAX_MESSAGE_BYTES - overhead + 1))

		push.message("mail", fitting).size shouldBe PushService.MAX_MESSAGE_BYTES
		String(push.message("mail", fitting)) shouldBe jsonMapper.writeValueAsString(mapOf("event" to "mail", "data" to fitting))
		String(push.message("mail", tooLarge)) shouldBe """{"event":"mail"}"""

		subscribe("a")
		push.send("mail", fitting) shouldBe 1
		received().single()["data"] shouldBe fitting
	}

	@Test
	fun `a notification published in a committed transaction is pushed after the commit`() {
		subscribe("a")

		transactions.execute {
			events.publishEvent(ClientNotification("mail", mapOf("count" to 4)))
			relay.awaitIdle()
			server.allServeEvents.shouldBeEmpty()
		}
		relay.awaitIdle()

		received().single() shouldBe mapOf("event" to "mail", "data" to mapOf("count" to 4))
	}

	@Test
	fun `each browser gets a message encrypted with its own keys`() {
		val other = PushTestBrowser()
		subscribe("mine")
		subscribe("other", other)

		push.send("mail", mapOf("count" to 3)) shouldBe 2

		val toOther =
			server.allServeEvents
				.single { it.request.url == "/push/other" }
				.request.body
		String(other.decrypt(toOther)) shouldBe """{"event":"mail","data":{"count":3}}"""
		WebPushCrypto.decode(other.p256dh).size shouldBe 65
	}
}
