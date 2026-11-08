package ch.sonensei.cockpit.dashboard

import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import ch.sonensei.cockpit.calendar.agenda.toCalendar
import ch.sonensei.cockpit.calendar.agenda.toOccurrence
import ch.sonensei.cockpit.core.network.generated.apis.BookmarksApi
import ch.sonensei.cockpit.core.network.generated.apis.CalendarsApi
import ch.sonensei.cockpit.core.network.generated.apis.HabitsApi
import ch.sonensei.cockpit.core.network.generated.apis.NotesApi
import ch.sonensei.cockpit.core.network.generated.apis.ProjectsApi
import ch.sonensei.cockpit.core.network.generated.apis.SettingsApi
import ch.sonensei.cockpit.core.network.generated.apis.TasksApi
import ch.sonensei.cockpit.core.network.generated.apis.WeeklyReviewApi
import ch.sonensei.cockpit.core.network.generated.models.OccurrenceResponse
import ch.sonensei.cockpit.core.network.generated.models.TaskResponse
import ch.sonensei.cockpit.mail.list.MailApi
import ch.sonensei.cockpit.review.ReviewStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.Response
import java.io.IOException
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The dashboard widgets (SPEC.md §8.7) in their default order; [key] is the id the web stores in the layout setting. */
enum class WidgetId(
    val key: String,
) {
    TODAY("today"),
    INBOX("inbox"),
    UPCOMING("upcoming"),
    WAITING_OVERDUE("waitingOverdue"),
    STALLED_PROJECTS("stalledProjects"),
    WEEKLY_REVIEW("weeklyReview"),
    READ_LATER("readLater"),
    PINNED_NOTES("pinnedNotes"),
}

/** The setting the web stores the layout in (server: `DashboardSettings.DASHBOARD_WIDGETS`). */
const val LAYOUT_SETTING = "dashboardWidgets"
private const val HIDDEN = ":hidden"

/** One widget's place on the dashboard: its position is its index in the layout. */
data class WidgetSlot(
    val id: WidgetId,
    val visible: Boolean,
)

/** The default layout: every widget, shown, in [WidgetId] order. */
val DEFAULT_LAYOUT: List<WidgetSlot> = WidgetId.entries.map { WidgetSlot(it, visible = true) }

/**
 * Every widget in its place, read from the stored layout like the web does: the listed widgets in their order (unknown
 * or repeated ids skipped, `<id>:hidden` hidden), then the widgets missing from it (added later), shown. No layout (or
 * an unreadable one) is [DEFAULT_LAYOUT].
 */
