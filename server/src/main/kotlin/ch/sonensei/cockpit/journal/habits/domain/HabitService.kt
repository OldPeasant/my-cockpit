package ch.sonensei.cockpit.journal.habits.domain

import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemDeletedEvent
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.reminders.domain.ReminderFiredEvent
import ch.sonensei.cockpit.core.reminders.domain.ReminderService
import ch.sonensei.cockpit.core.search.domain.SearchIndexer
import ch.sonensei.cockpit.core.settings.domain.CoreSettings
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import ch.sonensei.cockpit.journal.habits.infra.HabitCheckRepository
import ch.sonensei.cockpit.journal.habits.infra.HabitRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A change of one field; `Change(null)` clears it. A `null` change leaves the field as it is. */
data class Change<out T>(
	val value: T,
)

/** The fields of a create or update; `null`: not given (unchanged, or the default on create). */
data class HabitChanges(
	val name: Change<String?>? = null,
	val schedule: Change<HabitSchedule?>? = null,
	val timesPerWeek: Change<Int?>? = null,
	val weekdays: Change<List<DayOfWeek>>? = null,
	val targetCount: Change<Int?>? = null,
	val reminderTime: Change<LocalTime?>? = null,
	val archived: Change<Boolean?>? = null,
)

/** A habit with its state on the owner's today. */
data class HabitView(
	val habit: Habit,
	val stats: HabitStats,
)

/**
 * Habits and their checks (SPEC.md §8.5). Dates are local dates in the owner's time zone, weeks start on the owner's
 * week start (settings). Every change of a habit or its checks publishes a `HABIT`
 * [ch.sonensei.cockpit.core.events.domain.ChangeEvent]; the habit's name is in the search index.
 *
 * Reminders: a habit with a [Habit.reminderTime] that is not archived has exactly one pending reminder managed by this
 * module ([Habit.reminderId]), at that time on its next due day that is not done yet. It moves on whenever the habit or
 * a check changes, and after it fired. Other reminders of the habit (added through the reminders API) are left alone. A
 * change of the time zone setting applies from the next move on.
 *
 * Methods that change a habit take a `checkVersion` callback that sees the current version first (If-Match).
 */
