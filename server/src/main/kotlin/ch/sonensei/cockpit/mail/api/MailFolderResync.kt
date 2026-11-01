package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.sync.MailHeaderSyncService
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Syncs a folder in the background when a mail action found its cache stale (messages gone from the server), so the
 * stale rows disappear without waiting for the next poll. Runs through [MailHeaderSyncService], so the folder's sync
 * lock applies; the sync publishes the folder's change event when it removed rows. A request for a folder whose sync is
 * still pending is merged into it. A failing sync is logged and dropped: the next poll repairs the cache.
 */
@Component
class MailFolderResync internal constructor(
	private val sync: MailHeaderSyncService,
	private val executor: ExecutorService,
) {
	@Autowired
	constructor(sync: MailHeaderSyncService) : this(sync, Executors.newVirtualThreadPerTaskExecutor())

	private val log = LoggerFactory.getLogger(javaClass)
	private val pending = ConcurrentHashMap.newKeySet<UUID>()

	/** Starts a sync of [folderId] without waiting for it. */
	fun request(folderId: UUID) {
		if (!pending.add(folderId)) return
		try {
			executor.execute { run(folderId) }
		} catch (_: RejectedExecutionException) {
			pending.remove(folderId)
			log.debug("Resync of mail folder {} dropped: shutting down", folderId)
		}
	}

	private fun run(folderId: UUID) {
		// Removed before the sync starts, so a request made while it runs is not lost.
		pending.remove(folderId)
		try {
			val result = sync.syncFolder(folderId)
			log.debug("Resynced mail folder {} after a stale action: {} removed", folderId, result.removed)
		} catch (e: MailConnectionException) {
			log.info("Resync of mail folder {} after a stale action failed: {}", folderId, e.error)
			log.debug("Resync failure", e.cause)
		} catch (e: Exception) {
			log.warn("Resync of mail folder {} after a stale action failed ({})", folderId, e.javaClass.simpleName)
			log.debug("Resync failure", e)
		}
	}

	@PreDestroy
	fun shutdown() {
		executor.shutdown()
	}
}
