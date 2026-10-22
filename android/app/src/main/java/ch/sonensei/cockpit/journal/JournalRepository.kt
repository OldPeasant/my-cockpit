package ch.sonensei.cockpit.journal

import ch.sonensei.cockpit.core.network.generated.apis.JournalApi
import ch.sonensei.cockpit.core.network.generated.models.JournalEntryRequest
import ch.sonensei.cockpit.core.network.generated.models.JournalEntryResponse
import ch.sonensei.cockpit.notes.NotePhoto
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The journal entry of one day (SPEC.md §8.5). */
data class JournalEntry(
    val id: UUID,
    val date: LocalDate,
    val bodyMd: String,
    /** 1 (bad) to 5 (great); `null` when not set. Kept as is when the text is saved from the phone. */
    val mood: Int?,
    val tags: List<String>,
    val updatedAt: Instant,
    /** The entry's version, sent back as `If-Match` when it is saved. */
    val version: Long,
)

internal fun JournalEntryResponse.toEntry() =
    JournalEntry(
        id = id,
        date = date,
        bodyMd = bodyMd,
        mood = mood,
        tags = tags,
        updatedAt = updatedAt.toInstant(),
        version = version,
    )

@Module
@InstallIn(SingletonComponent::class)
object JournalModule {
    @Provides
    fun journalApi(retrofit: Retrofit): JournalApi = retrofit.create(JournalApi::class.java)
}

/** The outcome of a load or an upload. */
sealed interface JournalResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : JournalResult<T>

    /** No connection (or the server is down): nothing changed. */
    data object Offline : JournalResult<Nothing>

    /** The server refused it; [status] is `null` for an unreadable answer. */
    data class Failed(
        val status: Int?,
    ) : JournalResult<Nothing>
}

/** The outcome of a save. */
sealed interface JournalSaveResult {
    data class Saved(
        val entry: JournalEntry,
    ) : JournalSaveResult

    /**
     * The entry was changed (or created, or deleted) elsewhere since it was loaded, so the save was refused: [current]
     * is the entry on the server now, `null` when the day has none.
     */
    data class Conflict(
        val current: JournalEntry?,
    ) : JournalSaveResult

    data object Offline : JournalSaveResult

    data class Failed(
        val status: Int?,
    ) : JournalSaveResult
}

/** Journal entries on the server: no local cache, the screen loads what it shows. */
@Singleton
class JournalRepository
    @Inject
    constructor(
        private val api: JournalApi,
    ) {
        /** The entry of [date]; `null` when the day has none yet. */
        suspend fun get(date: LocalDate): JournalResult<JournalEntry?> =
            load {
                val response = api.getJournalEntry(date)
                if (response.code() == NOT_FOUND) null else body(response).toEntry()
            }

        /**
         * Saves [bodyMd] as the entry of [date]: creates it ([base] `null`) or replaces [base], keeping its mood and
         * tags. A refusal because the day changed elsewhere (`409`, `412`, or `404` when it was deleted) is a
         * [JournalSaveResult.Conflict] with the server's current entry.
         */
        suspend fun save(
            date: LocalDate,
            base: JournalEntry?,
            bodyMd: String,
        ): JournalSaveResult {
            val request = JournalEntryRequest(bodyMd = bodyMd, mood = base?.mood, tags = base?.tags.orEmpty())
            val result = load { body(api.putJournalEntry(date, request, base?.let { etag(it.version) })).toEntry() }
            return when (result) {
                is JournalResult.Ok -> JournalSaveResult.Saved(result.value)
                JournalResult.Offline -> JournalSaveResult.Offline
                is JournalResult.Failed ->
                    if (result.status in CONFLICTS) conflict(date) else JournalSaveResult.Failed(result.status)
            }
        }

        /** Stores [photo] in `/Attachments/Journal`; returns the Markdown to insert into the entry's body. */
        suspend fun uploadImage(photo: NotePhoto): JournalResult<String> =
            load {
                val part =
                    MultipartBody.Part.createFormData("file", photo.name, photo.bytes.toRequestBody(photo.mime.toMediaTypeOrNull()))
                body(api.uploadJournalImage(part)).markdown
            }

        private suspend fun conflict(date: LocalDate): JournalSaveResult =
            when (val current = get(date)) {
                is JournalResult.Ok -> JournalSaveResult.Conflict(current.value)
                JournalResult.Offline -> JournalSaveResult.Offline
                is JournalResult.Failed -> JournalSaveResult.Failed(current.status)
            }

        private suspend fun <T> load(block: suspend () -> T): JournalResult<T> =
            try {
                JournalResult.Ok(block())
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                JournalResult.Offline
            } catch (e: JournalHttpFailure) {
                if (e.status >= SERVER_ERROR) JournalResult.Offline else JournalResult.Failed(e.status)
            } catch (_: SerializationException) {
                JournalResult.Failed(null)
            } catch (_: IllegalArgumentException) {
                JournalResult.Failed(null)
            } catch (_: DateTimeException) {
                JournalResult.Failed(null)
            }

        private companion object {
            const val NOT_FOUND = 404
            const val SERVER_ERROR = 500
            val CONFLICTS = setOf(NOT_FOUND, 409, 412)
        }
    }

/** The `If-Match` value of [version] (the server's strong ETag). */
internal fun etag(version: Long) = "\"$version\""

/** A non-2xx answer; its message carries only the status, never the body (which may quote the entry). */
private class JournalHttpFailure(
    val status: Int,
) : Exception("HTTP $status")

private fun <T> body(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) throw JournalHttpFailure(response.code())
    return body
}
