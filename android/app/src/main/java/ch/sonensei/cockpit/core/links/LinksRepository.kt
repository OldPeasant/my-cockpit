package ch.sonensei.cockpit.core.links

import ch.sonensei.cockpit.core.network.generated.apis.LinksApi
import ch.sonensei.cockpit.core.network.generated.apis.NotesApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.models.CreateLinkRequest
import ch.sonensei.cockpit.core.network.generated.models.CreateNoteRequest
import ch.sonensei.cockpit.core.network.generated.models.CreateTaskRequest
import ch.sonensei.cockpit.core.network.generated.models.ItemRefDto
import ch.sonensei.cockpit.core.network.generated.models.LinkResponse
import ch.sonensei.cockpit.search.SearchRepository
import ch.sonensei.cockpit.search.SearchResult
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.Response
import retrofit2.Retrofit
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LinksModule {
    @Provides
    fun linksApi(retrofit: Retrofit): LinksApi = retrofit.create(LinksApi::class.java)
}

/** An item of any type (SPEC.md §4.3): [type] is the server's item type, e.g. `TASK`. */
data class ItemRef(
    val type: String,
    val id: UUID,
)

/** One link of an item, shown as its other end. */
data class LinkedItem(
    val linkId: UUID,
    val type: String,
    val itemId: UUID,
    val title: String?,
    val subtitle: String?,
    /** The item's web route, e.g. `/tasks/{id}`. */
    val deepLink: String?,
    /** `false` when the item no longer exists (or is in the Trash). */
    val available: Boolean,
    /** Created by hand, so it can be removed here (sync links are removed by editing their source). */
    val manual: Boolean,
)

/** The outcome of a links call. */
sealed interface LinksResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : LinksResult<T>

    /** No connection (or the server is down). */
    data object Offline : LinksResult<Nothing>

    /** The items are linked already (another device was faster). */
    data object Conflict : LinksResult<Nothing>

    /** The server refused it; [status] is `null` for an unreadable answer. */
    data class Failed(
        val status: Int?,
    ) : LinksResult<Nothing>
}

/** What the linked-items panel needs from the server, so its view model can be tested without one. */
interface LinksSource {
    suspend fun list(item: ItemRef): LinksResult<List<LinkedItem>>

    suspend fun link(
        source: ItemRef,
        target: ItemRef,
    ): LinksResult<LinkedItem>

    /** Removes a manual link; one that is gone already counts as removed. */
    suspend fun remove(linkId: UUID): LinksResult<Unit>

    suspend fun search(query: String): SearchResult

    /** Creates a task in the Inbox; the result is its id. */
    suspend fun createTask(title: String): LinksResult<UUID>

    /** Creates a note; the result is its id. */
    suspend fun createNote(title: String): LinksResult<UUID>
}

/** Links between items over `/api/v1/links`, link targets found by the global search (SPEC.md §4.3, §4.4). */
@Singleton
class LinksRepository
    @Inject
    constructor(
        private val links: LinksApi,
        private val tasks: TasksApi,
        private val notes: NotesApi,
        private val searches: SearchRepository,
    ) : LinksSource {
        override suspend fun list(item: ItemRef): LinksResult<List<LinkedItem>> {
            val type = LinksApi.TypeListLinks.entries.firstOrNull { it.value == item.type } ?: return LinksResult.Failed(null)
            return call({ links.listLinks(type, item.id) }) { body -> body.items.map { it.toLinkedItem(item) } }
        }

        override suspend fun link(
            source: ItemRef,
            target: ItemRef,
        ): LinksResult<LinkedItem> {
            val request =
                CreateLinkRequest(
                    source.toDto() ?: return LinksResult.Failed(null),
                    target.toDto() ?: return LinksResult.Failed(null),
                )
            return call({ links.createLink(request) }) { it.toLinkedItem(source) }
        }

        override suspend fun remove(linkId: UUID): LinksResult<Unit> =
            try {
                val code = links.deleteLink(linkId).code()
                when {
                    code >= SERVER_ERROR -> LinksResult.Offline
                    code in SUCCESS || code == NOT_FOUND -> LinksResult.Ok(Unit)
                    else -> LinksResult.Failed(code)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                LinksResult.Offline
            }

        override suspend fun search(query: String): SearchResult = searches.search(query, emptySet())

        override suspend fun createTask(title: String): LinksResult<UUID> =
            call({ tasks.createTask(CreateTaskRequest(title = title)) }) { it.id }

        override suspend fun createNote(title: String): LinksResult<UUID> =
            call({ notes.createNote(CreateNoteRequest(title = title)) }) { it.id }

        private suspend fun <B, T> call(
            request: suspend () -> Response<B>,
            map: (B) -> T,
        ): LinksResult<T> =
            try {
                val response = request()
                val body = response.body()
                when {
                    response.code() >= SERVER_ERROR -> LinksResult.Offline
                    response.code() == CONFLICT -> LinksResult.Conflict
                    !response.isSuccessful -> LinksResult.Failed(response.code())
                    body == null -> LinksResult.Failed(response.code())
                    else -> LinksResult.Ok(map(body))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                LinksResult.Offline
            } catch (_: SerializationException) {
                LinksResult.Failed(null)
            } catch (_: IllegalArgumentException) {
                LinksResult.Failed(null)
            }

        private companion object {
            const val SERVER_ERROR = 500
            const val CONFLICT = 409
            const val NOT_FOUND = 404
            val SUCCESS = 200..299
        }
    }

private fun ItemRef.toDto(): ItemRefDto? =
    ItemRefDto.Type.entries
        .firstOrNull { it.value == type }
        ?.let { ItemRefDto(id, it) }

/** The end of [this] link that is not [item]; the direction is relative to the queried item (a create answer: its source). */
internal fun LinkResponse.toLinkedItem(item: ItemRef): LinkedItem {
    val outgoing =
        direction == LinkResponse.Direction.OUTGOING ||
            (source.type.value == item.type && source.id == item.id && target.id != item.id)
    val other = if (outgoing) target else source
    return LinkedItem(
        linkId = id,
        type = other.type.value,
        itemId = other.id,
        title = other.title?.takeIf { it.isNotBlank() },
        subtitle = other.subtitle?.takeIf { it.isNotBlank() },
        deepLink = other.deepLink?.takeIf { it.isNotBlank() },
        available = other.available,
        manual = origin == LinkResponse.Origin.MANUAL,
    )
}
