package ch.sonensei.cockpit.mail.search

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.mail.model.MailAddress
import ch.sonensei.cockpit.mail.model.MailBodyRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import org.jsoup.Jsoup
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Keeps the `search_document` rows of cached mail (SPEC.md §4.4, §7.3) up to date. Header sync calls [index] for every
 * message it caches (or finds again) and [unindex] when a message leaves the cache or is detached; the content loader
 * calls [index] with the body when it caches one.
 *
 * Title is the subject; body is the sender and recipient names and addresses followed by the message text (the
 * text/plain part, else the text of the HTML part, else the snippet). A body evicted from the body cache stays in
 * the index, so mail once read stays findable. Recency is the arrival (INTERNALDATE), so old mail does not rank as new.
 * Only located messages (in a folder) are searchable: a detached row is no message the user can open.
 */
@Component
@Transactional
class MailSearchIndex(
	private val indexer: SearchIndexer,
	private val bodies: MailBodyRepository,
	private val messages: MailMessageRepository,
	private val jdbc: NamedParameterJdbcTemplate,
) {
	/** (Re)indexes [message] with its cached body, if there is one (without marking that body as recently used). */
	fun index(message: MailMessage) {
		val body = bodies.findByMessageId(message.id)
		index(message, body?.textPlain, body?.textHtml)
	}

	/** (Re)indexes [message] with the body that was just loaded. */
	fun index(
		message: MailMessage,
		textPlain: String?,
		textHtml: String?,
	) {
		if (!message.isLocated) return unindex(message.id)
		indexer.upsert(ItemType.EMAIL, message.id, message.subject.orEmpty(), documentBody(message, textPlain, textHtml), message.receivedAt)
	}

	fun unindex(messageId: UUID) = indexer.delete(ItemType.EMAIL, messageId)

	/**
	 * Removes the documents of the messages in folder [folderId]; called before the folder is deleted from the cache,
	 * which keeps the rows but makes them unlocated (`folder_id` set to `null`).
	 */
	fun unindexFolder(folderId: UUID) {
		jdbc.update(
			"""
			delete from search_document d
			using mail_message m
			where d.item_type = :type and d.item_id = m.id and m.folder_id = :folderId
			""".trimIndent(),
			mapOf("type" to ItemType.EMAIL.name, "folderId" to folderId),
		)
	}

	/**
	 * Repairs what the hooks cannot see: [removeStale], then one batch of [indexMissing]. Returns the number of
	 * documents removed and added.
	 */
	fun reconcile(batchSize: Int): Pair<Int, Int> = removeStale() to indexMissing(batchSize)

	/**
	 * Removes the documents of messages that are gone or not located (e.g. removed with their account through the
	 * foreign key, or left without folder when it was deleted). Returns the number of documents removed.
	 */
	fun removeStale(): Int =
		jdbc.update(
			"""
			delete from search_document d
			where d.item_type = :type
			  and not exists (select 1 from mail_message m where m.id = d.item_id and $LOCATED)
			""".trimIndent(),
			mapOf("type" to ItemType.EMAIL.name),
		)

	/**
	 * Indexes up to [batchSize] located messages without a document (e.g. cached before mail search existed). Returns
	 * the number of documents actually added.
	 */
	fun indexMissing(batchSize: Int): Int {
		val missing =
			jdbc
				.queryForList(
					"""
					select m.id from mail_message m
					where $LOCATED
					  and not exists (select 1 from search_document d where d.item_type = :type and d.item_id = m.id)
					order by m.id
					limit :limit
					""".trimIndent(),
					mapOf("type" to ItemType.EMAIL.name, "limit" to batchSize),
					UUID::class.java,
				).filterNotNull()
		return messages
			.findAllById(missing)
			.onEach { index(it) }
			.count { it.isLocated }
	}

	private fun documentBody(
		message: MailMessage,
		textPlain: String?,
		textHtml: String?,
	): String {
		val people = (message.from + message.to + message.cc + message.bcc).joinToString(" ") { it.searchText() }
		val text =
			textPlain?.takeIf(String::isNotBlank)
				?: textHtml?.takeIf(String::isNotBlank)?.let { Jsoup.parse(it).text() }
				?: message.snippet.orEmpty()
		return "$people\n$text"
	}

	// An address is one word to the full-text parser; its parts are added so `alice`, `example` or the whole address match.
	private fun MailAddress.searchText(): String = listOfNotNull(name, address, address.replace(NON_WORD, " ")).joinToString(" ")

	companion object {
		/** SQL predicate on `mail_message m`: the row is located ([MailMessage.isLocated]), i.e. a message the user can open. */
		const val LOCATED = "m.folder_id is not null and m.uid is not null"

		private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
	}
}
