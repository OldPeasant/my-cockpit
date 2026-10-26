package ch.sonensei.cockpit.core.push

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.push.domain.PushDispatcher
import ch.sonensei.cockpit.core.push.domain.PushKind
import ch.sonensei.cockpit.core.push.domain.PushMessage
import ch.sonensei.cockpit.core.push.domain.UnifiedPushRegistration
import ch.sonensei.cockpit.core.push.domain.UnifiedPushService
import ch.sonensei.cockpit.core.push.domain.WebPushKeys
import ch.sonensei.cockpit.core.push.infra.PushDeliveryException
import ch.sonensei.cockpit.core.push.infra.UnifiedPushClient
import ch.sonensei.cockpit.core.reminders.domain.Reminder
import ch.sonensei.cockpit.core.reminders.domain.ReminderChannel
import ch.sonensei.cockpit.core.reminders.domain.ReminderFiredEvent
import ch.sonensei.cockpit.core.reminders.domain.ResolvedReminder
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64

/**
 * [PushDispatcher] with a fake distributor client: failures of one endpoint, of reading the registrations or of removing
 * a gone endpoint are logged and dropped, never reach the publisher or the other endpoints, and never log the URL.
 */
class PushDispatcherFailureTests {
	private val registrations = mockk<UnifiedPushService>(relaxed = true)
	private val client = mockk<UnifiedPushClient>()
	private val dispatcher = PushDispatcher(registrations, client)

	private val logger = LoggerFactory.getLogger(PushDispatcher::class.java) as Logger
	private val appender = ListAppender<ILoggingEvent>()
	private var previousLevel: Level? = null

	@BeforeEach
	fun attach() {
		previousLevel = logger.level
		logger.level = Level.DEBUG
		appender.start()
		logger.addAppender(appender)
	}

	@AfterEach
	fun detach() {
		dispatcher.shutdown()
		logger.detachAppender(appender)
		logger.level = previousLevel
	}

	private fun registration(
		instanceId: String,
		keys: WebPushKeys? = null,
	) = UnifiedPushRegistration(instanceId, "https://ntfy.example.test/$SECRET-$instanceId", keys)

	private val message = PushMessage(PushKind.MAIL, UuidV7.generate())

	@Test
	fun `a failing endpoint does not stop the push to the others`() {
		val failing = registration("failing")
		val ok = registration("ok")
		every { registrations.all() } returns listOf(failing, ok)
		every { client.send(failing.endpoint, any(), any(), any()) } throws PushDeliveryException("ConnectException")
		every { client.send(ok.endpoint, any(), any(), any()) } returns 201

		dispatcher.onPush(message)

		verify(timeout = TIMEOUT) { client.send(ok.endpoint, any(), false, "normal") }
		verify(exactly = 0) { registrations.removeGone(any()) }
	}

	@Test
	fun `stored keys that cannot encrypt skip that endpoint only`() {
		val point = Base64.getUrlDecoder().decode(PushTestKeys.P256DH).also { it[64] = (it[64] + 1).toByte() }
		val broken = registration("broken", WebPushKeys(Base64.getUrlEncoder().withoutPadding().encodeToString(point), PushTestKeys.AUTH))
		val ok = registration("ok")
		every { registrations.all() } returns listOf(broken, ok)
		every { client.send(any(), any(), any(), any()) } returns 201

		dispatcher.onPush(message)

		verify(timeout = TIMEOUT) { client.send(ok.endpoint, any(), false, any()) }
		verify(exactly = 0) { client.send(broken.endpoint, any(), any(), any()) }
	}

	@Test
	fun `unreadable registrations drop the push without calling the distributor`() {
		every { registrations.all() } throws IllegalStateException("database down")

		dispatcher.onReminderFired(ReminderFiredEvent(fired()))

		awaitLog { it.level == Level.WARN }
		verify(exactly = 0) { client.send(any(), any(), any(), any()) }
	}

