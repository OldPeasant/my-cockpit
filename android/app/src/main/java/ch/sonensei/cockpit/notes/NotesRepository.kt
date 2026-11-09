package ch.sonensei.cockpit.notes

import ch.sonensei.cockpit.core.network.generated.apis.NotesApi
import ch.sonensei.cockpit.core.network.generated.apis.SearchApi
import ch.sonensei.cockpit.core.network.generated.models.NotePageResponse
import ch.sonensei.cockpit.core.network.generated.models.NoteResponse
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.time.DateTimeException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Note writes with hand-built bodies. The generated client sends every field of its request classes, and a `null`
 * there is refused (or, for the title, clears it), so a save of the text alone would touch the pins and tags too.
 */
interface NotesWriteApi {
    @POST("api/v1/notes")
    suspend fun createNote(
        @Body note: JsonObject,
    ): Response<NoteResponse>

    @PATCH("api/v1/notes/{id}")
    suspend fun updateNote(
        @Path("id") id: UUID,
        @Body patch: JsonObject,
        @Header("If-Match") ifMatch: String,
    ): Response<NoteResponse>

    /** The list with `archived` as the server spells it (the generated enum would be sent by its Kotlin name). */
    @GET("api/v1/notes")
    suspend fun listNotes(
        @Query("tag") tag: String?,
        @Query("archived") archived: String,
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int,
    ): Response<NotePageResponse>

    /** The Trash's restore, here so this screen needs no Trash client of its own. */
    @POST("api/v1/trash/NOTE/{id}/restore")
    suspend fun restoreNote(
        @Path("id") id: UUID,
    ): Response<Unit>
}

@Module
@InstallIn(SingletonComponent::class)
object NotesModule {
    @Provides
    fun notesApi(retrofit: Retrofit): NotesApi = retrofit.create(NotesApi::class.java)

    @Provides
    fun notesWriteApi(retrofit: Retrofit): NotesWriteApi = retrofit.create(NotesWriteApi::class.java)
}

/** The outcome of a load or an upload. */
sealed interface NotesResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : NotesResult<T>

    /** No connection (or the server is down): nothing changed. */
    data object Offline : NotesResult<Nothing>

    /** The server refused it; [status] is `null` for an unreadable answer. */
    data class Failed(
        val status: Int?,
    ) : NotesResult<Nothing>
}

/** The outcome of a save. */
sealed interface SaveResult {
    data class Saved(
        val note: Note,
    ) : SaveResult

    /**
     * The note was changed elsewhere since it was loaded, so the save was refused: [current] is its version on the
     * server now, or `null` when it was deleted (moved to the Trash).
     */
    data class Conflict(
        val current: Note?,
    ) : SaveResult

    data object Offline : SaveResult

    data class Failed(
        val status: Int?,
    ) : SaveResult
}

/** The editable part of a note. */
data class NoteDraft(
    /** Blank: no title (the server derives it from the first line). */
    val title: String,
    val bodyMd: String,
    /** The note's tags, in their order; sent only when they differ from the saved note's (a new note: when any). */
    val tags: List<String> = emptyList(),
)

/** An image picked for a note, already read into memory. */
class NotePhoto(
    val bytes: ByteArray,
    val mime: String,
    val name: String,
)

