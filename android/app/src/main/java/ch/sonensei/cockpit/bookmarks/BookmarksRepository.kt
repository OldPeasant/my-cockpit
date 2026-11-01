package ch.sonensei.cockpit.bookmarks

import ch.sonensei.cockpit.core.network.generated.apis.BookmarksApi
import ch.sonensei.cockpit.core.network.generated.models.BookmarkResponse
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.IOException
import java.time.DateTimeException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bookmark changes with hand-built bodies. The generated client sends every field of `BookmarkPatchRequest`, and a
 * `null` there clears the title and notes or is refused, so marking a bookmark read would touch the rest too.
 */
interface BookmarksWriteApi {
    @PATCH("api/v1/bookmarks/{id}")
    suspend fun updateBookmark(
        @Path("id") id: UUID,
        @Body patch: JsonObject,
        @Header("If-Match") ifMatch: String,
    ): Response<BookmarkResponse>

    /** The Trash's restore, here so this screen needs no Trash client of its own (`BookmarksApi` has none). */
    @POST("api/v1/trash/BOOKMARK/{id}/restore")
    suspend fun restoreBookmark(
        @Path("id") id: UUID,
    ): Response<Unit>
}

@Module
@InstallIn(SingletonComponent::class)
object BookmarksModule {
    // BookmarksApi itself is provided by CaptureQueueModule (the share target's uploader uses it too).
    @Provides
    fun bookmarksWriteApi(retrofit: Retrofit): BookmarksWriteApi = retrofit.create(BookmarksWriteApi::class.java)
}

/** Which bookmarks the list shows. */
enum class BookmarkView {
    /** The read-later queue: unread bookmarks in the read-later list. */
    QUEUE,

    /** Every bookmark. */
    ALL,
}

/** The outcome of a load or a change. */
sealed interface BookmarksResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : BookmarksResult<T>

    /** No connection (or the server is down): nothing changed. */
    data object Offline : BookmarksResult<Nothing>

    /** The server refused it; [status] is `null` for an unreadable answer. */
    data class Failed(
        val status: Int?,
    ) : BookmarksResult<Nothing>
}

/** Bookmarks on the server (SPEC.md §8.4): no local cache, the screen loads what it shows. */
@Singleton
class BookmarksRepository
    @Inject
    constructor(
        private val api: BookmarksApi,
        private val writeApi: BookmarksWriteApi,
    ) {
        /** The bookmarks of [view] (with [tag], if given), newest first. */
        suspend fun list(
            view: BookmarkView,
            tag: String?,
        ): BookmarksResult<List<Bookmark>> =
            load {
                val queue = view == BookmarkView.QUEUE
                val all = mutableListOf<Bookmark>()
                var cursor: String? = null
                for (page in 1..MAX_PAGES) {
                    val response =
                        body(
                            api.listBookmarks(
                                tag = tag,
                                readLater = if (queue) true else null,
                                read = if (queue) false else null,
                                cursor = cursor,
                                limit = PAGE_SIZE,
                            ),
                        )
                    response.items.mapTo(all) { it.toBookmark() }
                    cursor = response.nextCursor ?: break
                }
                all.distinctBy { it.id }.sortedWith(bookmarkOrder)
            }

        suspend fun tags(): BookmarksResult<List<BookmarkTag>> =
            load { body(api.listBookmarkTags()).items.map { BookmarkTag(it.tag, it.count) } }

        suspend fun get(id: UUID): BookmarksResult<Bookmark> = load { body(api.getBookmark(id)).toBookmark() }

        /** Marks [base] as read (`true`, keeping an earlier read time) or unread. */
        suspend fun setRead(
            base: Bookmark,
            read: Boolean,
        ): BookmarksResult<Bookmark> = patch(base, mapOf("read" to JsonPrimitive(read)))

        /** Saves title, tags and notes of [draft] over [base]; a blank title or notes field clears it. */
        suspend fun save(
            base: Bookmark,
            draft: BookmarkDraft,
        ): BookmarksResult<Bookmark> =
            patch(
                base,
                mapOf(
                    "title" to draft.title.trim().orNull(),
                    "description" to draft.notes.trim().orNull(),
                    "tags" to JsonArray(parseTags(draft.tags).map(::JsonPrimitive)),
                ),
            )

        /** Moves [base] to the Trash. */
        suspend fun delete(base: Bookmark): BookmarksResult<Unit> =
            load {
                val response = api.deleteBookmark(base.id, etag(base.version))
                if (!response.isSuccessful) throw BookmarksHttpFailure(response.code())
            }

        /** Takes a bookmark deleted a moment ago out of the Trash again. */
        suspend fun restore(id: UUID): BookmarksResult<Unit> =
            load {
                val response = writeApi.restoreBookmark(id)
                if (!response.isSuccessful) throw BookmarksHttpFailure(response.code())
            }

        private suspend fun patch(
            base: Bookmark,
            fields: Map<String, JsonElement>,
        ): BookmarksResult<Bookmark> = load { body(writeApi.updateBookmark(base.id, JsonObject(fields), etag(base.version))).toBookmark() }

        private suspend fun <T> load(block: suspend () -> T): BookmarksResult<T> =
            try {
                BookmarksResult.Ok(block())
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                BookmarksResult.Offline
            } catch (e: BookmarksHttpFailure) {
                if (e.status >= SERVER_ERROR) BookmarksResult.Offline else BookmarksResult.Failed(e.status)
            } catch (_: SerializationException) {
                BookmarksResult.Failed(null)
            } catch (_: IllegalArgumentException) {
                BookmarksResult.Failed(null)
            } catch (_: DateTimeException) {
                BookmarksResult.Failed(null)
            }

        companion object {
            const val NOT_FOUND = 404
            val CONFLICTS = setOf(409, 412)
            private const val PAGE_SIZE = 200
            private const val MAX_PAGES = 50
            private const val SERVER_ERROR = 500
        }
    }

private fun String.orNull(): JsonElement = if (isEmpty()) JsonNull else JsonPrimitive(this)

/** A non-2xx answer; its message carries only the status, never the body. */
private class BookmarksHttpFailure(
    val status: Int,
) : Exception("HTTP $status")

private fun <T> body(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) throw BookmarksHttpFailure(response.code())
    return body
}
