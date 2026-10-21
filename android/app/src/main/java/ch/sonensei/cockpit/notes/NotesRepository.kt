package ch.sonensei.cockpit.notes

import ch.sonensei.cockpit.core.network.generated.apis.NotesApi
import ch.sonensei.cockpit.core.network.generated.models.NoteResponse
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
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
    ) {
        /** Every note that is neither archived nor in the Trash (with [tag], if given), pinned first. */
        suspend fun list(tag: String?): NotesResult<List<Note>> =
            load {
                val all = mutableListOf<Note>()
                var cursor: String? = null
                for (page in 1..MAX_PAGES) {
                    val response = body(api.listNotes(tag = tag, cursor = cursor, limit = PAGE_SIZE))
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
