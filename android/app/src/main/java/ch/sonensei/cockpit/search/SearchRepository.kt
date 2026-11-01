package ch.sonensei.cockpit.search

import ch.sonensei.cockpit.core.network.generated.apis.SearchApi
import ch.sonensei.cockpit.core.network.generated.models.SearchResponse
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.Retrofit
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SearchModule {
    @Provides
    fun searchApi(retrofit: Retrofit): SearchApi = retrofit.create(SearchApi::class.java)
}

/** One found item. [type] is the server's item type (`TASK`, `NOTE`, ...); [deepLink] is its web route, e.g. `/tasks/{id}`. */
data class SearchHit(
    val id: UUID,
    val type: String,
    val title: String,
    val subtitle: String?,
    val deepLink: String?,
)

/** The hits of one item type, best first. */
data class SearchGroup(
    val type: String,
    val hits: List<SearchHit>,
)

/** The outcome of a search. */
sealed interface SearchResult {
    data class Ok(
        val groups: List<SearchGroup>,
    ) : SearchResult

    /** No connection (or the server is down). */
    data object Offline : SearchResult

    /** The server refused it; [status] is `null` for an unreadable answer. */
    data class Failed(
        val status: Int?,
    ) : SearchResult
}

/** Global search (SPEC.md §4.4) over `GET /api/v1/search`: no local cache. */
@Singleton
class SearchRepository
    @Inject
    constructor(
        private val api: SearchApi,
    ) {
        /** Items matching [query] (trimmed, at most [MAX_QUERY_LENGTH] characters) of [types] (empty: all types). */
        suspend fun search(
            query: String,
            types: Set<SearchFilter>,
        ): SearchResult {
            val q = query.trim().take(MAX_QUERY_LENGTH)
            if (q.isEmpty()) return SearchResult.Ok(emptyList())
            val typeParam =
                types
                    .flatMap { it.itemTypes }
                    .distinct()
                    .sorted()
                    .takeIf { it.isNotEmpty() }
            return try {
                val response = api.search(q = q, types = typeParam, limit = LIMIT)
                val body = response.body()
                when {
                    response.code() >= SERVER_ERROR -> SearchResult.Offline
                    !response.isSuccessful || body == null -> SearchResult.Failed(response.code())
                    else -> SearchResult.Ok(body.toGroups())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                SearchResult.Offline
            } catch (_: SerializationException) {
                SearchResult.Failed(null)
            } catch (_: IllegalArgumentException) {
                SearchResult.Failed(null)
            }
        }

        internal companion object {
            const val MAX_QUERY_LENGTH = 200
            const val LIMIT = 50
            const val SERVER_ERROR = 500
        }
    }

internal fun SearchResponse.toGroups(): List<SearchGroup> =
    groups
        .map { group ->
            SearchGroup(
                type = group.type.value,
                hits =
                    group.items.map {
                        SearchHit(
                            id = it.id,
                            type = it.type.value,
                            title = it.title,
                            subtitle = it.subtitle?.takeIf(String::isNotBlank),
                            deepLink = it.deepLink?.takeIf(String::isNotBlank),
                        )
                    },
            )
        }.filter { it.hits.isNotEmpty() }