fun parseSlots(stored: JsonElement?): List<WidgetSlot> {
    val byKey = WidgetId.entries.associateBy { it.key }
    val seen = mutableSetOf<WidgetId>()
    val slots = mutableListOf<WidgetSlot>()
    for (entry in stored as? JsonArray ?: emptyList()) {
        val text = (entry as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: continue
        val hidden = text.endsWith(HIDDEN)
        val widget = byKey[text.removeSuffix(HIDDEN)] ?: continue
        if (!seen.add(widget)) continue
        slots += WidgetSlot(widget, visible = !hidden)
    }
    return slots + WidgetId.entries.filter { it !in seen }.map { WidgetSlot(it, visible = true) }
}

/** The shown widgets in their order (see [parseSlots]). */
fun parseLayout(stored: JsonElement?): List<WidgetId> = parseSlots(stored).shown()

/** The setting value of this layout, as the web writes it. */
fun List<WidgetSlot>.toSetting(): JsonArray = JsonArray(map { JsonPrimitive(if (it.visible) it.id.key else it.id.key + HIDDEN) })

/** The ids of the shown widgets, in order. */
fun List<WidgetSlot>.shown(): List<WidgetId> = filter { it.visible }.map { it.id }

/** This layout with [widget] moved to [index] (clamped to the ends). */
fun List<WidgetSlot>.moved(
    widget: WidgetId,
    index: Int,
): List<WidgetSlot> {
    val from = indexOfFirst { it.id == widget }
    if (from < 0) return this
    val result = toMutableList()
    val slot = result.removeAt(from)
    result.add(index.coerceIn(0, result.size), slot)
    return result
}

/** This layout with [widget] shown or hidden. */
fun List<WidgetSlot>.withVisible(
    widget: WidgetId,
    visible: Boolean,
): List<WidgetSlot> = map { if (it.id == widget) it.copy(visible = visible) else it }

/** A number cut at a page size: [more] means there are more than [count]. */
data class Count(
    val count: Int,
    val more: Boolean,
)

/** An item a widget lists; [url] is a bookmark's address, which a tap opens. */
data class DashboardItem(
    val id: UUID,
    val title: String,
    val url: String? = null,
)

/** A task of a widget, with its due date. */
data class DashboardTask(
    val id: UUID,
    val title: String,
    val dueDate: LocalDate?,
)

/**
 * An event occurrence; [start] is `null` for an all-day one, [date] its (first) day. [occurrence] is what a tap opens in
 * the event editor (`null` in tests that do not open it: the tap then opens the calendar).
 */
data class DashboardEvent(
    val key: String,
    val title: String,
    val date: LocalDate,
    val start: Instant?,
    val occurrence: Occurrence? = null,
)

/** What each widget shows. */
sealed interface WidgetData {
    data class Today(
        /** Overdue and due-today tasks, overdue first. */
        val tasks: List<DashboardTask>,
        val events: List<DashboardEvent>,
        val habitsDue: Int,
        val habitsDone: Int,
    ) : WidgetData

    data class Inbox(
        val tasks: Count,
        /** Unread messages in the unified Inbox of all mail accounts. */
        val unreadMail: Count,
    ) : WidgetData

    data class Upcoming(
        val events: List<DashboardEvent>,
    ) : WidgetData

    data class WaitingOverdue(
        val tasks: List<DashboardTask>,
    ) : WidgetData

    data class StalledProjects(
        val projects: List<DashboardItem>,
    ) : WidgetData

    data class WeeklyReview(
        /** `null` before the first completed review. */
        val daysSinceLastReview: Long?,
        /** The step (1 to 8) of the open review, `null` when none is open. */
        val openStep: Int?,
    ) : WidgetData

    data class ReadLater(
        val items: List<DashboardItem>,
        val more: Boolean,
    ) : WidgetData

    data class PinnedNotes(
        val items: List<DashboardItem>,
        val more: Boolean,
    ) : WidgetData
}

/** The outcome of a load. */
sealed interface DashboardResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : DashboardResult<T>

    /** No connection (or the server is down). */
    data object Offline : DashboardResult<Nothing>

    /** The server refused it or answered something unreadable. */
    data object Failed : DashboardResult<Nothing>
}

/**
 * Loads the dashboard from the endpoints the web dashboard uses (server unchanged), each widget on its own so a
 * failing module only breaks its widget. Only the dashboard layout setting is written (by [saveLayout]).
 */
