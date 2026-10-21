package ch.sonensei.cockpit.calendar.edit

import ch.sonensei.cockpit.calendar.agenda.CalendarResult
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import ch.sonensei.cockpit.calendar.agenda.toOccurrence
import ch.sonensei.cockpit.core.network.generated.apis.CalendarsApi
import ch.sonensei.cockpit.core.network.generated.models.EventResponse
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Event writes with hand-built bodies: the generated request classes send every field, and a PATCH treats `null` as
 * "clear", so a change of the title would wipe the location.
 */
interface EventWriteApi {
    @POST("api/v1/calendar/events")
    suspend fun createEvent(
        @Body body: JsonObject,
    ): Response<EventResponse>

    @PATCH("api/v1/calendar/events/{id}")
    suspend fun updateEvent(
        @Path("id") id: UUID,
        @Body patch: JsonObject,
        @Query("scope") scope: String?,
        @Query("occurrence") occurrence: String?,
        @Header("If-Match") ifMatch: String,
    ): Response<EventResponse>
}

@Module
@InstallIn(SingletonComponent::class)
object CalendarEditModule {
    @Provides
    fun eventWriteApi(retrofit: Retrofit): EventWriteApi = retrofit.create(EventWriteApi::class.java)
}

/** An event as the form needs it. */
data class EventDetails(
    val id: UUID,
    val calendarId: UUID,
    val title: String,
    val location: String?,
    val description: String?,
    val allDay: Boolean,
    val start: Instant?,
    val end: Instant?,
    val startDate: LocalDate?,
    /** Exclusive, as the API has it. */
    val endDate: LocalDate?,
    /** IANA zone id a timed event was planned in. */
    val timeZone: String?,
    /** The rule of a series master. */
    val rrule: String?,
    val seriesId: UUID?,
    /** Sent back as `If-Match`. */
    val version: Long,
)

/** Whether [this] occurrence belongs to a repeating series (a change needs a scope). */
val Occurrence.inSeries: Boolean get() = seriesId != null && occurrence != null

/** Whether [this] occurrence comes from the rule of its series master (no override replaces it yet). */
val Occurrence.generated: Boolean get() = inSeries && eventId == seriesId

/** Events read and written on the server (SPEC.md §6.2, §6.3). */
@Singleton
class EventEditRepository
    @Inject
    constructor(
        private val api: CalendarsApi,
        private val writeApi: EventWriteApi,
    ) {
        suspend fun event(id: UUID): CalendarResult<EventDetails> = call { body(api.getEvent(id)).toDetails() }

        /**
         * The current state of the generated [occurrence] (it may have become an override since), or `null` when its
         * series no longer has it (moved, split, or this occurrence was removed elsewhere).
         */
        suspend fun reread(occurrence: Occurrence): CalendarResult<Occurrence?> =
            call {
                val (from, to) = rangeAround(requireNotNull(occurrence.occurrence))
                body(api.listEventOccurrences(from = from, to = to, calendarIds = listOf(occurrence.calendarId)))
                    .items
                    .map { it.toOccurrence() }
                    .firstOrNull { it.seriesId == occurrence.seriesId && it.occurrence == occurrence.occurrence }
                    ?.takeUnless { it.cancelled }
            }

        /** Creates the event of [body] (see [createBody]). */
        suspend fun create(body: JsonObject): CalendarResult<EventDetails> = call { body(writeApi.createEvent(body)).toDetails() }

        /**
         * Changes [occurrence] with [patch] (see [patchBody]), sent to the event holding it with [version] as `If-Match`:
         * a single event or an override directly (an override with [scope]: it stands for its occurrence), a generated
         * occurrence to its series master with [scope] and the occurrence's original start.
         */
        suspend fun update(
            occurrence: Occurrence,
            version: Long,
            scope: EditScope?,
            patch: JsonObject,
        ): CalendarResult<EventDetails> =
            call {
                val effective = if (occurrence.inSeries) scope ?: EditScope.THIS else null
                body(
                    writeApi.updateEvent(
                        id = occurrence.eventId,
                        patch = patch,
                        scope = effective?.name,
                        occurrence = occurrence.occurrence.takeIf { occurrence.generated },
                        ifMatch = "\"$version\"",
                    ),
                ).toDetails()
            }

        private suspend fun <T> call(block: suspend () -> T): CalendarResult<T> =
            try {
                CalendarResult.Ok(block())
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                CalendarResult.Offline
            } catch (e: EventHttpFailure) {
                if (e.status >= SERVER_ERROR) CalendarResult.Offline else CalendarResult.Failed(e.status)
            } catch (_: SerializationException) {
                CalendarResult.Failed(null)
            } catch (_: IllegalArgumentException) {
                CalendarResult.Failed(null)
            } catch (_: DateTimeException) {
                CalendarResult.Failed(null)
            }

        private companion object {
            const val SERVER_ERROR = 500
        }
    }

/** A non-2xx answer; its message carries only the status, never the body. */
private class EventHttpFailure(
    val status: Int,
) : Exception("HTTP $status")

private fun <T> body(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) throw EventHttpFailure(response.code())
    return body
}

/** Length of an all-day occurrence's original start (`yyyy-MM-dd`). */
private const val DATE_LENGTH = 10

/**
 * A range (in UTC, as the occurrence list takes it) sure to contain the occurrence originally starting at [original]:
 * an instant ±1 day, or for an all-day series the date counted by the days of the user's zone (at most a day off UTC).
 */
internal fun rangeAround(original: String): Pair<OffsetDateTime, OffsetDateTime> {
    if (original.length == DATE_LENGTH) {
        val day = LocalDate.parse(original).atStartOfDay().atOffset(ZoneOffset.UTC)
        return day.minusDays(1) to day.plusDays(2)
    }
    val at = OffsetDateTime.parse(original).withOffsetSameInstant(ZoneOffset.UTC)
    return at.minusDays(1) to at.plusDays(1)
}

internal fun EventResponse.toDetails() =
    EventDetails(
        id = id,
        calendarId = calendarId,
        title = title,
        location = location,
        description = description,
        allDay = allDay,
        start = start?.toInstant(),
        end = end?.toInstant(),
        startDate = startDate,
        endDate = endDate,
        timeZone = timeZone,
        rrule = rrule,
        seriesId = seriesId,
        version = version,
    )
