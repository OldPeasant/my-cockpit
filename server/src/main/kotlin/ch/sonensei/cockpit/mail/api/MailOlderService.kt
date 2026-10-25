package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.sync.MailHeaderSyncService
import ch.sonensei.cockpit.mail.sync.OlderMessagesResult
import ch.sonensei.cockpit.mail.sync.RemoteFolderNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Older mail on demand (SPEC.md §7.2): loads messages past the sync window into the cache ([MailHeaderSyncService.loadOlder])
 * and tells folder lists whether there is more to load.
 *
 * Whether the server has older messages is only known after asking it: a folder counts as complete once a load reported
 * nothing more, for the UIDVALIDITY it had then (a resync caches only the window again). This is kept in memory, so after
 * a restart a folder offers older mail until the next load finds none.
 */
@Service
class MailOlderService(
	private val sync: MailHeaderSyncService,
	private val folders: MailFolderRepository,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/** UIDVALIDITY per folder at which a load found nothing older. */
	private val complete = ConcurrentHashMap<UUID, Long>()

	/**
	 * Loads up to [limit] older messages of a folder into the cache.
	 *
	 * @throws NotFoundException for an unknown folder, or one that no longer exists on the server
	 * @throws ch.sonensei.cockpit.mail.imap.MailConnectionException when the server cannot be reached
	 */
	fun loadOlder(
		folderId: UUID,
		limit: Int,
	): OlderMessagesResult {
		val result =
			try {
				sync.loadOlder(folderId, limit)
			} catch (e: RemoteFolderNotFoundException) {
				log.debug("Mail folder {} is gone from the server", folderId, e)
				throw NotFoundException("Mail folder not found on the server")
			}
		val validity = folders.findById(folderId).orElse(null)?.uidValidity
		if (result.hasMore || validity == null) complete.remove(folderId) else complete[folderId] = validity
		return result
	}

	/** The server may have messages of the folder that are not cached: it is selectable and no load found it complete. */
	fun hasOlder(folderId: UUID): Boolean {
		val folder = folders.findById(folderId).orElse(null) ?: return false
		return folder.selectable && (folder.uidValidity == null || complete[folderId] != folder.uidValidity)
	}

	companion object {
		const val DEFAULT_LIMIT = MailHeaderSyncService.DEFAULT_OLDER_LIMIT
		const val MAX_LIMIT = 200
	}
}
