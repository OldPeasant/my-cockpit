package ch.sonensei.cockpit.core.push.infra

import ch.sonensei.cockpit.core.events.domain.ClientNotification
import ch.sonensei.cockpit.core.push.domain.PushService
import ch.sonensei.cockpit.core.reminders.api.ReminderNotifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * Sends the client notifications a user wants to see without an open tab as Web Push messages: fired reminders
 * (`reminder`) and new mail (`mail`, which the mail module only sends for accounts that notify about new mail). Like the
 * SSE hub it acts after the publishing transaction commits, and sends on its own thread, so neither the scheduler nor
 * the mail sync waits for push services.
 */
@Component
class PushNotificationRelay(
	private val push: PushService,
) : DisposableBean {
	private val log = LoggerFactory.getLogger(javaClass)
	private val sender: ExecutorService =
		Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "web-push").apply { isDaemon = true } }

	@TransactionalEventListener(fallbackExecution = true)
	fun onNotification(notification: ClientNotification) {
		val urgency = URGENCIES[notification.name] ?: return
		try {
			sender.execute {
				try {
					push.send(notification.name, notification.data, urgency)
				} catch (e: RuntimeException) {
					log.warn("Sending the {} push message failed", notification.name, e)
				}
			}
		} catch (e: RejectedExecutionException) {
			log.debug("Dropping {} push message, the server is stopping: {}", notification.name, e.toString())
		}
	}

	/** Waits until the messages handed over so far are sent; for tests. */
	fun awaitIdle(timeoutMillis: Long = 10_000) {
		sender.submit {}.get(timeoutMillis, TimeUnit.MILLISECONDS)
	}

	override fun destroy() {
		sender.shutdownNow()
	}

	companion object {
		/** The notifications that go out as push messages, with how urgently. */
		val URGENCIES =
			mapOf(
				ReminderNotifier.EVENT_NAME to PushUrgency.HIGH,
				"mail" to PushUrgency.NORMAL,
			)
	}
}
