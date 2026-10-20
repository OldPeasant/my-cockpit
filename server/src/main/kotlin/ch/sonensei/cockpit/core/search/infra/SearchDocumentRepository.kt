package ch.sonensei.cockpit.core.search.infra

import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchHit
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** SQL access to `search_document`. Plain JDBC: the generated `tsv` column and the search operators have no JPA mapping. */
@Repository
class SearchDocumentRepository(
	private val jdbc: NamedParameterJdbcTemplate,
) {
	fun upsert(
		type: ItemType,
		id: UUID,
		title: String,
		body: String,
		updatedAt: Instant,
	) {
		jdbc.update(
			"""insert into search_document (item_type, item_id, title, body, updated_at)
			values (:type, :id, :title, :body, :updatedAt)
			on conflict (item_type, item_id) do update
			set title = excluded.title, body = excluded.body, updated_at = excluded.updated_at""",
			mapOf("type" to type.name, "id" to id, "title" to title, "body" to body, "updatedAt" to Timestamp.from(updatedAt)),
		)
	}

	fun delete(
		type: ItemType,
		id: UUID,
	): Int = jdbc.update("delete from search_document where item_type = :type and item_id = :id", mapOf("type" to type.name, "id" to id))

	/**
	 * Documents matching [tsquery] (to_tsquery syntax) in the 'simple' or the 'english' config, best first: `ts_rank`
	 * times a recency boost from 2 (changed [now]) falling towards 1 (half of that after 30 days).
	 */
	fun fullText(
		tsquery: String,
		types: Collection<String>,
		limit: Int,
		now: Instant,
	): List<SearchHit> =
		jdbc.query(
			"""with q as (select to_tsquery('simple', :query) || to_tsquery('english', :query) as query)
			select d.item_type, d.item_id, d.title, d.updated_at,
			  ts_rank(d.tsv, q.query)
			    * (1 + 1 / (1 + greatest(extract(epoch from (cast(:now as timestamptz) - d.updated_at)), 0) / 2592000.0))
			    as score
			from search_document d, q
			where d.tsv @@ q.query and d.item_type in (:types)
			order by score desc, d.updated_at desc, d.item_id
			limit :limit""",
			mapOf("query" to tsquery, "types" to types, "limit" to limit, "now" to Timestamp.from(now)),
			HIT,
		)

	/** Documents whose title contains a word similar to [text] (`word_similarity` ≥ [threshold]), most similar first. */
	fun similarTitles(
		text: String,
		types: Collection<String>,
		limit: Int,
		threshold: Double,
	): List<SearchHit> {
		// Local to the (read-only) transaction; lets the <% operator use the trigram index with our threshold.
		jdbc.query(
			"select set_config('pg_trgm.word_similarity_threshold', :threshold, true)",
			mapOf("threshold" to threshold.toString()),
		) { _, _ -> }
		return jdbc.query(
			"""select item_type, item_id, title, updated_at
			from search_document
			where :text <% title and item_type in (:types)
			order by word_similarity(:text, title) desc, updated_at desc, item_id
			limit :limit""",
			mapOf("text" to text, "types" to types, "limit" to limit),
			HIT,
		)
	}

	private companion object {
		val HIT =
			RowMapper { rs, _ ->
				SearchHit(
					item = ItemRef(ItemType.valueOf(rs.getString("item_type")), rs.getObject("item_id", UUID::class.java)),
					title = rs.getString("title"),
					updatedAt = rs.getTimestamp("updated_at").toInstant(),
				)
			}
	}
}
