package ch.sonensei.cockpit.bookmarks

import ch.sonensei.cockpit.bookmarks.domain.BookmarkMetadataRequested
import ch.sonensei.cockpit.bookmarks.domain.BookmarkService
import ch.sonensei.cockpit.bookmarks.domain.PageMetadata
import ch.sonensei.cockpit.bookmarks.infra.BookmarkFetchProperties
import ch.sonensei.cockpit.bookmarks.infra.BookmarkMetadataWorker
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

	private fun worker(properties: BookmarkFetchProperties = properties()) = BookmarkMetadataWorker(service, properties).also { workers += it }

	private fun page(path: String) = "http://127.0.0.1:${server.port()}$path"

	private fun request(url: String) = BookmarkMetadataRequested(UUID.randomUUID(), url, overwrite = false)

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
		val request = BookmarkMetadataRequested(UUID.randomUUID(), page("/a"), overwrite = true)
		every { service.applyMetadata(any(), any(), any(), any()) } returns true

		worker().onRequested(request)

		verify(timeout = 5_000, exactly = 1) {
			service.applyMetadata(request.id, page("/a"), true, PageMetadata("Alpine huts", "Map", page("/favicon.ico")))
		}
	}

	@Test
	fun `a page that cannot be fetched is stored as failed (no metadata)`() {
		val request = request(page("/missing"))
		every { service.applyMetadata(any(), any(), any(), any()) } returns true

		worker().onRequested(request)

		verify(timeout = 5_000, exactly = 1) { service.applyMetadata(request.id, page("/missing"), false, null) }
	}

	@Test
	fun `a blocked address is stored as failed without any request`() {
		val worker = worker(properties(allowPrivateNetworks = false))
		val request = request("http://127.0.0.1/")
		every { service.applyMetadata(any(), any(), any(), any()) } returns true

		worker.onRequested(request)

		verify(timeout = 5_000, exactly = 1) { service.applyMetadata(request.id, "http://127.0.0.1/", false, null) }
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
	fun `requests beyond the queue size are dropped without failing the caller`() {
		val release = CountDownLatch(1)
		val worker = worker(properties(allowPrivateNetworks = false, threads = 1, queueSize = 1))
		// The first fetch blocks in name resolution until released, so the single thread stays busy.
		worker.fetcher =
			PageFetcher(properties(allowPrivateNetworks = false)) { _ ->
				release.await(10, TimeUnit.SECONDS)
				listOf(InetAddress.getByName("10.0.0.1"))
			}
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
		verify(exactly = 0) { service.applyMetadata(dropped.id, any(), any(), any()) }
	}

	@Test
	fun `after shutdown requests are dropped without failing the caller`() {
		val worker = worker()
		worker.destroy()

		// Rejected synchronously by the stopped executor: nothing was queued that could still run.
		worker.onRequested(request(page("/a")))

		verify(exactly = 0) { service.applyMetadata(any(), any(), any(), any()) }
	}
}
