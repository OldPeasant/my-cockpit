package ch.sonensei.cockpit.bookmarks

import ch.sonensei.cockpit.bookmarks.domain.BookmarkMetadataRequested
import ch.sonensei.cockpit.bookmarks.domain.BookmarkService
import ch.sonensei.cockpit.bookmarks.domain.PageMetadata
import ch.sonensei.cockpit.bookmarks.infra.BookmarkFetchProperties
import ch.sonensei.cockpit.bookmarks.infra.BookmarkMetadataWorker
import ch.sonensei.cockpit.bookmarks.infra.PageFetchError
import ch.sonensei.cockpit.bookmarks.infra.PageFetchException
import ch.sonensei.cockpit.bookmarks.infra.PageFetcher
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.util.unit.DataSize
import java.net.InetAddress
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The background metadata worker without Spring: a mocked [BookmarkService] stands for the database, so retries and
 * the queue limit can be forced. The worker is called directly (as the `@TransactionalEventListener` would after a
 * commit).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookmarkMetadataWorkerTests {
	private val server = WireMockServer(wireMockConfig().dynamicPort())
	private lateinit var service: BookmarkService
	private val workers = mutableListOf<BookmarkMetadataWorker>()

	@BeforeAll
	fun start() = server.start()

	@AfterAll
	fun stop() = server.stop()

	@BeforeEach
	fun reset() {
		server.resetAll()
		service = mockk()
	}

	@AfterEach
	fun shutdown() {
		workers.forEach { it.destroy() }
		workers.clear()
	}

	private fun properties(
		allowPrivateNetworks: Boolean = true,
		threads: Int = 1,
		queueSize: Int = 10,
	) = BookmarkFetchProperties(
		DataSize.ofKilobytes(64),
		Duration.ofSeconds(1),
		Duration.ofSeconds(5),
		2,
		allowPrivateNetworks,
		threads,
		queueSize,
	)

	private fun worker(
		properties: BookmarkFetchProperties = properties(),
		fetcher: PageFetcher = PageFetcher(properties),
	) = BookmarkMetadataWorker(service, properties, fetcher).also { workers += it }

	private fun page(path: String) = "http://127.0.0.1:${server.port()}$path"

	private fun request(url: String) = BookmarkMetadataRequested(UUID.randomUUID(), url, FETCH, overwrite = false)

	/**
	 * Queues a marker request after the others on a one-thread worker and waits for it: everything queued before it has
	 * then finished (the executor runs requests in order on its single thread).
	 */
	private fun drain(worker: BookmarkMetadataWorker) {
		val marker = request("https://unreachable.invalid/marker")
		val done = CountDownLatch(1)
		every { service.applyMetadata(marker.id, any(), any(), any()) } answers {
			done.countDown()
			true
		}
		worker.onRequested(marker)
		done.await(10, TimeUnit.SECONDS) shouldBe true
	}

	@Test
	fun `a fetched page is parsed and stored for the requested bookmark`() {
		server.stubFor(
			get(urlEqualTo("/a")).willReturn(
				aResponse().withHeader("Content-Type", "text/html").withBody("""<title>Alpine huts</title><meta name="description" content="Map">"""),
			),
		)
		val request = BookmarkMetadataRequested(UUID.randomUUID(), page("/a"), FETCH, overwrite = true)
		every { service.applyMetadata(any(), any(), any(), any()) } returns true

		worker().onRequested(request)

		verify(timeout = 5_000, exactly = 1) {
			service.applyMetadata(request.id, FETCH, true, PageMetadata("Alpine huts", "Map", page("/favicon.ico")))
		}
	}

	@Test
	fun `a page that cannot be fetched is stored as failed (no metadata)`() {
		val request = request(page("/missing"))
		every { service.applyMetadata(any(), any(), any(), any()) } returns true

		worker().onRequested(request)

		verify(timeout = 5_000, exactly = 1) { service.applyMetadata(request.id, FETCH, false, null) }
	}

	@Test
	fun `a blocked address is stored as failed without any request`() {
		val worker = worker(properties(allowPrivateNetworks = false))
		val request = request("http://127.0.0.1/")
		every { service.applyMetadata(any(), any(), any(), any()) } returns true

		worker.onRequested(request)

		verify(timeout = 5_000, exactly = 1) { service.applyMetadata(request.id, FETCH, false, null) }
		server.allServeEvents.size shouldBe 0
	}

	@Test
	fun `a concurrent change is retried, three attempts at most`() {
		val worker = worker()
		val request = request(page("/missing"))
		every { service.applyMetadata(request.id, any(), any(), any()) } throws OptimisticLockingFailureException("changed")

		worker.onRequested(request)
		drain(worker)

		verify(exactly = 3) { service.applyMetadata(request.id, any(), any(), any()) }
	}

	@Test
	fun `a retry after a concurrent change stores the metadata once`() {
		val worker = worker()
		val request = request(page("/missing"))
		every { service.applyMetadata(request.id, any(), any(), any()) } throws OptimisticLockingFailureException("changed") andThen true

		worker.onRequested(request)
		drain(worker)

		verify(exactly = 2) { service.applyMetadata(request.id, any(), any(), any()) }
	}

	@Test
	fun `other storage errors are not retried and do not stop the worker`() {
		val worker = worker()
		val request = request(page("/missing"))
		every { service.applyMetadata(request.id, any(), any(), any()) } throws IllegalStateException("database down")

		worker.onRequested(request)
		drain(worker)

		verify(exactly = 1) { service.applyMetadata(request.id, any(), any(), any()) }
	}

	@Test
	fun `requests beyond the queue size are stored as failed without failing the caller`() {
		val release = CountDownLatch(1)
		val worker =
			worker(
				properties(allowPrivateNetworks = false, threads = 1, queueSize = 1),
				// The first fetch blocks in name resolution until released, so the single thread stays busy.
				PageFetcher(properties(allowPrivateNetworks = false)) { _ ->
					release.await(10, TimeUnit.SECONDS)
					listOf(InetAddress.getByName("10.0.0.1"))
				},
			)
		val running = request("http://busy.example/")
		val queued = request("http://queued.example/")
		val dropped = request("http://dropped.example/")
		every { service.applyMetadata(any(), any(), any(), any()) } returns true
		val queuedDone = CountDownLatch(1)
		every { service.applyMetadata(queued.id, any(), any(), any()) } answers {
			queuedDone.countDown()
			true
		}

		worker.onRequested(running)
		worker.onRequested(queued)
		worker.onRequested(dropped)
		release.countDown()
		queuedDone.await(10, TimeUnit.SECONDS) shouldBe true

		verify(exactly = 1) { service.applyMetadata(running.id, any(), any(), null) }
		// Not fetched, but stored as FAILED at once (on the caller's thread), so it does not stay PENDING.
		verify(exactly = 1) { service.applyMetadata(dropped.id, FETCH, false, null) }
	}

	@Test
	fun `after shutdown requests are stored as failed without failing the caller`() {
		val worker = worker()
		worker.destroy()
		val request = request(page("/a"))
		every { service.applyMetadata(any(), any(), any(), any()) } returns true

		// Rejected synchronously by the stopped executor: nothing is fetched, the status is FAILED at once.
		worker.onRequested(request)

		verify(exactly = 1) { service.applyMetadata(request.id, FETCH, false, null) }
		server.allServeEvents.size shouldBe 0
	}

	@Test
	fun `a rejected request that cannot be stored does not fail the caller`() {
		val worker = worker()
		worker.destroy()
		every { service.applyMetadata(any(), any(), any(), any()) } throws IllegalStateException("database down")

		worker.onRequested(request(page("/a")))

		verify(exactly = 1) { service.applyMetadata(any(), any(), any(), any()) }
	}

	@Test
	fun `a fetch interrupted by stopping the application stores nothing, so the bookmark stays pending`() {
		val started = CountDownLatch(1)
		var fetchThread: Thread? = null
		val fetcher = mockk<PageFetcher>()
		every { fetcher.fetch(any()) } answers {
			fetchThread = Thread.currentThread()
			started.countDown()
			try {
				Thread.sleep(10_000)
				error("not interrupted")
			} catch (e: InterruptedException) {
				// What PageFetcher does when its wait is interrupted.
				throw PageFetchException(PageFetchError.TIMEOUT, e)
			}
		}
		every { service.applyMetadata(any(), any(), any(), any()) } returns true
		val worker = worker(fetcher = fetcher)

		worker.onRequested(request("https://slow.example/"))
		started.await(5, TimeUnit.SECONDS) shouldBe true
		worker.destroy()
		val thread = checkNotNull(fetchThread)
		thread.join(5_000)

		thread.isAlive shouldBe false
		verify(exactly = 0) { service.applyMetadata(any(), any(), any(), any()) }
	}

	@Test
	fun `on startup the fetches of bookmarks still pending are queued again`() {
		server.stubFor(get(urlEqualTo("/p")).willReturn(aResponse().withHeader("Content-Type", "text/html").withBody("<title>Pending</title>")))
		val pending = BookmarkMetadataRequested(UUID.randomUUID(), page("/p"), 7, overwrite = false)
		every { service.pendingMetadataRequests() } returns listOf(pending)
		every { service.applyMetadata(any(), any(), any(), any()) } returns true

		worker().requeuePending()

		verify(timeout = 5_000, exactly = 1) { service.applyMetadata(pending.id, 7, false, PageMetadata("Pending", null, page("/favicon.ico"))) }
	}

	@Test
	fun `on startup a failure to load the pending fetches does not stop the application`() {
		every { service.pendingMetadataRequests() } throws IllegalStateException("database down")

		worker().requeuePending()

		verify(exactly = 0) { service.applyMetadata(any(), any(), any(), any()) }
	}

	private companion object {
		const val FETCH = 1L
	}
}
