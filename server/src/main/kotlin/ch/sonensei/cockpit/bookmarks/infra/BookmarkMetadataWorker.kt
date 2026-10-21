package ch.sonensei.cockpit.bookmarks.infra

import ch.sonensei.cockpit.bookmarks.domain.BookmarkMetadataRequested
import ch.sonensei.cockpit.bookmarks.domain.BookmarkService
import ch.sonensei.cockpit.bookmarks.domain.PageMetadata
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
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
 * Logs name the bookmark id and the reason only, never the URL or page content.
 */
@Component
class BookmarkMetadataWorker(
	private val bookmarks: BookmarkService,
	properties: BookmarkFetchProperties,
) : DisposableBean {
	private val log = LoggerFactory.getLogger(javaClass)

	/** Replaceable in tests (e.g. to simulate name resolution). */
	internal var fetcher = PageFetcher(properties)

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
			log.warn("Bookmark {}: metadata fetch not queued ({})", request.id, e.javaClass.simpleName)
		}
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
		repeat(MAX_ATTEMPTS) { attempt ->
			try {
				bookmarks.applyMetadata(request.id, request.url, request.overwrite, metadata)
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
