package ch.sonensei.cockpit.core.push.domain

import ch.sonensei.cockpit.core.push.infra.UnifiedPushClient
import ch.sonensei.cockpit.core.reminders.domain.ReminderFiredEvent
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Sends a push to every registered UnifiedPush endpoint when a reminder fires ([ReminderFiredEvent]) or a module
 * publishes a [PushMessage] (new mail), after the publishing transaction commits. Sending runs on its own virtual
 * threads, so a slow or failing push server never delays or breaks the reminder scheduler or the mail sync: failures are
 * logged and dropped. An endpoint answering 404 or 410 is gone (RFC 8030 §7.3) and is removed.
 */
@Component
class PushDispatcher(
	private val registrations: UnifiedPushService,
	private val client: UnifiedPushClient,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

	@TransactionalEventListener(fallbackExecution = true)
	fun onReminderFired(event: ReminderFiredEvent) = dispatch(PushMessage(PushKind.REMINDER, event.reminder.reminder.id))

	@TransactionalEventListener(fallbackExecution = true)
	fun onPush(message: PushMessage) = dispatch(message)

	private fun dispatch(message: PushMessage) {
		try {
			executor.execute { sendToAll(message) }
		} catch (_: RejectedExecutionException) {
			log.debug("Push {} dropped: shutting down", message.kind)
		}
	}

	private fun sendToAll(message: PushMessage) {
		val targets =
			try {
				registrations.all()
			} catch (e: Exception) {
				log.warn("Push {} not sent: registrations could not be read ({})", message.kind, e.javaClass.simpleName)
				return
			}
		targets.forEach { send(it, message) }
	}

	private fun send(
		registration: UnifiedPushRegistration,
		message: PushMessage,
	) {
		try {
			val payload = message.toJson().toByteArray()
			val keys = registration.keys
			val body = if (keys == null) payload else WebPushEncryption.encrypt(payload, keys)
			val urgency = if (message.kind == PushKind.REMINDER) "high" else "normal"
			when (val status = client.send(registration.endpoint, body, keys != null, urgency)) {
				in 200..299 -> log.debug("Push {} sent to UnifiedPush instance {}", message.kind, registration.instanceId)
				404, 410 -> registrations.removeGone(registration)
				else -> log.warn("Push {} to UnifiedPush instance {} failed: HTTP {}", message.kind, registration.instanceId, status)
			}
		} catch (e: Exception) {
			log.warn("Push {} to UnifiedPush instance {} failed ({})", message.kind, registration.instanceId, e.javaClass.simpleName)
		}
	}

	@PreDestroy
	fun shutdown() {
		executor.shutdown()
	}
}
