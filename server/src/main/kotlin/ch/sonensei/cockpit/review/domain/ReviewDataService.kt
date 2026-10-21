package ch.sonensei.cockpit.review.domain

import ch.sonensei.cockpit.calendar.api.CalendarEventService
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.recurrence.Occurrence
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.core.web.CursorPage
import ch.sonensei.cockpit.core.web.CursorRequest
import ch.sonensei.cockpit.gtd.domain.ProjectFilter
import ch.sonensei.cockpit.gtd.domain.ProjectService
import ch.sonensei.cockpit.gtd.domain.ProjectStatus
import ch.sonensei.cockpit.gtd.domain.ProjectView
import ch.sonensei.cockpit.gtd.domain.Task
import ch.sonensei.cockpit.gtd.domain.TaskFilter
import ch.sonensei.cockpit.gtd.domain.TaskList
import ch.sonensei.cockpit.gtd.domain.TaskService
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** The Someday/Maybe step: tasks on the Someday list and projects with status SOMEDAY. */
data class SomedayItems(
	val tasks: List<Task>,
	val projects: List<ProjectView>,
)

/** The occurrences of a calendar step, from [from] (inclusive) to [to] (exclusive). */
data class ReviewEvents(
	val from: Instant,
	val to: Instant,
	val occurrences: List<Occurrence>,
)

/**
 * What the steps of the weekly review show (SPEC.md §8.6). It reads through the GTD and calendar services; it keeps no
 * state of its own, so every step shows the current data (also after the user acted on an item). Not transactional itself:
 * every service call runs in its own read-only transaction. Days are local days in the user's time zone.
 */
@Service
class ReviewDataService(
	private val tasks: TaskService,
	private val projects: ProjectService,
	private val events: CalendarEventService,
	private val settings: SettingsService,
	private val clock: Clock,
) {
	/** Open Next actions not changed for more than [olderThanDays] days, least recently changed first. */
	fun staleNextActions(olderThanDays: Int = STALE_DAYS): List<Task> {
		val cutoff = clock.instant().minus(Duration.ofDays(olderThanDays.toLong()))
		return allTasks(TaskList.NEXT)
			.filter { checkNotNull(it.updatedAt).isBefore(cutoff) }
			.sortedWith(compareBy<Task>({ it.updatedAt }, { it.id }))
	}

	/** Open Waiting For tasks, waiting longest first (those without a date last), then in list order. */
	fun waitingFor(): List<Task> = allTasks(TaskList.WAITING).sortedWith(compareBy(nullsLast()) { it.waitingSince })

	/** Active projects, stalled ones first, each group by title. */
	fun projects(): List<ProjectView> =
		allProjects(ProjectStatus.ACTIVE).sortedWith(compareBy({ !it.stalled }, { it.project.title }, { it.project.id }))

	/** Someday/Maybe tasks in list order and SOMEDAY projects by title. */
	fun someday(): SomedayItems = SomedayItems(allTasks(TaskList.SOMEDAY), allProjects(ProjectStatus.SOMEDAY))

	/** Occurrences of the last [PAST_DAYS] days before today (today not included), cancelled ones left out. */
	fun pastEvents(): ReviewEvents {
		val today = today()
		return occurrences(today.minusDays(PAST_DAYS), today)
	}

	/** Occurrences of today and the following days, [UPCOMING_DAYS] days in all, cancelled ones left out. */
	fun upcomingEvents(): ReviewEvents {
		val today = today()
		return occurrences(today, today.plusDays(UPCOMING_DAYS))
	}

	private fun occurrences(
		fromDay: LocalDate,
		toDay: LocalDate,
	): ReviewEvents {
		val zone = settings.timeZone()
		val from = fromDay.atStartOfDay(zone).toInstant()
		val to = toDay.atStartOfDay(zone).toInstant()
		val list =
			events
				.occurrences(from, to, null)
				.filter { it.event.status != EventStatus.CANCELLED }
				.sortedWith(compareBy({ it.start ?: it.startDate?.atStartOfDay(zone)?.toInstant() }, { it.event.id }))
		return ReviewEvents(from, to, list)
	}

	private fun today(): LocalDate = LocalDate.now(clock.withZone(settings.timeZone()))

	/** Every open task of [list] (not in a tickler), in list order. */
	private fun allTasks(list: TaskList): List<Task> = collect { tasks.list(TaskFilter(list = list), it) }

	/** Every project with [status], by title. */
	private fun allProjects(status: ProjectStatus): List<ProjectView> = collect { projects.list(ProjectFilter(status = status), it) }

	private fun <T> collect(fetch: (CursorRequest) -> CursorPage<T>): List<T> {
		val result = mutableListOf<T>()
		var request = CursorRequest(null, CursorRequest.MAX_LIMIT)
		while (true) {
			val page = fetch(request)
			result += page.items
			val next = page.nextCursor ?: return result
			request = CursorRequest.of(next, CursorRequest.MAX_LIMIT)
		}
	}

	companion object {
		const val STALE_DAYS = 30
		const val MAX_STALE_DAYS = 3650
		const val PAST_DAYS = 7L
		const val UPCOMING_DAYS = 14L
	}
}
