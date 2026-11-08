package ch.sonensei.cockpit.bookmarks

import ch.sonensei.cockpit.core.network.generated.apis.BookmarksApi
import ch.sonensei.cockpit.core.network.generated.models.BookmarkResponse
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
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

    /** A new bookmark; hand-built so fields left empty are not sent (the server fills them from the page). */
    @POST("api/v1/bookmarks")
    suspend fun createBookmark(
        @Body body: JsonObject,
    ): Response<BookmarkResponse>

    /** Fetches the page again and saves a readable copy of it. */
    @POST("api/v1/bookmarks/{id}/snapshot")
    suspend fun saveSnapshot(
        @Path("id") id: UUID,
        @Header("If-Match") ifMatch: String,
    ): Response<BookmarkResponse>

    /** A file's text preview: the readable copy of a page is a file in the Attachments folder. */
    @GET("api/v1/files/{id}/preview")
    suspend fun preview(
        @Path("id") id: UUID,
    ): Response<ResponseBody>

    /** A new task (in the Inbox unless `list` says otherwise); only its `id` is read from the answer. */
    @POST("api/v1/tasks")
    suspend fun createTask(
        @Body body: JsonObject,
    ): Response<JsonObject>

    @POST("api/v1/links")
    suspend fun createLink(
        @Body body: JsonObject,
    ): Response<JsonObject>

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

    /**
     * The server refused it; [status] is `null` for an unreadable answer. [fields] names the request fields a 400's
     * Problem Details found invalid (empty when it named none).
     */
    data class Failed(
        val status: Int?,
        val fields: Set<String> = emptySet(),
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

        /** Puts [base] into the read-later list (`true`) or takes it out. */
        suspend fun setReadLater(
            base: Bookmark,
            readLater: Boolean,
        ): BookmarksResult<Bookmark> = patch(base, mapOf("readLater" to JsonPrimitive(readLater)))

        /** Saves a new bookmark; a blank title is filled from the page by the server. */
        suspend fun create(bookmark: NewBookmark): BookmarksResult<Bookmark> =
            load {
                val fields =
                    buildMap {
                        put("url", JsonPrimitive(bookmark.url.trim()))
                        bookmark.title
                            .trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let { put("title", JsonPrimitive(it)) }
                        val tags = parseTags(bookmark.tags)
                        if (tags.isNotEmpty()) put("tags", JsonArray(tags.map(::JsonPrimitive)))
                        put("readLater", JsonPrimitive(bookmark.readLater))
                    }
                body(writeApi.createBookmark(JsonObject(fields))).toBookmark()
            }

        /** The readable copy stored as file [fileId]. */
        suspend fun snapshot(fileId: UUID): BookmarksResult<BookmarkSnapshot> =
            load {
                val response = writeApi.preview(fileId)
                val text = body(response).use { it.string() }
                BookmarkSnapshot(text, response.headers()["X-Preview-Truncated"].equals("true", ignoreCase = true))
            }

        /**
         * Asks the server to fetch the page of [base] now and save a readable copy of it. Ok with `null` when the
         * server could not fetch the page or found nothing readable in it (its 502, which is not an outage here).
         */
        suspend fun saveSnapshot(base: Bookmark): BookmarksResult<Bookmark?> =
            load {
                val response = writeApi.saveSnapshot(base.id, etag(base.version))
                if (response.code() == BAD_GATEWAY) null else body(response).toBookmark()
            }

        /**
         * Creates an Inbox task for [bookmark] (its title, the address in the notes) and links the bookmark to it as
         * its origin (SPEC.md §4.5). Ok with `false` when the task exists but the link could not be made. [taskId] is
         * the client-generated UUIDv7 of the task: the create is idempotent, so repeating it after a lost answer
         * returns the task made before instead of making a second one.
         */
        suspend fun toInbox(
            bookmark: Bookmark,
            taskId: UUID,
        ): BookmarksResult<Boolean> =
            load {
                val task =
                    body(
                        writeApi.createTask(
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive(taskId.toString()),
                                    "title" to JsonPrimitive(bookmark.displayTitle.take(MAX_TASK_TITLE)),
                                    "notesMd" to JsonPrimitive(bookmark.url),
                                    "list" to JsonPrimitive("INBOX"),
                                ),
                            ),
                        ),
                    )
                val taskId = UUID.fromString(task["id"]?.jsonPrimitive?.content ?: throw SerializationException("task without id"))
                link(taskId, bookmark.id)
            }

        /** Links task [taskId] to bookmark [bookmarkId] as its origin; `false` when that failed. */
        private suspend fun link(
            taskId: UUID,
            bookmarkId: UUID,
        ): Boolean =
            try {
                writeApi
                    .createLink(
                        JsonObject(
                            mapOf(
                                "source" to ref("TASK", taskId),
                                "target" to ref("BOOKMARK", bookmarkId),
                                "label" to JsonPrimitive("origin"),
                            ),
                        ),
                    ).isSuccessful
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                false
            }

        private fun ref(
            type: String,
            id: UUID,
        ) = JsonObject(mapOf("type" to JsonPrimitive(type), "id" to JsonPrimitive(id.toString())))

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
                if (e.status >= SERVER_ERROR) BookmarksResult.Offline else BookmarksResult.Failed(e.status, e.fields)
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
            private const val MAX_TASK_TITLE = 500
            private const val BAD_GATEWAY = 502
        }
    }

private fun String.orNull(): JsonElement = if (isEmpty()) JsonNull else JsonPrimitive(this)

/**
 * A non-2xx answer; its message carries only the status, never the body. [fields] are the invalid fields a 400 named.
 */
private class BookmarksHttpFailure(
    val status: Int,
    val fields: Set<String> = emptySet(),
) : Exception("HTTP $status")

private fun <T> body(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) {
        val fields = if (response.code() == HTTP_BAD_REQUEST) invalidFields(response.errorBody()) else emptySet()
        throw BookmarksHttpFailure(response.code(), fields)
    }
    return body
}

private const val HTTP_BAD_REQUEST = 400

private val problemJson = Json { ignoreUnknownKeys = true }

/** The `field`s of the `errors` of a Problem Details body (empty when it is none or names no field). */
private fun invalidFields(body: ResponseBody?): Set<String> =
    runCatching {
        val text = body?.use { it.string() } ?: return emptySet()
        val errors = problemJson.parseToJsonElement(text).jsonObject["errors"]?.jsonArray ?: return emptySet()
        errors.mapNotNull { it.jsonObject["field"]?.jsonPrimitive?.contentOrNull }.toSet()
    }.getOrDefault(emptySet())
