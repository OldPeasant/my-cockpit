package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.push.domain.PushKind
import ch.sonensei.cockpit.core.push.domain.PushMessage
import ch.sonensei.cockpit.core.push.domain.UnifiedPushService
import ch.sonensei.cockpit.core.push.domain.WebPushKeys
import ch.sonensei.cockpit.core.push.infra.UnifiedPushRegistrationRepository
import ch.sonensei.cockpit.core.reminders.domain.Reminder
import ch.sonensei.cockpit.core.reminders.domain.ReminderChannel
import ch.sonensei.cockpit.core.reminders.domain.ReminderFiredEvent
import ch.sonensei.cockpit.core.reminders.domain.ResolvedReminder
import ch.sonensei.cockpit.core.reminders.infra.ReminderRepository
import ch.sonensei.cockpit.support.PostgresTestBase
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/** Pushes to registered UnifiedPush endpoints, with WireMock as the distributor's push server. */
@SpringBootTest
class PushDispatcherTests(
	@Autowired private val events: ApplicationEventPublisher,
	@Autowired private val service: UnifiedPushService,
	@Autowired private val repository: UnifiedPushRegistrationRepository,
	@Autowired private val transactions: TransactionTemplate,
	@Autowired private val reminders: ReminderRepository,
) : PostgresTestBase() {
	@BeforeEach
	fun setUp() {
		repository.deleteAll()
		reminders.deleteAll()
		distributor.resetAll()
		distributor.stubFor(post(urlPathEqualTo("/ok")).willReturn(aResponse().withStatus(201)))
		distributor.stubFor(post(urlPathEqualTo("/gone")).willReturn(aResponse().withStatus(410)))
		distributor.stubFor(post(urlPathEqualTo("/missing")).willReturn(aResponse().withStatus(404)))
		distributor.stubFor(post(urlPathEqualTo("/broken")).willReturn(aResponse().withStatus(500)))
		distributor.stubFor(post(urlPathEqualTo("/reset")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)))
	}

	private fun url(path: String) = "http://localhost:${distributor.port()}$path"

	// Saved, because the reminder's SSE notifier reads its timestamps too.
	private fun fired(): ResolvedReminder =
		ResolvedReminder(
			reminders.save(Reminder(ItemType.TASK, UuidV7.generate(), Instant.parse("2026-10-26T08:00:00Z"), ReminderChannel.NOTIFICATION)),
			null,
		)

	/** The requests to [path], once [count] arrived (sending is asynchronous). */
	private fun requests(
		path: String,
		count: Int,
	): List<LoggedRequest> {
		val deadline = System.nanoTime() + 10_000_000_000
		while (true) {
			val found =
				distributor.findAll(
					com.github.tomakehurst.wiremock.client.WireMock
						.postRequestedFor(urlPathEqualTo(path)),
				)
			if (found.size >= count || System.nanoTime() > deadline) return found
			Thread.sleep(20)
		}
	}

	private fun awaitRemoved(instanceId: String) {
		val deadline = System.nanoTime() + 10_000_000_000
		while (repository.findByInstanceId(instanceId) != null && System.nanoTime() < deadline) Thread.sleep(20)
	}

	@Test
	fun `a fired reminder is pushed as kind and id only, unencrypted without keys`() {
		service.register("phone", url("/ok"), null)
		val reminder = fired()

		events.publishEvent(ReminderFiredEvent(reminder))

		val request = requests("/ok", 1).single()
		request.bodyAsString shouldBe """{"kind":"reminder","id":"${reminder.reminder.id}"}"""
		request.getHeader("Content-Type") shouldBe "application/json"
		request.getHeader("TTL") shouldBe "86400"
		request.getHeader("Urgency") shouldBe "high"
	}

	@Test
	fun `with keys the push is encrypted with aes128gcm and decrypts to the payload`() {
		service.register("phone", url("/ok"), WebPushKeys(PushTestKeys.P256DH, PushTestKeys.AUTH))
		val mail = UuidV7.generate()

		events.publishEvent(PushMessage(PushKind.MAIL, mail))

		val request = requests("/ok", 1).single()
		request.getHeader("Content-Encoding") shouldBe "aes128gcm"
		request.getHeader("Urgency") shouldBe "normal"
		PushTestKeys.decrypt(request.body) shouldBe """{"kind":"mail","id":"$mail"}"""
	}

	@Test
	fun `every registered endpoint gets the push, endpoints answering 404 or 410 are removed, others are kept`() {
		service.register("ok", url("/ok"), null)
		service.register("gone", url("/gone"), null)
		service.register("missing", url("/missing"), null)
		service.register("broken", url("/broken"), null)
		service.register("reset", url("/reset"), null)

		events.publishEvent(PushMessage(PushKind.MAIL, UuidV7.generate()))

		listOf("/ok", "/gone", "/missing", "/broken").forEach { requests(it, 1).size shouldBe 1 }
		awaitRemoved("gone")
		awaitRemoved("missing")
		repository.findAll().map { it.instanceId } shouldContainExactlyInAnyOrder listOf("ok", "broken", "reset")
	}

	@Test
	fun `a push inside a transaction is sent only after it commits, and not at all on rollback`() {
		service.register("phone", url("/ok"), null)
		val committed = UuidV7.generate()

		transactions.execute { status ->
			events.publishEvent(PushMessage(PushKind.MAIL, UuidV7.generate()))
			status.setRollbackOnly()
		}
		transactions.execute { events.publishEvent(PushMessage(PushKind.MAIL, committed)) }

		requests("/ok", 1).single().bodyAsString shouldBe """{"kind":"mail","id":"$committed"}"""
		Thread.sleep(200)
		requests("/ok", 1).size shouldBe 1
	}

	@Test
	fun `an unreachable push server does not break the publisher`() {
		service.register("down", "http://localhost:1/nothing", null)

		events.publishEvent(ReminderFiredEvent(fired()))

		repository.findByInstanceId("down")?.instanceId shouldBe "down"
	}

	companion object {
		private val distributor = WireMockServer(wireMockConfig().dynamicPort())

		@JvmStatic
		@BeforeAll
		fun start() = distributor.start()

		@JvmStatic
		@AfterAll
		fun stop() = distributor.stop()
	}
}
