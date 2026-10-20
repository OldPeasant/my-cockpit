package ch.sonensei.cockpit.core.events.infra

import ch.sonensei.cockpit.core.events.domain.ChangeEvent
import ch.sonensei.cockpit.core.events.domain.ClientNotification
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * The connected SSE clients of `GET /api/v1/events` (SPEC.md §2).
 *
 * - [onChange] forwards every [ChangeEvent] after the publishing transaction commits; an event published in a
 *   transaction that rolls back is dropped. An event published outside a transaction is forwarded right away.
 * - [onNotification] does the same for a [ClientNotification], under its own event name (e.g. `reminder`).
 * - Every [heartbeat] (default 25 s) each client gets a comment line, which keeps proxies from closing an idle
 *   stream and reveals clients that went away.
 * - Every stream ends after [streamTimeout] (default 15 min). EventSource clients reconnect on their own, and each
 *   reconnect passes the authorization filters again, so a stream outlives the end of its login (logout, expiry,
 *   revocation) by at most that time.
 * - A client is removed when its stream completes, times out, fails, or a write to it fails.
 *
 * Sends run on one background thread, so a commit never waits for a client's socket.
 */
@Component
class SseEventHub(
	@Value("\${cockpit.events.heartbeat:PT25S}") private val heartbeat: Duration,
	@Value("\${cockpit.events.stream-timeout:PT15M}") private val streamTimeout: Duration,
) : DisposableBean {
	private val log = LoggerFactory.getLogger(javaClass)
	private val emitters = CopyOnWriteArraySet<SseEmitter>()
	private val sender =
		Executors.newSingleThreadScheduledExecutor { runnable ->
			Thread(runnable, "sse-events").apply { isDaemon = true }
		}

	init {
		require(heartbeat.isPositive) { "cockpit.events.heartbeat must be positive" }
		require(streamTimeout.isPositive) { "cockpit.events.stream-timeout must be positive" }
		sender.scheduleAtFixedRate(::sendHeartbeat, heartbeat.toMillis(), heartbeat.toMillis(), TimeUnit.MILLISECONDS)
	}

	/** Number of connected clients. */
	val clientCount: Int get() = emitters.size

	/**
	 * A new stream for one client; it stays open until [streamTimeout] passes, the client disconnects or the server
	 * stops. On the timeout the stream ends normally, so the client simply reconnects.
	 */
	fun subscribe(): SseEmitter {
		val emitter = SseEmitter(streamTimeout.toMillis())
		emitter.onCompletion { emitters.remove(emitter) }
		emitter.onTimeout {
			emitters.remove(emitter)
			// Completing here ends the committed response cleanly instead of with an AsyncRequestTimeoutException (503).
			runCatching { emitter.complete() }
		}
		emitter.onError { emitters.remove(emitter) }
		emitters.add(emitter)
		// The first bytes flush the response headers, so the client (and any proxy) sees the stream open at once.
		trySend(emitter) { SseEmitter.event().comment("connected") }
		return emitter
	}

	@TransactionalEventListener(fallbackExecution = true)
	fun onChange(event: ChangeEvent) = broadcast(EVENT_NAME, event)

	/** Forwards a [ClientNotification] as SSE event of its name, after the publishing transaction commits. */
	@TransactionalEventListener(fallbackExecution = true)
	fun onNotification(notification: ClientNotification) = broadcast(notification.name, notification.data)

	private fun broadcast(
		name: String,
		data: Any,
	) {
		try {
			sender.execute {
				emitters.forEach { emitter ->
					trySend(emitter) {
						SseEmitter
							.event()
							.name(name)
							.data(data, MediaType.APPLICATION_JSON)
					}
				}
			}
		} catch (e: RejectedExecutionException) {
			// The hub is shut down (server stopping): the change itself succeeded, so don't fail its publisher.
			log.debug("Dropping {} event, SSE hub is shut down: {}", name, e.toString())
		}
	}

	private fun sendHeartbeat() {
		emitters.forEach { emitter -> trySend(emitter) { SseEmitter.event().comment("heartbeat") } }
	}

	/** Sends one event; a client that cannot be written to is dropped. Builders are single-use, hence the factory. */
	private fun trySend(
		emitter: SseEmitter,
		event: () -> SseEmitter.SseEventBuilder,
	) {
		try {
			emitter.send(event())
		} catch (e: Exception) {
			// Usually the client went away (broken pipe) or the stream is already complete.
			log.debug("Dropping SSE client: {}", e.toString())
			emitters.remove(emitter)
			runCatching { emitter.complete() }
		}
	}

	override fun destroy() {
		sender.shutdownNow()
		emitters.forEach { runCatching { it.complete() } }
		emitters.clear()
	}

	companion object {
		/** The SSE `event:` name of a [ChangeEvent]. */
		const val EVENT_NAME = "change"
	}
}
