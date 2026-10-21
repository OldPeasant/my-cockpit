package ch.sonensei.cockpit.calendar.agenda

import ch.sonensei.cockpit.core.network.generated.apis.CalendarsApi
import ch.sonensei.cockpit.core.network.generated.models.CalendarResponse
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.Path
import java.io.IOException
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Calendar changes with hand-built bodies: the generated request class sends every field, and a `null` color there
 * would clear the calendar's color when only its visibility changes.
 */
interface CalendarWriteApi {
    @PATCH("api/v1/calendar/calendars/{id}")
    suspend fun updateCalendar(
        @Path("id") id: UUID,
        @Body patch: JsonObject,
        @Header("If-Match") ifMatch: String,
    ): Response<CalendarResponse>
}

@Module
@InstallIn(SingletonComponent::class)
object CalendarAgendaModule {
    @Provides
    fun calendarsApi(retrofit: Retrofit): CalendarsApi = retrofit.create(CalendarsApi::class.java)

    @Provides
    fun calendarWriteApi(retrofit: Retrofit): CalendarWriteApi = retrofit.create(CalendarWriteApi::class.java)
}

/** The outcome of a call. */
sealed interface CalendarResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : CalendarResult<T>

    /** No connection (or the server is down): nothing changed. */
    data object Offline : CalendarResult<Nothing>

    /** The server refused it; [status] is `null` for an unreadable answer. */
    data class Failed(
        val status: Int?,
    ) : CalendarResult<Nothing>
}

/** Calendars and their occurrences on the server (SPEC.md §6.4): no local cache, every view loads what it shows. */
@Singleton
class CalendarRepository
    @Inject
    constructor(
        private val api: CalendarsApi,
        private val writeApi: CalendarWriteApi,
    ) {
        /** Every calendar, ordered by name. */
        suspend fun calendars(): CalendarResult<List<CalendarInfo>> = load { body(api.listCalendars()).items.map { it.toCalendar() } }

        /**
         * The occurrences of the visible calendars on the days [from, to) in [zone], ordered by start. A range longer
         * than the server allows in one call is loaded in parts.
         */
        suspend fun occurrences(
            from: LocalDate,
            to: LocalDate,
            zone: ZoneId,
        ): CalendarResult<List<Occurrence>> =
            load {
                val all = mutableListOf<Occurrence>()
                var partStart = from
                while (partStart < to) {
                    val partEnd = minOf(partStart.plusDays(MAX_RANGE_DAYS), to)
                    // Sent in UTC: an offset like +02:00 would need its '+' escaped in the query.
                    val response =
                        body(
                            api.listEventOccurrences(
                                from = partStart.atStartOfDay(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC),
                                to = partEnd.atStartOfDay(zone).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC),
                            ),
                        )
                    response.items.mapTo(all) { it.toOccurrence() }
                    partStart = partEnd
                }
                // An occurrence overlapping two parts is in both.
                all.distinctBy { it.key }.filterNot { it.cancelled }
            }

        /** Shows or hides [calendar]'s events in the views (on every device); a stale [CalendarInfo.version] is refused. */
        suspend fun setVisible(
            calendar: CalendarInfo,
            visible: Boolean,
        ): CalendarResult<CalendarInfo> =
            load {
                body(
                    writeApi.updateCalendar(calendar.id, JsonObject(mapOf("visible" to JsonPrimitive(visible))), etag(calendar.version)),
                ).toCalendar()
            }

        private suspend fun <T> load(block: suspend () -> T): CalendarResult<T> =
            try {
                CalendarResult.Ok(block())
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                CalendarResult.Offline
            } catch (e: CalendarHttpFailure) {
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

            /** The server serves at most 400 days per call. */
            const val MAX_RANGE_DAYS = 366L
        }
    }

/** A non-2xx answer; its message carries only the status, never the body. */
private class CalendarHttpFailure(
    val status: Int,
) : Exception("HTTP $status")

private fun <T> body(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) throw CalendarHttpFailure(response.code())
    return body
}