	@Test
	fun `a failing removal of a gone endpoint is logged and does not stop the other endpoints`() {
		val gone = registration("gone")
		val ok = registration("ok")
		every { registrations.all() } returns listOf(gone, ok)
		every { client.send(gone.endpoint, any(), any(), any()) } returns 410
		every { client.send(ok.endpoint, any(), any(), any()) } returns 200
		every { registrations.removeGone(gone) } throws IllegalStateException("database down")

		dispatcher.onPush(message)

		verify(timeout = TIMEOUT) { client.send(ok.endpoint, any(), any(), any()) }
		verify(exactly = 1) { registrations.removeGone(gone) }
	}

	@Test
	fun `only 404 and 410 remove an endpoint, other answers keep it`() {
		val byStatus = listOf(200, 202, 204, 301, 400, 401, 403, 404, 410, 413, 429, 500, 503).associateWith { registration("s$it") }
		every { registrations.all() } returns byStatus.values.toList()
		byStatus.forEach { (status, registration) -> every { client.send(registration.endpoint, any(), any(), any()) } returns status }

		dispatcher.onPush(message)

		byStatus.values.forEach { verify(timeout = TIMEOUT) { client.send(it.endpoint, any(), any(), any()) } }
		verify(timeout = TIMEOUT) { registrations.removeGone(byStatus.getValue(404)) }
		verify(timeout = TIMEOUT) { registrations.removeGone(byStatus.getValue(410)) }
		Thread.sleep(100)
		verify(exactly = 2) { registrations.removeGone(any()) }
	}

	@Test
	fun `a push after shutdown is dropped without an exception`() {
		every { registrations.all() } returns listOf(registration("phone"))
		dispatcher.shutdown()

		dispatcher.onPush(message)
		dispatcher.onReminderFired(ReminderFiredEvent(fired()))

		Thread.sleep(100)
		verify(exactly = 0) { client.send(any(), any(), any(), any()) }
	}

	@Test
	fun `logs of sent, failed and rejected pushes never contain the endpoint URL`() {
		val sent = registration("sent")
		val rejected = registration("rejected")
		val unreachable = registration("unreachable")
		val gone = registration("gone")
		every { registrations.all() } returns listOf(sent, rejected, unreachable, gone)
		every { client.send(sent.endpoint, any(), any(), any()) } returns 201
		every { client.send(rejected.endpoint, any(), any(), any()) } returns 500
		// Even if a client exception carried the URL, the dispatcher logs only its type.
		every { client.send(unreachable.endpoint, any(), any(), any()) } throws IllegalStateException("cannot reach ${unreachable.endpoint}")
		every { client.send(gone.endpoint, any(), any(), any()) } returns 410

		dispatcher.onPush(message)

		verify(timeout = TIMEOUT) { registrations.removeGone(gone) }
		awaitLog { it.formattedMessage.contains("unreachable") }
		awaitLog { it.formattedMessage.contains("rejected") }
		awaitLog { it.formattedMessage.contains("sent") }
		appender.list.shouldNotBeEmpty()
		appender.list.forEach {
			it.formattedMessage shouldNotContain SECRET
			it.formattedMessage shouldNotContain "ntfy.example.test"
			it.throwableProxy shouldBe null
		}
	}

	private fun awaitLog(predicate: (ILoggingEvent) -> Boolean) {
		val deadline = System.nanoTime() + TIMEOUT * 1_000_000
		while (appender.list.toList().none(predicate) && System.nanoTime() < deadline) Thread.sleep(10)
		appender.list.toList().any(predicate) shouldBe true
	}

	private fun fired() =
		ResolvedReminder(Reminder(ItemType.TASK, UuidV7.generate(), Instant.parse("2026-10-26T08:00:00Z"), ReminderChannel.NOTIFICATION), null)

	private companion object {
		const val TIMEOUT = 5_000L
		const val SECRET = "upFictitiousCapability"
	}
}