@Singleton
class DashboardRepository(
    private val settings: SettingsApi,
    private val tasks: TasksApi,
    private val projects: ProjectsApi,
    private val calendars: CalendarsApi,
    private val mail: MailApi,
    private val habits: HabitsApi,
    private val reviews: WeeklyReviewApi,
    private val bookmarks: BookmarksApi,
    private val notes: NotesApi,
    /** Its zone is the user's: "today" and the event days are local days. */
    private val clock: Clock,
) {
    @Inject
    constructor(
        settings: SettingsApi,
        tasks: TasksApi,
        projects: ProjectsApi,
        calendars: CalendarsApi,
        mail: MailApi,
        habits: HabitsApi,
        reviews: WeeklyReviewApi,
        bookmarks: BookmarksApi,
        notes: NotesApi,
    ) : this(settings, tasks, projects, calendars, mail, habits, reviews, bookmarks, notes, Clock.systemDefaultZone())

    /** The ETag of the settings last read or written, for If-Match; guarded by [etagLock]. */
    private var settingsEtag: String? = null

    /** Counts the ETags saves returned: a read that started before one of them must not replace it with its older one. */
    private var etagWrites = 0

    private val etagLock = Any()

    private fun etag(): String? = synchronized(etagLock) { settingsEtag }

    /** Every widget in its place, from the user settings the web dashboard writes too. */
    suspend fun layout(): DashboardResult<List<WidgetSlot>> = call { readSettings() }

    /**
     * Stores [layout] in the setting the web uses and returns the stored layout. Only this key is sent, so on a stale
     * ETag (settings changed elsewhere meanwhile) they are read again and the layout is sent once more: the user's latest
     * layout wins.
     */
    suspend fun saveLayout(layout: List<WidgetSlot>): DashboardResult<List<WidgetSlot>> =
        call {
            val change = JsonObject(mapOf(LAYOUT_SETTING to layout.toSetting()))
            var response = settings.updateSettings(change, etag())
            if (response.code() in CONFLICTS) {
                readSettings()
                response = settings.updateSettings(change, etag())
            }
            val stored = body(response)[LAYOUT_SETTING]
            val written = response.headers()["ETag"]
            if (written != null) {
                synchronized(etagLock) {
                    settingsEtag = written
                    etagWrites++
                }
            }
            parseSlots(stored)
        }

    /** Every calendar, for the event editor a tapped event opens. */
    suspend fun calendars(): DashboardResult<List<CalendarInfo>> = call { body(calendars.listCalendars()).items.map { it.toCalendar() } }

    private suspend fun readSettings(): List<WidgetSlot> {
        val writesBefore = synchronized(etagLock) { etagWrites }
        val response = settings.getSettings()
        val stored = body(response)[LAYOUT_SETTING]
        val read = response.headers()["ETag"]
        synchronized(etagLock) { if (etagWrites == writesBefore) settingsEtag = read }
        return parseSlots(stored)
    }

    suspend fun load(widget: WidgetId): DashboardResult<WidgetData> =
        call {
            when (widget) {
                WidgetId.TODAY -> today()
                WidgetId.INBOX -> inbox()
                WidgetId.UPCOMING -> WidgetData.Upcoming(events(ZonedDateTime.now(clock), UPCOMING_DAYS))
                WidgetId.WAITING_OVERDUE ->
                    WidgetData.WaitingOverdue(tasks(TasksApi.ListListTasks.WAITING, TasksApi.DueListTasks.OVERDUE))
                WidgetId.STALLED_PROJECTS ->
                    WidgetData.StalledProjects(
                        body(projects.listProjects(ProjectsApi.StatusListProjects.ACTIVE, stalled = true, limit = PAGE))
                            .items
                            .map { DashboardItem(it.id, it.title) },
                    )
                WidgetId.WEEKLY_REVIEW ->
                    body(reviews.getWeeklyReviewStatus()).let { status ->
                        WidgetData.WeeklyReview(
                            status.daysSinceLastReview,
                            status.current?.let { ReviewStep.valueOf(it.step.value).number },
                        )
                    }
                WidgetId.READ_LATER ->
                    body(bookmarks.listBookmarks(readLater = true, read = false, limit = PAGE)).let { page ->
                        WidgetData.ReadLater(page.items.map { DashboardItem(it.id, it.displayTitle, it.url) }, page.nextCursor != null)
                    }
                WidgetId.PINNED_NOTES ->
                    body(notes.listNotes(pinned = true, limit = PAGE)).let { page ->
                        WidgetData.PinnedNotes(page.items.map { DashboardItem(it.id, it.displayTitle) }, page.nextCursor != null)
                    }
            }
        }

    private suspend fun today(): WidgetData.Today =
        coroutineScope {
            val overdue = async { tasks(due = TasksApi.DueListTasks.OVERDUE) }
            val due = async { tasks(due = TasksApi.DueListTasks.TODAY) }
            val events = async { events(ZonedDateTime.now(clock).toLocalDate().atStartOfDay(clock.zone), 1) }
            val habitsDue = body(habits.listHabits(archived = false)).items.filter { it.stats.dueToday }
            WidgetData.Today(
                tasks = (overdue.await() + due.await()).distinctBy { it.id },
                events = events.await(),
                habitsDue = habitsDue.size,
                habitsDone = habitsDue.count { it.stats.doneToday },
            )
        }

    private suspend fun inbox(): WidgetData.Inbox =
        coroutineScope {
            val inboxTasks = async { body(tasks.listTasks(list = TasksApi.ListListTasks.INBOX, limit = PAGE)) }
            val messages = body(mail.messages(accountId = null, folderId = null, cursor = null, limit = PAGE))
            val unread = messages.items.count { !it.seen }
            val taskPage = inboxTasks.await()
            WidgetData.Inbox(
                tasks = Count(taskPage.items.size, taskPage.nextCursor != null),
                // Only a page with unread mail may have cut some off: never "0+".
                unreadMail = Count(unread, unread > 0 && messages.nextCursor != null),
            )
        }

    private suspend fun tasks(
        list: TasksApi.ListListTasks? = null,
        due: TasksApi.DueListTasks,
    ): List<DashboardTask> = body(tasks.listTasks(list = list, due = due, limit = PAGE)).items.map(TaskResponse::toDashboardTask)

    /** The occurrences from [from] for [days] local days (counted from its midnight), cancelled ones left out. */
    private suspend fun events(
        from: ZonedDateTime,
        days: Long,
    ): List<DashboardEvent> {
        val end = from.toLocalDate().plusDays(days).atStartOfDay(from.zone)
        return body(calendars.listEventOccurrences(from.toOffsetDateTime(), end.toOffsetDateTime()))
            .items
            .filter { it.status != OccurrenceResponse.Status.CANCELLED }
            .map { it.toDashboardEvent() }
    }

    private suspend fun <T> call(block: suspend () -> T): DashboardResult<T> =
        try {
            DashboardResult.Ok(block())
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            DashboardResult.Offline
        } catch (e: DashboardHttpFailure) {
            if (e.status >= SERVER_ERROR) DashboardResult.Offline else DashboardResult.Failed
        } catch (_: SerializationException) {
            DashboardResult.Failed
        } catch (_: IllegalArgumentException) {
            DashboardResult.Failed
        } catch (_: DateTimeException) {
            DashboardResult.Failed
        }

    private fun OccurrenceResponse.toDashboardEvent(): DashboardEvent {
        val date = start?.atZoneSameInstant(clock.zone)?.toLocalDate() ?: startDate ?: throw DateTimeException("no start")
        return DashboardEvent(
            key = "$eventId:${occurrence ?: date}",
            title = title,
            date = date,
            start = if (allDay) null else start?.toInstant(),
            occurrence = toOccurrence(),
        )
    }

    companion object {
        /** Page size of the lists a widget counts: beyond it, the count shows as "N+". */
        const val PAGE = 50

        /** How far ahead the Upcoming events widget looks. */
        const val UPCOMING_DAYS = 7L

        private const val SERVER_ERROR = 500

        /** 409 changed concurrently, 412 stale ETag, 428 no ETag: the settings are read again first. */
        private val CONFLICTS = setOf(409, 412, 428)
    }
}

private fun TaskResponse.toDashboardTask() = DashboardTask(id, title, dueDate)

/** A non-2xx answer; its message carries only the status. */
private class DashboardHttpFailure(
    val status: Int,
) : Exception("HTTP $status")

private fun <T> body(response: Response<T>): T {
    val body = response.body()
    if (!response.isSuccessful || body == null) throw DashboardHttpFailure(response.code())
    return body
}
