package ch.sonensei.cockpit.core.push

import ch.sonensei.cockpit.core.events.domain.ClientNotification
import ch.sonensei.cockpit.core.push.domain.PushService
import ch.sonensei.cockpit.core.push.infra.PushNotificationRelay
import ch.sonensei.cockpit.core.push.infra.PushUrgency
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Which notifications the relay hands to [PushService], on which thread, and what survives a failure. */
class PushNotificationRelayTests {
	private val push = mockk<PushService>()
	private val relay = PushNotificationRelay(push)

	@AfterEach
	fun stop() = relay.destroy()

	@Test
	fun `reminders go out with high urgency, mail with normal, other notifications not at all`() {
		every { push.send(any(), any(), any()) } returns 1

		relay.onNotification(ClientNotification("reminder", mapOf("id" to "r1")))
		relay.onNotification(ClientNotification("mail", mapOf("count" to 1)))
		relay.onNotification(ClientNotification("sync-finished", mapOf("x" to 1)))
		relay.onNotification(ClientNotification("Reminder", mapOf("id" to "r2")))
		relay.awaitIdle()

		verify(exactly = 1) { push.send("reminder", mapOf("id" to "r1"), PushUrgency.HIGH) }
		verify(exactly = 1) { push.send("mail", mapOf("count" to 1), PushUrgency.NORMAL) }
		verify(exactly = 2) { push.send(any(), any(), any()) }
	}

	@Test
	fun `sends on its own thread, not the publisher's`() {
		var thread = ""
		every { push.send(any(), any(), any()) } answers {
			thread = Thread.currentThread().name
			1
		}

		relay.onNotification(ClientNotification("mail", mapOf("count" to 1)))
		relay.awaitIdle()

		thread shouldStartWith "web-push"
		(thread == Thread.currentThread().name) shouldBe false
	}

	@Test
	fun `a slow push service does not block the publisher`() {
		val release = CountDownLatch(1)
		every { push.send(any(), any(), any()) } answers {
			release.await(10, TimeUnit.SECONDS)
			1
		}

		val started = System.nanoTime()
		relay.onNotification(ClientNotification("mail", mapOf("count" to 1)))
		val tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
		release.countDown()
		relay.awaitIdle()

		(tookMillis < 5_000) shouldBe true
		verify(exactly = 1) { push.send(any(), any(), any()) }
	}

	@Test
	fun `a failing send does not stop later messages`() {
		every { push.send("reminder", any(), any()) } throws IllegalStateException("fictitious failure")
		every { push.send("mail", any(), any()) } returns 1

		relay.onNotification(ClientNotification("reminder", mapOf("id" to "r1")))
		relay.onNotification(ClientNotification("mail", mapOf("count" to 1)))
		relay.awaitIdle()

		verify(exactly = 1) { push.send("mail", any(), any()) }
	}

	@Test
	fun `notifications after shutdown are dropped without an error`() {
		relay.destroy()

		relay.onNotification(ClientNotification("mail", mapOf("count" to 1)))

		verify(exactly = 0) { push.send(any(), any(), any()) }
	}
}
