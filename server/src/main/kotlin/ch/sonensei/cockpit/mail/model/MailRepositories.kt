package ch.sonensei.cockpit.mail.model

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface MailAccountRepository : JpaRepository<MailAccount, UUID> {
	fun findAllByOrderByDisplayNameAscIdAsc(): List<MailAccount>
}

interface MailIdentityRepository : JpaRepository<MailIdentity, UUID> {
	fun findByAccountIdOrderByPositionAscIdAsc(accountId: UUID): List<MailIdentity>
}

interface MailFolderRepository : JpaRepository<MailFolder, UUID> {
	fun findByAccountIdOrderByPathAsc(accountId: UUID): List<MailFolder>

	fun findByAccountIdAndPath(
		accountId: UUID,
		path: String,
	): MailFolder?

	fun findByAccountIdAndRole(
		accountId: UUID,
		role: MailFolderRole,
	): MailFolder?
}

interface MailMessageRepository : JpaRepository<MailMessage, UUID> {
	fun findByFolderIdAndUid(
		folderId: UUID,
		uid: Long,
	): MailMessage?

	/** Located copies of a message in an account (the same Message-ID can be in several folders), oldest row first. */
	fun findByAccountIdAndMessageIdHeaderAndUidIsNotNullOrderByIdAsc(
		accountId: UUID,
		messageIdHeader: String,
	): List<MailMessage>

	/** Rows of an account with that Message-ID, located or not (e.g. to reattach a linked row on resync). */
	fun findByAccountIdAndMessageIdHeaderOrderByIdAsc(
		accountId: UUID,
		messageIdHeader: String,
	): List<MailMessage>

	fun findByAccountIdAndThreadKeyAndUidIsNotNullOrderByReceivedAtAscIdAsc(
		accountId: UUID,
		threadKey: String,
	): List<MailMessage>
}

interface MailBodyRepository : JpaRepository<MailBody, UUID> {
	fun findByMessageId(messageId: UUID): MailBody?

	@Query("select coalesce(sum(b.sizeBytes), 0) from MailBody b")
	fun totalSizeBytes(): Long

	/** Marks a body as used without bumping its version (reading must not change the entity's ETag). */
	@Modifying
	@Query("update MailBody b set b.lastAccessedAt = :at where b.messageId = :messageId")
	fun touch(
		@Param("messageId") messageId: UUID,
		@Param("at") at: Instant,
	): Int

	/** Stores or replaces the body of a message in one statement, safe against a concurrent store of the same message. */
	@Modifying
	@Query(
		"""
		insert into mail_body (id, created_at, updated_at, version, message_id, text_plain, text_html, size_bytes, last_accessed_at)
		values (:id, :now, :now, 0, :messageId, :textPlain, :textHtml, :sizeBytes, :now)
		on conflict (message_id) do update set
		    text_plain = excluded.text_plain, text_html = excluded.text_html, size_bytes = excluded.size_bytes,
		    updated_at = excluded.updated_at, last_accessed_at = excluded.last_accessed_at, version = mail_body.version + 1
		""",
		nativeQuery = true,
	)
	fun upsert(
		@Param("id") id: UUID,
		@Param("messageId") messageId: UUID,
		@Param("textPlain") textPlain: String?,
		@Param("textHtml") textHtml: String?,
		@Param("sizeBytes") sizeBytes: Long,
		@Param("now") now: Instant,
	): Int

	/**
	 * LRU eviction: keeps the most recently used bodies while their total size fits [maxBytes] and deletes the rest.
	 * The body of message [keep] (the one just stored) counts first and is never deleted, even when it alone exceeds
	 * the budget. Returns the number of deleted bodies.
	 */
	@Modifying
	@Query(
		"""
		delete from mail_body where id in (
		    select id from (
		        select id, message_id, sum(size_bytes) over (
		            order by (message_id = :keep) desc, last_accessed_at desc, id desc
		        ) as kept_bytes
		        from mail_body
		    ) ranked
		    where kept_bytes > :maxBytes and message_id <> :keep
		)
		""",
		nativeQuery = true,
	)
	fun evictBeyond(
		@Param("maxBytes") maxBytes: Long,
		@Param("keep") keep: UUID,
	): Int
}
