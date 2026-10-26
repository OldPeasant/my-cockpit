package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.items.FakeItemResolvers
import ch.sonensei.cockpit.core.items.FakeItemResolversConfiguration
import ch.sonensei.cockpit.core.persistence.MutableClock
import ch.sonensei.cockpit.core.persistence.MutableClockTestConfiguration
import ch.sonensei.cockpit.core.push.domain.UnifiedPushService
import ch.sonensei.cockpit.core.push.infra.PushDeliveryException
import ch.sonensei.cockpit.core.push.infra.UnifiedPushClient
import ch.sonensei.cockpit.core.push.infra.UnifiedPushRegistrationRepository
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.reminders.infra.ReminderRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration

/** A reminder fired by [ReminderService.fireDue] reaches the distributor; [UnifiedPushClient] against WireMock. */
@SpringBootTest
@Import(FakeItemResolversConfiguration::class, MutableClockTestConfiguration::class)
class PushEndToEndTests(
	@Autowired private val reminderService: ReminderService,
	@Autowired private val reminders: ReminderRepository,
	@Autowired private val fakes: FakeItemResolvers,
	@Autowired private val clock: MutableClock,
	@Autowired private val service: UnifiedPushService,
	@Autowired private val repository: UnifiedPushRegistrationRepository,
	@Autowired private val client: UnifiedPushClient,
) : PostgresTestBase() {
	private val start = MutableClockTestConfiguration.START

	@BeforeEach
	fun setUp() {
		repository.deleteAll()
		reminders.deleteAll()
		fakes.reset()
		clock.set(start)
		distributor.resetAll()
		distributor.stubFor(post(urlPathEqualTo("/ok")).willReturn(aResponse().withStatus(201)))
		distributor.stubFor(
			post(urlPathEqualTo("/moved")).willReturn(aResponse().withStatus(302).withHeader("Location", url("/ok"))),
		)
	}

	private fun url(path: String) = "http://localhost:${distributor.port()}$path"

	private fun requests(
		path: String,
		count: Int,
	): List<LoggedRequest> {
		val deadline = System.nanoTime() + 10_000_000_000
		while (true) {
			val found = distributor.findAll(postRequestedFor(urlPathEqualTo(path)))
			if (found.size >= count || System.nanoTime() > deadline) return found
			Thread.sleep(20)
		}
	}

	@Test
	fun `a reminder fired by the scheduler is pushed with its id, never its item's title`() {
		service.register("phone", url("/ok"), null)
		val task = fakes.tasks.add("Call the fictitious plumber")
		val reminder = reminderService.create(task, start.plus(Duration.ofMinutes(30))).reminder
		clock.advance(Duration.ofMinutes(30))

		reminderService.fireDue() shouldBe 1

		val request = requests("/ok", 1).single()
		request.bodyAsString shouldBe """{"kind":"reminder","id":"${reminder.id}"}"""
		request.bodyAsString shouldNotContain "plumber"
		request.getHeader("Urgency") shouldBe "high"
	}

	@Test
	fun `a reminder of a deleted item fires silently, without a push`() {
		service.register("phone", url("/ok"), null)
		val task = fakes.tasks.add("Fictitious errand")
		reminderService.create(task, start.plus(Duration.ofMinutes(30)))
		fakes.tasks.remove(task.id)
		clock.advance(Duration.ofMinutes(30))

		reminderService.fireDue() shouldBe 1

		Thread.sleep(300)
		distributor.findAll(postRequestedFor(urlPathEqualTo("/ok"))).shouldBeEmpty()
	}

	@Test
	fun `a failing resolver still pushes the reminder`() {
		service.register("phone", url("/ok"), null)
		val task = fakes.tasks.add("Fictitious errand")
		val reminder = reminderService.create(task, start.plus(Duration.ofMinutes(30))).reminder
		fakes.tasks.failing = true
		clock.advance(Duration.ofMinutes(30))

		reminderService.fireDue() shouldBe 1

		requests("/ok", 1).single().bodyAsString shouldBe """{"kind":"reminder","id":"${reminder.id}"}"""
	}

	@Test
	fun `the client does not follow redirects and the dispatcher keeps such an endpoint`() {
		client.send(url("/moved"), "{}".toByteArray(), false, "normal") shouldBe 302
		requests("/ok", 0).shouldBeEmpty()

		service.register("phone", url("/moved"), null)
		reminderService.create(fakes.tasks.add("Fictitious errand"), start)
		reminderService.fireDue() shouldBe 1

		requests("/moved", 2).size shouldBe 2
		Thread.sleep(200)
		distributor.findAll(postRequestedFor(urlPathEqualTo("/ok"))).shouldBeEmpty()
		repository.findByInstanceId("phone").shouldNotBeNull()
	}

	@Test
	fun `an unreachable endpoint fails with an exception that names neither host nor path`() {
		val endpoint = "http://localhost:1/upFictitiousCapability"

		val error = shouldThrow<PushDeliveryException> { client.send(endpoint, "{}".toByteArray(), false, "normal") }

		error.message.orEmpty() shouldNotContain "upFictitiousCapability"
		error.message.orEmpty() shouldNotContain "localhost"
		error.cause shouldBe null
	}

	companion object {
		private val distributor = WireMockServer(wireMockConfig().dynamicPort())

		@JvmStatic
		@BeforeAll
		fun startDistributor() = distributor.start()

		@JvmStatic
		@AfterAll
		fun stopDistributor() = distributor.stop()
	}
}
