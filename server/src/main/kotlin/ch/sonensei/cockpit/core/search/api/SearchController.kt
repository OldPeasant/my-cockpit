package ch.sonensei.cockpit.core.search.api

import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.search.domain.SearchGroup
import ch.sonensei.cockpit.core.search.domain.SearchResult
import ch.sonensei.cockpit.core.search.domain.SearchService
import ch.sonensei.cockpit.core.web.InvalidRequestException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** One search across all modules (SPEC.md §4.4), for the command palette and the search page. */
@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "Search", description = "Global search across all item types")
class SearchController(
	private val search: SearchService,
) {
	@GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
	@Operation(
		operationId = "search",
		summary = "Search all items",
		description =
			"Every word of `q` must match the start of a word of an item's title or text (exact or English-stemmed " +
				"form); hits rank by relevance (title before text) and recency. When that finds fewer than `limit` " +
				"items, items whose title is similar to `q` (typos, fragments) fill up the rest. Results are grouped " +
				"by type, groups ordered by their best hit; `limit` counts items across all groups. Items in the " +
				"Trash are not found. A query without letters or digits finds nothing.",
	)
	@ApiResponse(responseCode = "200", description = "The matching items, grouped by type")
	@ApiResponse(
		responseCode = "400",
		description = "Blank or too long `q`, unknown type, or `limit` out of range",
		content = [Content(mediaType = PROBLEM_JSON, schema = Schema(ref = "#/components/schemas/ProblemDetail"))],
	)
	fun search(
		@Parameter(
			description = "Search text",
			schema = Schema(type = "string", minLength = 1, maxLength = SearchService.MAX_QUERY_LENGTH),
		)
		@RequestParam
		q: String,
		@Parameter(description = "Only these item types (comma-separated or repeated); all types when absent")
		@RequestParam(required = false)
		types: List<ItemType>?,
		@Parameter(
			description = "Maximum number of items, 1..${SearchService.MAX_LIMIT}",
			schema =
				Schema(
					type = "integer",
					format = "int32",
					defaultValue = "${SearchService.DEFAULT_LIMIT}",
					minimum = "1",
					maximum = "${SearchService.MAX_LIMIT}",
				),
		)
		@RequestParam(required = false)
		limit: Int?,
	): SearchResponse {
		val query = q.trim()
		if (query.isEmpty()) throw InvalidRequestException("q", "must not be blank")
		if (query.length > SearchService.MAX_QUERY_LENGTH) {
			throw InvalidRequestException("q", "must be at most ${SearchService.MAX_QUERY_LENGTH} characters")
		}
		val effectiveLimit = limit ?: SearchService.DEFAULT_LIMIT
		if (effectiveLimit !in 1..SearchService.MAX_LIMIT) {
			throw InvalidRequestException("limit", "must be between 1 and ${SearchService.MAX_LIMIT}")
		}
		val groups = search.search(query, types.orEmpty().toSet(), effectiveLimit)
		return SearchResponse(groups.map { it.toResponse() })
	}
}

data class SearchResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val groups: List<SearchGroupResponse>,
)

data class SearchGroupResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val type: ItemType,
	@field:Schema(description = "Best first", requiredMode = RequiredMode.REQUIRED)
	val items: List<SearchItemResponse>,
)

@Schema(description = "A found item. Without a resolver for its type (module not built yet) subtitle, icon and deepLink are missing.")
data class SearchItemResponse(
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val type: ItemType,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val id: UUID,
	@field:Schema(requiredMode = RequiredMode.REQUIRED)
	val title: String,
	val subtitle: String? = null,
	@field:Schema(description = "Material Symbols icon name")
	val icon: String? = null,
	@field:Schema(description = "Client route of the item's detail view, e.g. /tasks/{id}")
	val deepLink: String? = null,
	@field:Schema(description = "When the item was last indexed (changed)", requiredMode = RequiredMode.REQUIRED)
	val updatedAt: Instant,
)

private fun SearchGroup.toResponse() = SearchGroupResponse(type, results.map { it.toResponse() })

private fun SearchResult.toResponse() =
	SearchItemResponse(
		type = hit.item.type,
		id = hit.item.id,
		title = resolved?.title ?: hit.title,
		subtitle = resolved?.subtitle,
		icon = resolved?.icon,
		deepLink = resolved?.deepLink,
		updatedAt = hit.updatedAt,
	)

private const val PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON_VALUE
