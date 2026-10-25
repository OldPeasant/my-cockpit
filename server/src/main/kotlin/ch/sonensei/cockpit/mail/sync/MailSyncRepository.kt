package ch.sonensei.cockpit.mail.sync

import ch.sonensei.cockpit.mail.model.MailMessage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

/** Message-ID → thread key of a cached message. */
interface ThreadKeyOfMessage {
	val messageIdHeader: String
	val threadKey: String
}

/** The queries of the header sync on cached messages. */
interface MailSyncRepository : JpaRepository<MailMessage, UUID> {
	@Query("select m.uid from MailMessage m where m.folderId = :folderId and m.uid is not null order by m.uid")
	fun locatedUids(
		@Param("folderId") folderId: UUID,
	): List<Long>

	fun findByFolderIdAndUidIn(
		folderId: UUID,
		uids: Collection<Long>,
	): List<MailMessage>

	fun findByFolderIdAndUidIsNotNull(folderId: UUID): List<MailMessage>

	fun findByFolderIdAndUidIsNull(folderId: UUID): List<MailMessage>

	/** Rows of an account with that Message-ID that are not located anywhere (candidates to be found again). */
	fun findByAccountIdAndMessageIdHeaderAndUidIsNullOrderByIdAsc(
		accountId: UUID,
		messageIdHeader: String,
	): List<MailMessage>

	@Query(
		"""select m.messageIdHeader as messageIdHeader, m.threadKey as threadKey from MailMessage m
		where m.accountId = :accountId and m.messageIdHeader in :messageIds and m.threadKey is not null
		order by m.id""",
	)
	fun threadKeys(
		@Param("accountId") accountId: UUID,
		@Param("messageIds") messageIds: Collection<String>,
	): List<ThreadKeyOfMessage>

	@Query("select distinct m.threadKey from MailMessage m where m.accountId = :accountId and m.threadKey in :keys")
	fun existingThreadKeys(
		@Param("accountId") accountId: UUID,
		@Param("keys") keys: Collection<String>,
	): List<String>

	fun findByAccountIdAndThreadKeyIn(
		accountId: UUID,
		threadKeys: Collection<String>,
	): List<MailMessage>
}
