package ch.sonensei.cockpit.core.search.domain

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemResolverRegistry
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import ch.sonensei.cockpit.core.search.infra.SearchDocumentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** A document found by [SearchService.search], before it is resolved for display. */
data class SearchHit(
	val item: ItemRef,
	val title: String,
	val updatedAt: Instant,
)

/** A found item with its display data from its module's resolver (`null` when its type has no resolver yet). */
data class SearchResult(
	val hit: SearchHit,
	val resolved: ResolvedItem?,
)

/** The results of one item type, best first. */
data class SearchGroup(
	val type: ItemType,
	val results: List<SearchResult>,
)

/**
 * The global search (SPEC.md §4.4) over the documents that modules maintain with [SearchIndexer].
 *
 * Every word of the query must match the start of a word of the title or body, in its exact ('simple') or stemmed
 * ('english') form. Hits rank by `ts_rank` (title words weigh more than body words) boosted by recency. When that finds
 * fewer than `limit` items (a typo, or a fragment inside a word), titles similar to the query (trigram word
 * similarity) fill up the rest, ranked after the full-text hits.
 */
@Service
@Transactional(readOnly = true)
class SearchService(
	private val documents: SearchDocumentRepository,
	private val resolvers: ItemResolverRegistry,
	private val clock: Clock,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * Up to [limit] items matching [query], of [types] only (all types when empty), grouped by type. Groups are ordered
	 * by their best hit. An item whose resolver says it no longer exists (e.g. it is in the Trash) is left out.
	 */
	fun search(
		query: String,
		types: Set<ItemType>,
		limit: Int,
	): List<SearchGroup> {
		val words =
			WORD
				.findAll(query)
				.map { it.value.lowercase() }
				.take(MAX_WORDS)
				.toList()
		if (words.isEmpty()) return emptyList()
		val typeNames = (types.ifEmpty { ItemType.entries.toSet() }).map { it.name }
		val prefixQuery = words.joinToString(" & ") { "'$it':*" }
		// More candidates than needed: hits the resolver leaves out (e.g. items in the Trash) must not shorten the page.
		val candidates = limit * CANDIDATE_FACTOR
		val fullTextHits = documents.fullText(prefixQuery, typeNames, candidates, clock.instant())
		val results =
			fullTextHits
				.asSequence()
				.mapNotNull(::resolve)
				.take(limit)
				.toMutableList()
		if (results.size < limit) {
			val found = fullTextHits.mapTo(HashSet()) { it.item }
			documents
				.similarTitles(words.joinToString(" "), typeNames, candidates, FUZZY_THRESHOLD)
				.asSequence()
				.filter { it.item !in found }
				.mapNotNull(::resolve)
				.take(limit - results.size)
				.let(results::addAll)
		}
		return results
			.groupBy { it.hit.item.type }
			.map { (type, results) -> SearchGroup(type, results) }
	}

	private fun resolve(hit: SearchHit): SearchResult? {
		val resolver = resolvers.find(hit.item.type) ?: return SearchResult(hit, null)
		return try {
			resolver.resolve(hit.item.id)?.let { SearchResult(hit, it) }
		} catch (e: RuntimeException) {
			// One broken module must not break the search; show the hit with what the index knows.
			log.warn("ItemResolver for {} failed for {}", hit.item.type, hit.item.id, e)
			SearchResult(hit, null)
		}
	}

	companion object {
		const val DEFAULT_LIMIT = 20
		const val MAX_LIMIT = 50
		const val MAX_QUERY_LENGTH = 200
		private const val MAX_WORDS = 20

		/** Candidates fetched per query as a multiple of `limit`, so that hits left out by their resolver get replaced. */
		private const val CANDIDATE_FACTOR = 2

		/** Minimum `word_similarity` of query and title for a fuzzy hit; one typo in a short word stays above it. */
		const val FUZZY_THRESHOLD = 0.4

		// Letters and digits only: what the tsquery gets is always a valid lexeme, whatever the user typed.
		private val WORD = Regex("""[\p{L}\p{N}]+""")
	}
}
