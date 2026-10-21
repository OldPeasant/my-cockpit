package ch.sonensei.cockpit.mail.sync

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InboxWatcherTests {
	private val accountId = UUID.fromString("01900000-0000-7000-8000-00000000a001")
	private val scheduler = Executors.newSingleThreadScheduledExecutor()
	private val watchers = mutableListOf<InboxWatcher>()
	private val syncs = Semaphore(0)
	private val syncCount = AtomicInteger()

	@AfterEach
	fun tearDown() {
		watchers.forEach { it.stop() }
		scheduler.shutdownNow()
	}

	private fun properties(
		reIdleInterval: Duration = Duration.ofMinutes(29),
		initialBackoff: Duration = Duration.ofMillis(50),
		maxBackoff: Duration = Duration.ofSeconds(1),
	) = MailPushProperties(true, reIdleInterval, Duration.ofMinutes(5), initialBackoff, maxBackoff)

	private fun countingSync() {
		syncCount.incrementAndGet()
		syncs.release()
	}

	private fun watcher(
		connect: () -> IdleSession,
		sync: () -> Unit = { countingSync() },
		properties: MailPushProperties = properties(),
		scheduler: ScheduledExecutorService = this.scheduler,
	) = InboxWatcher(accountId, connect, sync, properties, scheduler).also { watchers += it }

	private fun awaitSync() = syncs.tryAcquire(5, TimeUnit.SECONDS) shouldBe true

	@Test
	fun `catches up once connected, then syncs after every change the server reports`() {
		val session = FakeIdleSession()
		val watcher = watcher(connect = { session })

		watcher.start()
		awaitSync()
		waitUntil { watcher.idling }
		syncCount.get() shouldBe 1

		session.change()
		awaitSync()
		session.change()
		awaitSync()

		syncCount.get() shouldBe 3
		watcher.failures shouldBe 0
		watcher.unsupported shouldBe false
	}

	@Test
	fun `stop closes the connection and ends the thread without another sync`() {
		val session = FakeIdleSession()
		val watcher = watcher(connect = { session })
		watcher.start()
		waitUntil { watcher.idling }

		watcher.stop()

		// The close runs on its own thread.
		waitUntil { session.closed }
		watcher.alive shouldBe false
		watcher.idling shouldBe false
		syncCount.get() shouldBe 1
	}

	@Test
	fun `stop returns within its timeout when closing a half-open connection hangs`() {
		val hang = CountDownLatch(1)
		val closeStarted = CountDownLatch(1)
		val session = HangingIdleSession(hang, closeStarted = closeStarted)
		val watcher = watcher(connect = { session })
		watcher.start()
		waitUntil { watcher.idling }

		try {
			val start = System.nanoTime()
			watcher.stop(Duration.ofMillis(100))
			val took = Duration.ofNanos(System.nanoTime() - start)

			closeStarted.await(5, TimeUnit.SECONDS) shouldBe true
			// Bounded by the timeout (with tolerance), not by the hanging close.
			(took < Duration.ofSeconds(2)) shouldBe true
		} finally {
			hang.countDown()
		}
		waitUntil { !watcher.alive }
	}

	@Test
	fun `a re-IDLE NOOP that hangs does not block the scheduler shared by all accounts`() {
		val scheduler = mockk<ScheduledExecutorService>()
		val task = slot<Runnable>()
		every { scheduler.scheduleAtFixedRate(capture(task), any(), any(), any()) } returns mockk<ScheduledFuture<*>>(relaxed = true)
		val hang = CountDownLatch(1)
		val refreshStarted = CountDownLatch(1)
		val session = HangingIdleSession(hang, refreshStarted = refreshStarted)
		val watcher = watcher(connect = { session }, scheduler = scheduler)
		watcher.start()
		waitUntil { watcher.idling }

		try {
			val start = System.nanoTime()
			task.captured.run()
			val took = Duration.ofNanos(System.nanoTime() - start)

			refreshStarted.await(5, TimeUnit.SECONDS) shouldBe true
			(took < Duration.ofSeconds(1)) shouldBe true
		} finally {
			watcher.stop(Duration.ofMillis(10))
			hang.countDown()
		}
		waitUntil { !watcher.alive }
	}

	/**
	 * A half-open connection: [await], [refresh] and [close] block until [hang] is released, like reads on a socket
	 * whose peer vanished. They give up after [HANG_LIMIT_SECONDS] (the socket timeout), so a regression fails the
	 * test instead of hanging it.
	 */
	private class HangingIdleSession(
		private val hang: CountDownLatch,
		private val closeStarted: CountDownLatch = CountDownLatch(1),
		private val refreshStarted: CountDownLatch = CountDownLatch(1),
	) : IdleSession {
		@Volatile private var closed = false

		override fun await() {
			hang.await(HANG_LIMIT_SECONDS, TimeUnit.SECONDS)
			if (closed) throw IllegalStateException("The fake IDLE connection is closed")
		}

		override fun refresh() {
			refreshStarted.countDown()
			hang.await(HANG_LIMIT_SECONDS, TimeUnit.SECONDS)
		}

		override fun close() {
			closeStarted.countDown()
			hang.await(HANG_LIMIT_SECONDS, TimeUnit.SECONDS)
			closed = true
		}

		companion object {
			const val HANG_LIMIT_SECONDS = 3L
		}
	}

	@Test
	fun `a failed connection is retried with a doubling backoff and the failures reset after a good connection`() {
		val session = FakeIdleSession()
		val attempts = CopyOnWriteArrayList<Long>()
		val failuresSeen = CopyOnWriteArrayList<Int>()
		lateinit var watcher: InboxWatcher
		watcher =
			watcher(
				connect = {
					attempts += System.nanoTime()
					failuresSeen += watcher.failures
					if (attempts.size <= 2) throw IllegalStateException("Fictitious server imap.example.test refused") else session
				},
				properties = properties(initialBackoff = Duration.ofMillis(100), maxBackoff = Duration.ofSeconds(1)),
			)

		watcher.start()
		awaitSync()
		waitUntil { watcher.idling }

		attempts.size shouldBe 3
		failuresSeen shouldContainExactly listOf(0, 1, 2)
		// Waited at least backoff(1) = 100 ms, then backoff(2) = 200 ms (a little tolerance for timer granularity).
		(attempts[1] - attempts[0] >= TimeUnit.MILLISECONDS.toNanos(95)) shouldBe true
		(attempts[2] - attempts[1] >= TimeUnit.MILLISECONDS.toNanos(195)) shouldBe true
		watcher.failures shouldBe 0
	}

	@Test
	fun `a failing catch-up sync counts as a failed attempt and closes the connection`() {
		val sessions = CopyOnWriteArrayList<FakeIdleSession>()
		lateinit var watcher: InboxWatcher
		watcher =
			watcher(
				connect = { FakeIdleSession().also { sessions += it } },
				sync = { throw IllegalStateException("Fictitious sync failure") },
				properties = properties(initialBackoff = Duration.ofMillis(10), maxBackoff = Duration.ofMillis(20)),
			)

		watcher.start()
		waitUntil { sessions.size >= 3 }

		(watcher.failures >= 2) shouldBe true
		watcher.idling shouldBe false
		sessions.take(2).all { it.closed } shouldBe true
	}

	@Test
	fun `a connection that fails while idling reconnects after the initial backoff and catches up again`() {
		val first = FakeIdleSession()
		val second = FakeIdleSession()
		val connects = AtomicInteger()
		val failuresAtReconnect = CopyOnWriteArrayList<Int>()
		lateinit var watcher: InboxWatcher
		watcher =
			watcher(
				connect = {
					if (connects.incrementAndGet() == 1) {
						first
					} else {
						failuresAtReconnect += watcher.failures
						second
					}
				},
			)
		watcher.start()
		awaitSync()
		waitUntil { watcher.idling }

		first.fail(IllegalStateException("Fictitious connection reset"))
		awaitSync()
		waitUntil { watcher.idling }

		connects.get() shouldBe 2
		first.closed shouldBe true
		second.closed shouldBe false
		// The good first connection had reset the counter, so this failure waited only backoff(1).
		failuresAtReconnect shouldContainExactly listOf(1)
		watcher.failures shouldBe 0
		syncCount.get() shouldBe 2
	}

	@Test
	fun `a server without IDLE ends the watcher as unsupported without syncing`() {
		val connects = AtomicInteger()
		val watcher =
			watcher(connect = {
				connects.incrementAndGet()
				throw IdleNotSupportedException()
			})

		watcher.start()
		waitUntil { !watcher.alive }

		watcher.unsupported shouldBe true
		watcher.idling shouldBe false
		connects.get() shouldBe 1
		syncCount.get() shouldBe 0
	}

	@Test
	fun `stop during the backoff wait ends the watcher right away`() {
		val connects = AtomicInteger()
		val watcher =
			watcher(
				connect = {
					connects.incrementAndGet()
					throw IllegalStateException("Fictitious server unreachable")
				},
				properties = properties(initialBackoff = Duration.ofMinutes(10), maxBackoff = Duration.ofMinutes(10)),
			)
		watcher.start()
		waitUntil { watcher.failures == 1 }

		watcher.stop()

		watcher.alive shouldBe false
		connects.get() shouldBe 1
	}

	@Test
	fun `a connection that opens after stop is closed without syncing`() {
		val connecting = CountDownLatch(1)
		val release = CountDownLatch(1)
		val session = FakeIdleSession()
		val watcher =
			watcher(connect = {
				connecting.countDown()
				release.await(5, TimeUnit.SECONDS)
				session
			})
		watcher.start()
		connecting.await(5, TimeUnit.SECONDS) shouldBe true

		watcher.stop(Duration.ofMillis(10))
		release.countDown()
		waitUntil { !watcher.alive }

		session.closed shouldBe true
		syncCount.get() shouldBe 0
	}

	@Test
	fun `the IDLE is renewed every re-IDLE interval on the scheduler, each renewal followed by a sync`() {
		val session = FakeIdleSession()
		val watcher = watcher(connect = { session }, properties = properties(reIdleInterval = Duration.ofMillis(50)))

		watcher.start()
		waitUntil { session.refreshes.get() >= 2 && syncCount.get() >= 3 }

		watcher.failures shouldBe 0
		watcher.idling shouldBe true
	}

	@Test
	fun `the re-IDLE is scheduled at the configured interval and cancelled when the connection ends`() {
		val scheduler = mockk<ScheduledExecutorService>()
		val future = mockk<ScheduledFuture<*>>(relaxed = true)
		val task = slot<Runnable>()
		every { scheduler.scheduleAtFixedRate(capture(task), any(), any(), any()) } returns future
		val session = FakeIdleSession()
		val watcher = watcher(connect = { session }, scheduler = scheduler)
		watcher.start()
		awaitSync()
		waitUntil { watcher.idling }

		val interval = Duration.ofMinutes(29).toMillis()
		verify { scheduler.scheduleAtFixedRate(any(), interval, interval, TimeUnit.MILLISECONDS) }
		task.captured.run()
		awaitSync()
		session.refreshes.get() shouldBe 1

		watcher.stop()

		verify { future.cancel(false) }
	}

	@Test
	fun `a failing re-IDLE does not escape the scheduled task`() {
		val scheduler = mockk<ScheduledExecutorService>()
		val task = slot<Runnable>()
		every { scheduler.scheduleAtFixedRate(capture(task), any(), any(), any()) } returns mockk<ScheduledFuture<*>>(relaxed = true)
		val session = FakeIdleSession().apply { refreshFailure = IllegalStateException("Fictitious NOOP failure") }
		val watcher = watcher(connect = { session }, scheduler = scheduler)
		watcher.start()
		waitUntil { watcher.idling }

		task.captured.run()

		// The NOOP runs on its own thread; its failure is swallowed there and the IDLE goes on.
		waitUntil { session.refreshes.get() == 1 }
		watcher.idling shouldBe true
		watcher.alive shouldBe true
		watcher.failures shouldBe 0
	}
}
