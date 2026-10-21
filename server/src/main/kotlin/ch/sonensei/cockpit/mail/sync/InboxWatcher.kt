package ch.sonensei.cockpit.mail.sync

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Watches one account's Inbox with IDLE on its own thread (SPEC.md §7.2): connects with [connect], syncs once to
 * catch up, then syncs ([sync]) every time the server reports a change. The IDLE is restarted every
 * [MailPushProperties.reIdleInterval] (on [scheduler]). A failed connection or sync is retried after
 * [MailPushProperties.backoff]; a server without IDLE ends the watcher with [unsupported] set, so the Inbox is polled.
 */
class InboxWatcher(
	val accountId: UUID,
	private val connect: () -> IdleSession,
	private val sync: () -> Unit,
	private val properties: MailPushProperties,
	private val scheduler: ScheduledExecutorService,
) {
	private val log = LoggerFactory.getLogger(javaClass)
	private val stopped = CountDownLatch(1)
	private val thread =
		Thread
			.ofPlatform()
			.daemon()
			.name("mail-idle-$accountId")
			.unstarted(::run)

	@Volatile private var session: IdleSession? = null

	/** IDLE is running (connected and caught up). */
	@Volatile var idling: Boolean = false
		private set

	/** The server has no IDLE; the watcher has ended. */
	@Volatile var unsupported: Boolean = false
		private set

	/** Consecutive failed attempts since the last good connection. */
	@Volatile var failures: Int = 0
		private set

	val alive: Boolean get() = thread.isAlive

	fun start() = thread.start()

	/** Ends the watcher: closes its connection and waits up to [timeout] for the thread to end. */
	fun stop(timeout: Duration = Duration.ofSeconds(5)) {
		stopped.countDown()
		// Closing a half-open connection can block until its socket times out, so it never runs on the caller's thread.
		session?.let { current -> Thread.ofVirtual().name("mail-idle-close-$accountId").start(current::close) }
		if (thread.isAlive && thread != Thread.currentThread()) thread.join(timeout)
	}

	private val isStopped: Boolean get() = stopped.count == 0L

	private fun run() {
		while (!isStopped) {
			try {
				watch()
			} catch (e: IdleNotSupportedException) {
				log.info("Mail account {} has no IMAP IDLE, its Inbox is polled", accountId)
				unsupported = true
				return
			} catch (e: Exception) {
				if (isStopped) return
				failures++
				val wait = properties.backoff(failures)
				// Class name only: messages of mail exceptions may contain server responses.
				log.warn("IDLE on the Inbox of mail account {} failed ({}), retrying in {}", accountId, e.javaClass.name, wait)
				log.debug("IDLE failure", e)
				stopped.await(wait.toMillis(), TimeUnit.MILLISECONDS)
			}
		}
	}

	private fun watch() {
		connect().use { current ->
			session = current
			if (isStopped) return
			val reIdle = properties.reIdleInterval.toMillis()
			val refresher =
				scheduler.scheduleAtFixedRate(
					{
						// A NOOP on a dead connection blocks until the socket times out: off the scheduler all accounts share.
						Thread.ofVirtual().name("mail-re-idle-$accountId").start {
							try {
								current.refresh()
							} catch (e: Exception) {
								// The IDLE thread notices a dead connection itself.
								log.debug("Ending the IDLE of mail account {} failed", accountId, e)
							}
						}
					},
					reIdle,
					reIdle,
					TimeUnit.MILLISECONDS,
				)
			try {
				sync()
				failures = 0
				idling = true
				while (!isStopped) {
					current.await()
					if (!isStopped) sync()
				}
			} finally {
				idling = false
				refresher.cancel(false)
				session = null
			}
		}
	}
}