/** Notes on the server (SPEC.md §8.1): no local cache, every screen loads what it shows. */
@Singleton
class NotesRepository
    @Inject
    constructor(
        private val api: NotesApi,
        private val writeApi: NotesWriteApi,
        private val searchApi: SearchApi,
    ) {
        /**
         * Every note not in the Trash (with [tag], if given) that is not archived, or only the archived ones
         * ([archived]), pinned first.
         */
        suspend fun list(
            tag: String?,
            archived: Boolean = false,
        ): NotesResult<List<Note>> =
            load {
                val all = mutableListOf<Note>()
                var cursor: String? = null
                for (page in 1..MAX_PAGES) {
                    val response = body(writeApi.listNotes(tag = tag, archived = archived.toString(), cursor = cursor, limit = PAGE_SIZE))
                    response.items.mapTo(all) { it.toNote() }
                    cursor = response.nextCursor ?: break
                }
                all.distinctBy { it.id }.sortedWith(noteOrder)
            }

        suspend fun tags(): NotesResult<List<NoteTag>> = load { body(api.listNoteTags()).items.map { NoteTag(it.tag, it.count) } }

        suspend fun get(id: UUID): NotesResult<Note> = load { body(api.getNote(id)).toNote() }

        /**
         * Saves [draft] as a new note ([base] `null`) or over [base]. A refusal because [base] is outdated (`409`,
         * `412`) or gone (`404`) is a [SaveResult.Conflict] with the server's current note.
         */
        suspend fun save(
            base: Note?,
            draft: NoteDraft,
        ): SaveResult {
            val fields =
                buildMap {
                    val title = draft.title.trim()
                    put("title", if (title.isEmpty()) JsonNull else JsonPrimitive(title))
                    put("bodyMd", JsonPrimitive(draft.bodyMd))
                    if (draft.tags != (base?.tags ?: emptyList<String>())) put("tags", JsonArray(draft.tags.map(::JsonPrimitive)))
                }
            val result =
                load {
                    if (base == null) {
                        body(writeApi.createNote(JsonObject(fields.filterValues { it != JsonNull })))
                    } else {
                        body(writeApi.updateNote(base.id, JsonObject(fields), etag(base.version)))
                    }.toNote()
                }
            return when (result) {
                is NotesResult.Ok -> SaveResult.Saved(result.value)
                NotesResult.Offline -> SaveResult.Offline
                is NotesResult.Failed ->
                    if (base != null && result.status in CONFLICTS) conflict(base.id) else SaveResult.Failed(result.status)
            }
        }

        /**
         * The ids of the notes whose title or body matches [query] (the server's search, SPEC.md §8.1), best first, at
         * most [SEARCH_LIMIT]. Archived notes are found too; notes in the Trash are not.
         */
        suspend fun search(query: String): NotesResult<List<UUID>> {
            val q = query.trim().take(MAX_QUERY)
            if (q.isEmpty()) return NotesResult.Ok(emptyList())
            return load {
                body(searchApi.search(q = q, types = listOf(NOTE_TYPE), limit = SEARCH_LIMIT))
                    .groups
                    .flatMap { group -> group.items.filter { it.type.value == NOTE_TYPE } }
                    .map { it.id }
                    .distinct()
            }
        }

        /**
         * Pins or archives [base] (a `null` flag stays as it is). A refusal because [base] is outdated or gone is a
         * [SaveResult.Conflict], as for [save].
         */
        suspend fun setFlags(
            base: Note,
            pinned: Boolean? = null,
            archived: Boolean? = null,
        ): SaveResult {
            val fields =
                buildMap {
                    pinned?.let { put("pinned", JsonPrimitive(it)) }
                    archived?.let { put("archived", JsonPrimitive(it)) }
                }
            val result = load { body(writeApi.updateNote(base.id, JsonObject(fields), etag(base.version))).toNote() }
            return when (result) {
                is NotesResult.Ok -> SaveResult.Saved(result.value)
                NotesResult.Offline -> SaveResult.Offline
                is NotesResult.Failed -> if (result.status in CONFLICTS) conflict(base.id) else SaveResult.Failed(result.status)
            }
        }

        /** The kept earlier versions of note [id] (at most 20, SPEC.md §8.1), newest first. */
        suspend fun revisions(id: UUID): NotesResult<List<NoteRevision>> =
            load {
                body(api.listNoteRevisions(id))
                    .items
                    .map { NoteRevision(it.number, it.title, it.bodyMd, it.savedAt.toInstant()) }
                    .sortedByDescending { it.number }
            }

        /**
         * Sets the title and body of [base] to those of revision [number] (tags, pin and archive state stay; the
         * replaced content becomes a revision itself). A refusal because [base] is outdated or gone is a
         * [SaveResult.Conflict], as for [save].
         */
        suspend fun restoreRevision(
            base: Note,
            number: Int,
        ): SaveResult {
            val result = load { body(api.restoreNoteRevision(base.id, number, etag(base.version))).toNote() }
            return when (result) {
                is NotesResult.Ok -> SaveResult.Saved(result.value)
                NotesResult.Offline -> SaveResult.Offline
                is NotesResult.Failed -> if (result.status in CONFLICTS) conflict(base.id) else SaveResult.Failed(result.status)
            }
        }

        /** Moves [note] to the Trash, if it is still the version shown (`409`/`412`: it changed; `404`: gone already). */
        suspend fun delete(note: Note): NotesResult<Unit> = load { done(api.deleteNote(note.id, etag(note.version))) }

        /** Takes note [id] out of the Trash again (the undo of [delete]). */
        suspend fun restore(id: UUID): NotesResult<Unit> = load { done(writeApi.restoreNote(id)) }

        /** Stores [photo] in `/Attachments/Notes`; returns the Markdown to insert into the note's body. */
        suspend fun uploadImage(
            noteId: UUID,
            photo: NotePhoto,
        ): NotesResult<String> =
            load {
                val part =
                    MultipartBody.Part.createFormData("file", photo.name, photo.bytes.toRequestBody(photo.mime.toMediaTypeOrNull()))
                body(api.uploadNoteImage(noteId, part)).markdown
            }

        private suspend fun conflict(id: UUID): SaveResult =
            when (val current = get(id)) {
                is NotesResult.Ok -> SaveResult.Conflict(current.value)
                NotesResult.Offline -> SaveResult.Offline
                is NotesResult.Failed -> if (current.status == NOT_FOUND) SaveResult.Conflict(null) else SaveResult.Failed(current.status)
            }

        private suspend fun <T> load(block: suspend () -> T): NotesResult<T> =
            try {
                NotesResult.Ok(block())
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                NotesResult.Offline
            } catch (e: NotesHttpFailure) {
                if (e.status >= SERVER_ERROR) NotesResult.Offline else NotesResult.Failed(e.status)
            } catch (_: SerializationException) {
                NotesResult.Failed(null)
            } catch (_: IllegalArgumentException) {
                NotesResult.Failed(null)
            } catch (_: DateTimeException) {
                NotesResult.Failed(null)
            }

        private companion object {
            const val PAGE_SIZE = 200
            const val MAX_PAGES = 50
            const val NOT_FOUND = 404
            const val SERVER_ERROR = 500
            val CONFLICTS = setOf(NOT_FOUND, 409, 412)
            const val NOTE_TYPE = "NOTE"
            const val SEARCH_LIMIT = 50
            const val MAX_QUERY = 200
        }
    }

/** A non-2xx answer; its message carries only the status, never the body (which may quote the note). */
private class NotesHttpFailure(
    val status: Int,
) : Exception("HTTP $status")

private fun <T> body(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) throw NotesHttpFailure(response.code())
    return body
}

private fun done(response: Response<Unit>) {
    if (!response.isSuccessful) throw NotesHttpFailure(response.code())
}
