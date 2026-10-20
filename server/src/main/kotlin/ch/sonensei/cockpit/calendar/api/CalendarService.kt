package ch.sonensei.cockpit.calendar.api

import ch.sonensei.cockpit.calendar.model.Calendar
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.CalendarSource
import ch.sonensei.cockpit.calendar.model.CalendarSourceRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** A calendar with the type of its source. */
data class CalendarView(
	val calendar: Calendar,
	val sourceType: CalendarSourceType,
)

/** The changes of a calendar PATCH; a null field is not sent. */
data class CalendarChanges(
	val name: FieldChange<String>? = null,
	val color: FieldChange<String?>? = null,
	val visible: FieldChange<Boolean>? = null,
	val isDefault: FieldChange<Boolean>? = null,
)

/**
 * Calendars (SPEC.md §6.1, §6.4). New calendars are LOCAL: they belong to the single LOCAL source, created with the
 * first local calendar. At most one calendar is the default for new events; the first calendar becomes it. Changes are
 * announced as [CALENDAR_CHANGE_TYPE] change events.
 */
@Service
@Transactional
class CalendarService(
	private val sources: CalendarSourceRepository,
	private val calendars: CalendarRepository,
	private val changes: ChangeEventPublisher,
) {
	@Transactional(readOnly = true)
	fun list(): List<CalendarView> {
		val types = sources.findAll().associate { it.id to it.type }
		return calendars
			.findAll()
			.sortedWith(compareBy<Calendar, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id })
			.map { CalendarView(it, types.getValue(it.sourceId)) }
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): CalendarView = view(load(id))

	/** Creates a local calendar. */
	fun create(
		name: String,
		color: String?,
		visible: Boolean,
		isDefault: Boolean,
	): CalendarView {
		val source = localSource()
		val calendar = Calendar(source.id, name.trim())
		calendar.color = color
		calendar.visible = visible
		validate(calendar)
		if (isDefault || calendars.findByIsDefaultTrue() == null) makeDefault(calendar)
		val saved = calendars.saveAndFlush(calendar)
		changes.publish(CALENDAR_CHANGE_TYPE, saved.id, ChangeOp.CREATED)
		return view(saved)
	}

	/** Changes name, color, visibility or the default flag; `checkVersion` sees the current version first (If-Match). */
	fun update(
		id: UUID,
		fields: CalendarChanges,
		checkVersion: (Long) -> Unit,
	): CalendarView {
		val calendar = load(id)
		checkVersion(calendar.version)
		fields.name?.let { calendar.name = it.value.trim() }
		fields.color?.let { calendar.color = it.value }
		fields.visible?.let { calendar.visible = it.value }
		validate(calendar)
		fields.isDefault?.let { if (it.value) makeDefault(calendar) else calendar.isDefault = false }
		val saved = calendars.saveAndFlush(calendar)
		changes.publish(CALENDAR_CHANGE_TYPE, saved.id, ChangeOp.UPDATED)
		return view(saved)
	}

	/** The calendar for new events without a calendar. */
	@Transactional(readOnly = true)
	fun defaultCalendar(): Calendar? = calendars.findByIsDefaultTrue()

	/**
	 * A calendar whose events the cockpit can change: a LOCAL, not read-only one. External calendars are changed through
	 * their provider (calendar sync), which this API does not do yet.
	 *
	 * @throws InvalidRequestException (400, on [field]) when the calendar does not exist
	 * @throws ConflictException (409) when it is read-only or not local
	 */
	@Transactional(readOnly = true)
	fun requireWritable(
		id: UUID,
		field: String = "calendarId",
	): Calendar {
		val calendar = calendars.findById(id).orElse(null) ?: throw InvalidRequestException(field, "does not exist")
		if (calendar.readOnly) throw ConflictException("The calendar is read-only")
		if (sources.findById(calendar.sourceId).orElseThrow().type != CalendarSourceType.LOCAL) {
			throw ConflictException("Events of external calendars cannot be changed here yet")
		}
		return calendar
	}

	/** The single default: the previous default loses the flag (flushed first, the database allows only one). */
	private fun makeDefault(calendar: Calendar) {
		calendars.findByIsDefaultTrue()?.takeIf { it.id != calendar.id }?.let {
			it.isDefault = false
			calendars.saveAndFlush(it)
			changes.publish(CALENDAR_CHANGE_TYPE, it.id, ChangeOp.UPDATED)
		}
		calendar.isDefault = true
	}

	private fun localSource(): CalendarSource =
		sources.findAllByOrderByIdAsc().firstOrNull { it.type == CalendarSourceType.LOCAL }
			?: sources.saveAndFlush(CalendarSource(CalendarSourceType.LOCAL, LOCAL_SOURCE_NAME))

	private fun load(id: UUID): Calendar = calendars.findById(id).orElse(null) ?: throw NotFoundException("Calendar not found")

	private fun view(calendar: Calendar) = CalendarView(calendar, sources.findById(calendar.sourceId).orElseThrow().type)

	private fun validate(calendar: Calendar) {
		val errors = mutableListOf<FieldError>()
		if (calendar.name.isBlank()) errors += FieldError("name", "must not be blank")
		if (calendar.name.length > MAX_NAME_LENGTH) errors += FieldError("name", "must be at most $MAX_NAME_LENGTH characters")
		calendar.color?.let { if (!COLOR.matches(it)) errors += FieldError("color", "must be a hex color like #3366ff") }
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)
	}

	companion object {
		/** The `type` of calendar change events (calendars are not items). */
		const val CALENDAR_CHANGE_TYPE = "CALENDAR"

		const val LOCAL_SOURCE_NAME = "Cockpit"
		const val MAX_NAME_LENGTH = 200
		private val COLOR = Regex("^#[0-9a-fA-F]{6}$")
	}
}
