package ch.sonensei.cockpit.mail.search

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.mail.imap.MailConnectionError
import ch.sonensei.cockpit.mail.imap.MailConnectionException
import ch.sonensei.cockpit.mail.model.MailAccountRepository
import ch.sonensei.cockpit.mail.model.MailFolder
import ch.sonensei.cockpit.mail.model.MailFolderRepository
import ch.sonensei.cockpit.mail.model.MailMessage
import ch.sonensei.cockpit.mail.model.MailMessageRepository
import ch.sonensei.cockpit.mail.sync.MailHeaderSyncService
import ch.sonensei.cockpit.mail.sync.RemoteFolderNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/** Where a mail search looks: a folder, all folders of an account, or all folders of all accounts. */
data class MailSearchScope(
	val accountId: UUID? = null,
	val folderId: UUID? = null,
)

/** An account whose server could not be searched; its folders were skipped. */
data class ServerSearchFailure(
	val accountId: UUID,
	val error: MailConnectionError,
)

/** What the search on the server did. */
data class ServerSearchSummary(
	/** Folders searched on the server. */
	val searchedFolders: Int,
	/** Found messages that were not cached and now are. */
	val loaded: Int,
	/** A folder had more matches than `limit`; only the most recent ones were taken. */
	val truncated: Boolean,
	val failures: List<ServerSearchFailure>,
)

data class MailSearchResult(
	/** Newest (arrival) first. */
	val messages: List<MailMessage>,
	/** More messages match than `limit`. */
	val hasMore: Boolean,
	/** `null` without `server=true`. */
	val server: ServerSearchSummary?,
)

/**
 * Mail search (SPEC.md §7.3): every word of the query must match the start of a word of a cached message's subject,
 * sender or recipients or cached body (the full-text index of [MailSearchIndex]). With `server = true` the folders of
 * the scope are also searched on the mail server (IMAP SEARCH, for mail older than the sync window or whose body is
 * not cached); messages it finds are cached and returned with the local hits. A server that cannot be reached is
 * reported in [ServerSearchSummary.failures] instead of failing the search.
 */
@Service
class MailSearchService(
	private val accounts: MailAccountRepository,
	private val folders: MailFolderRepository,
	private val messages: MailMessageRepository,
	private val sync: MailHeaderSyncService,
	private val jdbc: NamedParameterJdbcTemplate,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	fun search(
		query: String,
		scope: MailSearchScope,
		server: Boolean,
		limit: Int,
	): MailSearchResult {
		val targetFolders = folders(scope)
		val words = words(query)
		if (words.isEmpty()) return MailSearchResult(emptyList(), false, if (server) ServerSearchSummary(0, 0, false, emptyList()) else null)
		var serverIds = emptyList<UUID>()
		val summary =
			if (server) {
				val found = searchServer(targetFolders, words, limit)
				serverIds = found.first
				found.second
			} else {
				null
			}
		val ids = localIds(words, scope, serverIds, limit + 1)
		val byId = messages.findAllById(ids).associateBy { it.id }
		val found = ids.take(limit).mapNotNull { byId[it] }
		return MailSearchResult(found, ids.size > limit, summary)
	}

	/** Validates the scope; the selectable folders it covers (for the server search). */
	private fun folders(scope: MailSearchScope): List<MailFolder> {
		if (scope.folderId != null) {
			val folder = folders.findById(scope.folderId).orElse(null) ?: throw NotFoundException("Mail folder not found")
			if (scope.accountId != null && folder.accountId != scope.accountId) {
				throw InvalidRequestException("folderId", "is not a folder of the account")
			}
			return listOf(folder).filter { it.selectable }
		}
		if (scope.accountId != null) {
			if (!accounts.existsById(scope.accountId)) throw NotFoundException("Mail account not found")
			return folders.findByAccountIdOrderByPathAsc(scope.accountId).filter { it.selectable }
		}
		return accounts.findAllByOrderByDisplayNameAscIdAsc().flatMap { account ->
			folders.findByAccountIdOrderByPathAsc(account.id).filter { it.selectable }
		}
	}

	private fun searchServer(
		targetFolders: List<MailFolder>,
		words: List<String>,
		limit: Int,
	): Pair<List<UUID>, ServerSearchSummary> {
		val ids = mutableListOf<UUID>()
		val failures = linkedMapOf<UUID, MailConnectionError>()
		var searched = 0
		var loaded = 0
		var truncated = false
		for (folder in targetFolders) {
			if (folder.accountId in failures) continue
			try {
				val result = sync.searchServer(folder.id, words, limit)
				ids += result.messageIds
				loaded += result.loaded
				truncated = truncated || result.hasMore
				searched++
			} catch (e: RemoteFolderNotFoundException) {
				// Gone from the server; the next folder list sync removes it.
				log.debug("Mail folder {} is gone from the server, not searched", folder.id, e)
			} catch (e: NotFoundException) {
				// Removed (with its account) while searching.
				log.debug("Mail folder {} was removed, not searched", folder.id, e)
			} catch (e: MailConnectionException) {
				// The cause may hold server responses; keep it at DEBUG.
				log.info("Searching mail account {} on the server failed: {}", folder.accountId, e.error)
				log.debug("Mail server search failure", e.cause)
				failures[folder.accountId] = e.error
			}
		}
		return ids to ServerSearchSummary(searched, loaded, truncated, failures.map { (account, error) -> ServerSearchFailure(account, error) })
	}

	/**
	 * Located messages of the scope that match [words] in the index, or that the server search found ([serverIds]),
	 * newest arrival first.
	 */
	private fun localIds(
		words: List<String>,
		scope: MailSearchScope,
		serverIds: List<UUID>,
		limit: Int,
	): List<UUID> {
		val params =
			MapSqlParameterSource()
				.addValue("type", ItemType.EMAIL.name)
				.addValue("query", words.joinToString(" & ") { "'$it':*" })
				.addValue("limit", limit)
		val conditions = mutableListOf(MailSearchIndex.LOCATED)
		scope.accountId?.let {
			params.addValue("accountId", it)
			conditions += "m.account_id = :accountId"
		}
		scope.folderId?.let {
			params.addValue("folderId", it)
			conditions += "m.folder_id = :folderId"
		}
		val serverMatch =
			if (serverIds.isEmpty()) {
				""
			} else {
				params.addValue("serverIds", serverIds.distinct())
				" or m.id in (:serverIds)"
			}
		val sql =
			"""
			select m.id
			from mail_message m
			left join search_document d on d.item_type = :type and d.item_id = m.id
			where ${conditions.joinToString(" and ")}
			  and (d.tsv @@ (to_tsquery('simple', :query) || to_tsquery('english', :query))$serverMatch)
			order by coalesce(m.received_at, m.created_at) desc, m.id desc
			limit :limit
			""".trimIndent()
		return jdbc.queryForList(sql, params, UUID::class.java).filterNotNull()
	}

	companion object {
		const val DEFAULT_LIMIT = 50
		const val MAX_LIMIT = 200
		const val MAX_QUERY_LENGTH = 200
		private const val MAX_WORDS = 20

		// Letters and digits only: what the tsquery and IMAP SEARCH get is always a plain word, whatever the user typed.
		private val WORD = Regex("""[\p{L}\p{N}]+""")

		fun words(query: String): List<String> =
			WORD
				.findAll(query)
				.map { it.value.lowercase() }
				.distinct()
				.take(MAX_WORDS)
				.toList()
	}
}
