package ch.sonensei.cockpit.mail.api

import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.imap.ImapConnectionPool
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailBodyCache
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import jakarta.mail.Folder
import jakarta.mail.FolderNotFoundException
import jakarta.mail.MessagingException
import jakarta.mail.Part
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPMessage
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.util.UUID

/**
 * The attachment metadata cached next to a body (`mail_body_attachments`); a row only exists while its body is in
 * the [MailBodyCache] (it is evicted with it).
 */
@Component
class MailBodyAttachmentStore(
	private val jdbc: JdbcTemplate,
	private val json: JsonMapper,
) {
	fun find(messageId: UUID): List<MailAttachment>? =
		jdbc
			.query("select attachments::text from mail_body_attachments where message_id = ?", { rs, _ -> rs.getString(1) }, messageId)
			.firstOrNull()
			?.let { json.readValue(it, ATTACHMENTS) }

	/** Needs the body of [messageId] in the cache (foreign key). */
	fun save(
		messageId: UUID,
		attachments: List<MailAttachment>,
	) {
		jdbc.update(
			"""
			insert into mail_body_attachments (message_id, attachments) values (?, ?::jsonb)
			on conflict (message_id) do update set attachments = excluded.attachments
			""".trimIndent(),
			messageId,
			json.writeValueAsString(attachments),
		)
	}

	private companion object {
		val ATTACHMENTS = object : TypeReference<List<MailAttachment>>() {}
	}
}

/**
 * Loads message content (SPEC.md §7.2): from the [MailBodyCache] when it is there, else from the IMAP server (only the
 * text parts are downloaded) and then cached. Folders are opened read-only (`EXAMINE`) and parts fetched with
 * `BODY.PEEK`, so reading never marks a message as seen on the server.
 */
@Component
class MailContentLoader(
	private val pool: ImapConnectionPool,
	private val accounts: MailAccountRepository,
	private val folders: MailFolderRepository,
	private val cache: MailBodyCache,
	private val attachments: MailBodyAttachmentStore,
	private val transactions: TransactionTemplate,
	private val properties: MailReadProperties,
) {
	/** The content of a located [message]; fetched from the server on a cache miss. */
	fun load(message: MailMessage): MailContent {
		cached(message.id)?.let { return it }
		val content = withRemoteMessage(message) { MimeContent.read(it, properties.maxTextSize.toBytes().toInt()) }
		transactions.executeWithoutResult {
			cache.put(message.id, content.textPlain, content.textHtml)
			attachments.save(message.id, content.attachments)
		}
		return content
	}

	private fun cached(messageId: UUID): MailContent? =
		transactions.execute {
			val body = cache.get(messageId) ?: return@execute null
			val parts = attachments.find(messageId) ?: return@execute null
			MailContent(body.textPlain, body.textHtml, parts)
		}

	/**
	 * Runs [block] on the server copy of a located [message], in its folder opened read-only.
	 *
	 * @throws NotFoundException when the folder or the message is no longer on the server
	 * @throws MailConnectionException when the server cannot be reached or fails
	 */
	fun <T> withRemoteMessage(
		message: MailMessage,
		block: (Part) -> T,
	): T {
		val uid = message.uid ?: throw NotFoundException(GONE)
		val folder = message.folderId?.let { folders.findById(it).orElse(null) } ?: throw NotFoundException(GONE)
		val account = accounts.findById(message.accountId).orElse(null) ?: throw NotFoundException(GONE)
		return pool.withStore(account) { store ->
			val remote = store.getFolder(folder.path) as IMAPFolder
			try {
				remote.open(Folder.READ_ONLY)
			} catch (e: FolderNotFoundException) {
				throw NotFoundException(GONE)
			} catch (e: MessagingException) {
				throw MailConnectionException.of(e)
			}
			try {
				if (folder.uidValidity != null && remote.uidValidity != folder.uidValidity) throw NotFoundException(GONE)
				val found = remote.getMessageByUID(uid) as IMAPMessage? ?: throw NotFoundException(GONE)
				found.peek = true
				block(found)
			} catch (e: MessagingException) {
				throw MailConnectionException.of(e)
			} catch (e: IOException) {
				throw MailConnectionException.of(e)
			} finally {
				runCatching { if (remote.isOpen) remote.close(false) }
			}
		}
	}

	private companion object {
		const val GONE = "The message is no longer on the server"
	}
}
