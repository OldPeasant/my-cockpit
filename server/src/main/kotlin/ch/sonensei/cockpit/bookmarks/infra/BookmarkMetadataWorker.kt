package ch.sonensei.cockpit.bookmarks.infra

import ch.sonensei.cockpit.bookmarks.domain.BookmarkMetadataRequested
import ch.sonensei.cockpit.bookmarks.domain.BookmarkService
import ch.sonensei.cockpit.bookmarks.domain.PageMetadata
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Fetches the page metadata of saved bookmarks in the background: after the saving transaction commits, a
 * [BookmarkMetadataRequested] is queued, the page is fetched ([PageFetcher], SSRF protected) and parsed
 * ([PageMetadataParser]), and [BookmarkService.applyMetadata] stores the result (`FAILED` when the page could not be
 * fetched). A concurrent change of the bookmark is retried a few times.
 *
 * No bookmark stays `PENDING` for good: a request that cannot be queued because the queue is full is stored as
 * `FAILED` right away. Requests that stopping the application interrupts, drops from the queue or rejects store
 * nothing; when the application has started again, the fetches of all bookmarks still `PENDING` are queued again.
 *
 * Logs name the bookmark id and the reason only, never the URL or page content.
 */
@Component
class BookmarkMetadataWorker(
	private val bookmarks: BookmarkService,
	properties: BookmarkFetchProperties,
	private val fetcher: PageFetcher = PageFetcher(properties),
) : DisposableBean {
	private val log = LoggerFactory.getLogger(javaClass)

	private val executor =
		ThreadPoolExecutor(
			properties.threads,
			properties.threads,
			0,
			TimeUnit.MILLISECONDS,
			ArrayBlockingQueue(properties.queueSize),
		) { runnable -> Thread(runnable, "bookmark-fetch").apply { isDaemon = true } }

	@TransactionalEventListener
	fun onRequested(request: BookmarkMetadataRequested) {
		try {
			executor.execute { run(request) }
		} catch (e: RejectedExecutionException) {
			// Stopping: leave the bookmark PENDING like the requests dropped from the queue, the next start fetches it.
			if (executor.isShutdown) {
				log.info("Bookmark {}: metadata fetch not queued, the application is stopping", request.id)
				return
			}
			log.warn("Bookmark {}: metadata fetch not queued ({}), stored as failed", request.id, e.javaClass.simpleName)
			store(request, null)
		}
	}

	/** Queues the fetches of the bookmarks still `PENDING`: their requests did not survive the last stop. */
	@EventListener(ApplicationReadyEvent::class)
	fun requeuePending() {
		val pending =
			try {
				bookmarks.pendingMetadataRequests()
			} catch (e: RuntimeException) {
				log.warn("Pending bookmark metadata fetches not loaded ({})", e.javaClass.name)
				return
			}
		if (pending.isNotEmpty()) log.info("Queueing {} pending bookmark metadata fetches", pending.size)
		pending.forEach(::onRequested)
	}

	private fun run(request: BookmarkMetadataRequested) {
		val metadata: PageMetadata? =
			try {
				PageMetadataParser.parse(fetcher.fetch(request.url))
			} catch (e: PageFetchException) {
				log.info("Bookmark {}: metadata not fetched: {}", request.id, e.error.message)
				null
			} catch (e: RuntimeException) {
				log.warn("Bookmark {}: metadata not fetched ({})", request.id, e.javaClass.name)
				null
			}
		// Stopping interrupts running fetches: leave the bookmark PENDING, the next start fetches it again.
		if (executor.isShutdown) return
		store(request, metadata)
	}

	/** Stores the result of [request] ([BookmarkService.applyMetadata]), retrying after a concurrent change. */
	private fun store(
		request: BookmarkMetadataRequested,
		metadata: PageMetadata?,
	) {
		repeat(MAX_ATTEMPTS) { attempt ->
			try {
				bookmarks.applyMetadata(request.id, request.fetch, request.overwrite, metadata)
				return
			} catch (e: OptimisticLockingFailureException) {
				if (attempt == MAX_ATTEMPTS - 1) log.warn("Bookmark {}: metadata not stored, changed concurrently", request.id)
			} catch (e: RuntimeException) {
				log.warn("Bookmark {}: metadata not stored ({})", request.id, e.javaClass.name)
				return
			}
		}
	}

	override fun destroy() {
		executor.shutdownNow()
	}

	private companion object {
		const val MAX_ATTEMPTS = 3
	}
}
