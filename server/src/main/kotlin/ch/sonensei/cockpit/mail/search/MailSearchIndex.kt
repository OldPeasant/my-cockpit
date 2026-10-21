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
	 * Repairs what the hooks cannot see, in batches of [batchSize]: documents of messages that are gone or detached
	 * (e.g. removed with their account through the foreign key) are removed, located messages without a document (e.g.
	 * cached before mail search existed) are indexed. Returns the number of documents removed and added.
	 */
	fun reconcile(batchSize: Int): Pair<Int, Int> {
		val removed =
			jdbc.update(
				"""
				delete from search_document d
				where d.item_type = :type
				  and not exists (select 1 from mail_message m where m.id = d.item_id and m.uid is not null)
				""".trimIndent(),
				mapOf("type" to ItemType.EMAIL.name),
			)
		val missing =
			jdbc
				.queryForList(
					"""
					select m.id from mail_message m
					where m.uid is not null
					  and not exists (select 1 from search_document d where d.item_type = :type and d.item_id = m.id)
					order by m.id
					limit :limit
					""".trimIndent(),
					mapOf("type" to ItemType.EMAIL.name, "limit" to batchSize),
					UUID::class.java,
				).filterNotNull()
		messages.findAllById(missing).forEach { index(it) }
		return removed to missing.size
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

	private companion object {
		val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
	}
}