@Service
@Transactional
class HabitService(
	private val habits: HabitRepository,
	private val checks: HabitCheckRepository,
	private val reminders: ReminderService,
	private val indexer: SearchIndexer,
	private val changes: ChangeEventPublisher,
	private val events: ApplicationEventPublisher,
	private val settings: SettingsService,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	private val isolated =
		TransactionTemplate(transactionManager).apply {
			propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
		}

	/** The habits that are archived ([archived]) or not, by name. */
	@Transactional(readOnly = true)
	fun list(archived: Boolean): List<HabitView> {
		val found = habits.findByArchived(archived)
		val byHabit = checks.findByHabitIdIn(found.map { it.id }).groupBy { it.habitId }
		val context = context()
		return found.map { HabitView(it, calendar(it, byHabit[it.id].orEmpty(), context).stats()) }
	}

	/** @throws NotFoundException (404) when no such habit exists */
	@Transactional(readOnly = true)
	fun get(id: UUID): HabitView = view(load(id))

	/**
	 * Creates a habit (default: DAILY, target count 1).
	 *
	 * @throws InvalidRequestException (400) for invalid fields
	 */
	fun create(fields: HabitChanges): HabitView {
		val habit = Habit(name = "")
		apply(habit, fields)
		validate(habit)
		val saved = habits.saveAndFlush(habit)
		return changed(saved, ChangeOp.CREATED)
	}

	/**
	 * Changes the given fields. Fields that do not fit the schedule are cleared (`timesPerWeek` unless TIMES_PER_WEEK,
	 * `weekdays` unless WEEKDAYS).
	 *
	 * @throws InvalidRequestException (400) for invalid fields
	 * @throws NotFoundException (404) when no such habit exists
	 */
	fun update(
		id: UUID,
		fields: HabitChanges,
		checkVersion: (Long) -> Unit,
	): HabitView {
		val habit = load(id)
		checkVersion(habit.version)
		apply(habit, fields)
		validate(habit)
		val saved = habits.saveAndFlush(habit)
		return changed(saved, ChangeOp.UPDATED)
	}

	/** Deletes the habit for good, with its checks, links and reminders. */
	fun delete(
		id: UUID,
		checkVersion: (Long) -> Unit,
	) {
		val habit = load(id)
		checkVersion(habit.version)
		habits.delete(habit)
		habits.flush()
		events.publishEvent(ItemDeletedEvent(ItemRef(ItemType.HABIT, id)))
		changes.publish(ItemType.HABIT, id, ChangeOp.DELETED)
	}

	/**
	 * Sets how often the habit was done on [date] (0 removes the check). Archived habits can be checked too.
	 *
	 * @throws InvalidRequestException (400) for a date after today or a count out of range
	 * @throws NotFoundException (404) when no such habit exists
	 */
	fun setCheck(
		id: UUID,
		date: LocalDate,
		count: Int,
	): HabitView {
		val context = context()
		val errors = mutableListOf<FieldError>()
		if (date > context.today) errors += FieldError("date", "must not be after today (${context.today})")
		if (count !in 0..MAX_COUNT) errors += FieldError("count", "must be between 0 and $MAX_COUNT")
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)
		// Locked, so concurrent checks of the same habit and date never insert twice.
		val habit = habits.lockById(id) ?: throw NotFoundException("Habit not found")
		val check = checks.findByHabitIdAndDate(id, date)
		when {
			count == 0 -> check?.let(checks::delete)
			check == null -> checks.save(HabitCheck(id, date, count))
			else -> check.count = count
		}
		checks.flush()
		changes.publish(ItemType.HABIT, id, ChangeOp.UPDATED)
		return sync(habit, context)
	}

	/**
	 * The checks of a habit from [from] to [to] (both included), by date.
	 *
	 * @throws InvalidRequestException (400) when [to] is before [from] or the range is longer than [MAX_RANGE_DAYS]
	 * @throws NotFoundException (404) when no such habit exists
	 */
	@Transactional(readOnly = true)
	fun checks(
		id: UUID,
		from: LocalDate,
		to: LocalDate,
	): List<HabitCheck> {
		if (to < from) throw InvalidRequestException("to", "must not be before from")
		val days = ChronoUnit.DAYS.between(from, to) + 1
		if (days > MAX_RANGE_DAYS) {
			throw InvalidRequestException("to", "must be at most $MAX_RANGE_DAYS days after from")
		}
		load(id)
		return checks.findRange(id, from, to)
	}

	/** The owner's today. */
	fun today(): LocalDate = LocalDate.now(clock.withZone(settings.timeZone()))

	/**
	 * A fired habit reminder schedules the next one, after the firing transaction committed and in a transaction of its
	 * own: a failure here must neither roll back nor stop the firing batch of every module's reminders, so it is only
	 * logged (the next check or habit change moves the reminder again).
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	fun onReminderFired(event: ReminderFiredEvent) {
		val reminder = event.reminder.reminder
		if (reminder.itemType != ItemType.HABIT) return
		try {
			isolated.executeWithoutResult {
				habits.findById(reminder.itemId).orElse(null)?.let { sync(it, context()) }
			}
		} catch (e: RuntimeException) {
			log.warn("Scheduling the next reminder of habit {} failed", reminder.itemId, e)
		}
	}

	private fun changed(
		habit: Habit,
		op: ChangeOp,
	): HabitView {
		indexer.upsert(ItemType.HABIT, habit.id, habit.name, null)
		changes.publish(ItemType.HABIT, habit.id, op)
		return sync(habit, context())
	}

	/**
	 * The habit's view, after moving its managed reminder ([Habit.reminderId]) to where it belongs now. Only that
	 * reminder is touched; once it fired or is gone, a new one is created.
	 */
	private fun sync(
		habit: Habit,
		context: Context,
	): HabitView {
		val calendar = calendar(habit, checks.findByHabitIdIn(listOf(habit.id)), context)
		val ref = ItemRef(ItemType.HABIT, habit.id)
		val now = clock.instant()
		val wanted =
			habit.reminderTime?.takeUnless { habit.archived }?.let { time ->
				calendar
					.openDueDays()
					.map { it.atTime(time).atZone(context.zone).toInstant() }
					.firstOrNull { it > now }
					?.truncatedTo(ChronoUnit.MICROS)
			}
		val managed = habit.reminderId?.let { id -> reminders.pendingOfItem(ref).firstOrNull { it.id == id } }
		if (managed?.remindAt != wanted) {
			managed?.let { reminders.delete(it.id) }
			val created = wanted?.let { reminders.create(ref, it).reminder.id }
			habits.setReminderId(habit.id, created)
			log.debug("Habit {} reminder moved to {}", habit.id, wanted)
		}
		return HabitView(habit, calendar.stats())
	}

	private fun view(habit: Habit): HabitView = HabitView(habit, calendar(habit, checks.findByHabitIdIn(listOf(habit.id)), context()).stats())

	private fun calendar(
		habit: Habit,
		habitChecks: List<HabitCheck>,
		context: Context,
	) = HabitCalendar(
		rule = habit.rule,
		counts = habitChecks.associate { it.date to it.count },
		since = checkNotNull(habit.createdAt).atZone(context.zone).toLocalDate(),
		today = context.today,
		weekStart = context.weekStart,
	)

	private fun context(): Context {
		val zone = settings.timeZone()
		return Context(zone, LocalDate.now(clock.withZone(zone)), DayOfWeek.valueOf(settings.get(CoreSettings.WEEK_START)))
	}

	private fun load(id: UUID): Habit = habits.findById(id).orElseThrow { NotFoundException("Habit not found") }

	private fun apply(
		habit: Habit,
		fields: HabitChanges,
	) {
		fields.name?.let { habit.name = it.value?.trim().orEmpty() }
		fields.schedule?.let { habit.schedule = it.value ?: HabitSchedule.DAILY }
		fields.timesPerWeek?.let { habit.timesPerWeek = it.value }
		fields.weekdays?.let { habit.weekdays = it.value.distinct().sorted() }
		fields.targetCount?.let { habit.targetCount = it.value ?: 1 }
		fields.reminderTime?.let { habit.reminderTime = it.value?.truncatedTo(ChronoUnit.MINUTES) }
		fields.archived?.let { habit.archived = it.value ?: false }
		if (habit.schedule != HabitSchedule.TIMES_PER_WEEK) habit.timesPerWeek = null
		if (habit.schedule != HabitSchedule.WEEKDAYS) habit.weekdays = emptyList()
	}

	private fun validate(habit: Habit) {
		val errors = mutableListOf<FieldError>()
		if (habit.name.isEmpty()) errors += FieldError("name", "must not be blank")
		if (habit.name.length > MAX_NAME_LENGTH) errors += FieldError("name", "must be at most $MAX_NAME_LENGTH characters")
		if (habit.targetCount !in 1..MAX_COUNT) errors += FieldError("targetCount", "must be between 1 and $MAX_COUNT")
		if (habit.schedule == HabitSchedule.TIMES_PER_WEEK && habit.timesPerWeek?.let { it in 1..7 } != true) {
			errors += FieldError("timesPerWeek", "must be between 1 and 7 for TIMES_PER_WEEK")
		}
		if (habit.schedule == HabitSchedule.WEEKDAYS && habit.weekdays.isEmpty()) {
			errors += FieldError("weekdays", "must name at least one day for WEEKDAYS")
		}
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)
	}

	private data class Context(
		val zone: ZoneId,
		val today: LocalDate,
		val weekStart: DayOfWeek,
	)

	companion object {
		const val MAX_NAME_LENGTH = 200
		const val MAX_COUNT = 1000
		const val MAX_RANGE_DAYS = 3660L
	}
}
