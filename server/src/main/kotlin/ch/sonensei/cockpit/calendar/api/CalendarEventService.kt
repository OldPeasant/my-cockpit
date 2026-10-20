package ch.sonensei.cockpit.calendar.api

import ch.sonensei.cockpit.calendar.model.CalendarEvent
import ch.sonensei.cockpit.calendar.model.CalendarEventIndexer
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarRepository
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.recurrence.Occurrence
import ch.sonensei.cockpit.calendar.recurrence.OccurrenceExpander
import ch.sonensei.cockpit.calendar.recurrence.OccurrenceRange
import ch.sonensei.cockpit.calendar.sync.CalendarWriteThrough
import ch.sonensei.cockpit.core.events.domain.ChangeEventPublisher
import ch.sonensei.cockpit.core.events.domain.ChangeOp
import ch.sonensei.cockpit.core.items.domain.ItemDeletedEvent
import ch.sonensei.cockpit.core.items.domain.ItemRef
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.persistence.UuidV7
import ch.sonensei.cockpit.core.settings.domain.SettingsService
import ch.sonensei.cockpit.core.web.ConflictException
import ch.sonensei.cockpit.core.web.FieldError
import ch.sonensei.cockpit.core.web.InvalidRequestException
import ch.sonensei.cockpit.core.web.NotFoundException
import net.fortuna.ical4j.model.Recur
import org.hibernate.Hibernate
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

/** A created event, and whether it is new (`false`: an idempotent repeat of a create with the same id). */
data class CreatedEvent(
	val event: CalendarEvent,
	val created: Boolean,
)

/**
 * Events of local and synced calendars (SPEC.md §6.1, §6.4; changes of synced ones are written through to their provider,
 * see [CalendarWriteThrough]): the occurrences in a range, create, change and delete, for a series
 * also of one occurrence (`THIS`), of an occurrence and every later one (`FOLLOWING`) or of the whole series (`ALL`).
 *
 * - `THIS` changes the override of the occurrence (created from the series master on first change); deleting it adds an
 *   EXDATE, or cancels its override.
 * - `FOLLOWING` splits the series: the master ends (UNTIL) before the occurrence, a new master starts at it, and the
 *   later overrides and EXDATEs move to the new series. On the first occurrence it is `ALL`.
 * - `ALL` changes the master. Content changes reach the overrides that still had the old value. When the series moves
 *   (or its rule changes), its EXDATEs and overrides follow; those that are no longer an occurrence of it are dropped.
 *
 * The time fields of a change of an occurrence (`THIS`, `FOLLOWING`, and `ALL` with an occurrence) are that
 * occurrence's new times; the series moves by the same wall-clock offset. Every changed event is re-indexed for search
 * and announced with a change event; deleted series go to the Trash ([EventTrashContributor]). Methods that change an
 * event take a `checkVersion` callback that sees the version of the addressed event first (If-Match).
 */
@Service
@Transactional
class CalendarEventService(
	private val events: CalendarEventRepository,
	private val calendarRepository: CalendarRepository,
	private val calendars: CalendarService,
	private val expander: OccurrenceExpander,
	private val indexer: CalendarEventIndexer,
	private val changes: ChangeEventPublisher,
	private val appEvents: ApplicationEventPublisher,
	private val settings: SettingsService,
	private val clock: Clock,
	private val writeThrough: CalendarWriteThrough,
) {
	/**
	 * The occurrences of the events of [calendarIds] (default: the visible calendars) overlapping [from, to), seen from
	 * the user's time zone (all-day events by the days the range touches).
	 */
	@Transactional(readOnly = true)
	fun occurrences(
		from: Instant,
		to: Instant,
		calendarIds: Collection<UUID>?,
	): List<Occurrence> {
		if (!to.isAfter(from)) throw InvalidRequestException("to", "must be after from")
		if (Duration.between(from, to) > MAX_RANGE) throw InvalidRequestException("to", "must be at most ${MAX_RANGE.toDays()} days after from")
		val ids = calendarIds?.takeIf { it.isNotEmpty() } ?: calendarRepository.findAll().filter { it.visible }.map { it.id }
		if (ids.isEmpty()) return emptyList()
		val range = OccurrenceRange(from, to, settings.timeZone())
		return expander.expand(events.findCandidatesInRange(ids, from, to, range.fromDate, range.toDate), range)
	}

	@Transactional(readOnly = true)
	fun get(id: UUID): CalendarEvent = initialized(load(id))

	/**
	 * Creates an event, by default in the default calendar. With a client-chosen [id] (UUIDv7) the create is idempotent.
	 *
	 * @throws ConflictException (409) when the id belongs to an event in the Trash, or the calendar is not writable
	 * @throws InvalidRequestException (400) for invalid fields
	 */
	fun create(
		id: UUID?,
		fields: EventChanges,
	): CreatedEvent {
		if (id != null) {
			if (!UuidV7.isUuidV7(id)) throw InvalidRequestException("id", "must be a UUIDv7")
			existingCreate(id)?.let { return it }
		}
		val calendar =
			fields.calendarId?.let { calendars.requireWritable(it.value) }
				?: calendars.defaultCalendar()?.let { calendars.requireWritable(it.id) }
				?: throw InvalidRequestException("calendarId", "is required (there is no default calendar)")
		val title = fields.title?.value ?: throw InvalidRequestException("title", "is required")
		val event = CalendarEvent(calendar.id, title, id)
		applyContent(event, fields)
		fields.rrule?.let { event.rrule = normalizeRule(it.value) }
		event.setTiming(Timing.resolve(null, fields.timing ?: TimingInput(), settings.timeZone()))
		validate(event)
		val saved = events.saveAndFlush(event)
		changed(saved, ChangeOp.CREATED)
		return CreatedEvent(initialized(saved), created = true)
	}

	/**
	 * The answer to a repeated create with [id]: the existing event unchanged, or `null` when there is none.
	 *
	 * @throws ConflictException (409) when the id belongs to an event in the Trash
	 */
	@Transactional(readOnly = true)
	fun existingCreate(id: UUID): CreatedEvent? =
		events.findById(id).orElse(null)?.let { existing ->
			if (existing.isDeleted) throw ConflictException("An event with this id is in the Trash")
			CreatedEvent(initialized(existing), created = false)
		}

	/**
	 * Changes the event [id], for a series the occurrences [scope] says (see the class). Returns the event that now
	 * holds the change: the event, the override (`THIS`), the new series (`FOLLOWING`) or the master (`ALL`).
	 *
	 * @throws InvalidRequestException (400) for invalid fields, a missing or malformed occurrence
	 * @throws NotFoundException (404) when there is no such event or occurrence
	 * @throws ConflictException (409) when the calendar is not writable
	 */
	fun update(
		id: UUID,
		scope: EditScope?,
		occurrence: String?,
		fields: EventChanges,
		checkVersion: (Long) -> Unit,
	): CalendarEvent {
		val target = resolve(id, scope, occurrence)
		checkVersion(target.addressed.version)
		calendars.requireWritable(target.addressed.calendarId, field = "id")
		fields.calendarId?.let {
			if (it.value != target.addressed.calendarId) {
				calendars.requireWritable(it.value)
				if (writeThrough.isSynced(it.value) || writeThrough.isSynced(target.addressed.calendarId)) {
					throw ConflictException("Events cannot be moved to or from a synced calendar")
				}
			}
		}
		val master = target.master ?: return initialized(updateSingle(target.addressed, fields))
		val occurrenceId = target.occurrence
		val result =
			when {
				target.scope == EditScope.THIS -> updateOccurrence(master, occurrenceId!!, target.override, fields)
				target.scope == EditScope.FOLLOWING && occurrenceId != Timing.of(master).occurrenceId() ->
					splitSeries(master, occurrenceId!!, target.override, fields)
				else -> updateSeries(master, occurrenceId, target.override, fields)
			}
		return initialized(result)
	}

	/**
	 * Deletes the event [id]: a single event or a whole series (`ALL`) goes to the Trash, one occurrence (`THIS`) is
	 * excluded, `FOLLOWING` ends the series before the occurrence.
	 */
	fun delete(
		id: UUID,
		scope: EditScope?,
		occurrence: String?,
		checkVersion: (Long) -> Unit,
	) {
		val target = resolve(id, scope, occurrence)
		checkVersion(target.addressed.version)
		calendars.requireWritable(target.addressed.calendarId, field = "id")
		val master = target.master
		val occurrenceId = target.occurrence
		when {
			master == null -> trash(target.addressed)
			target.scope == EditScope.THIS -> deleteOccurrence(master, occurrenceId!!, target.override)
			target.scope == EditScope.FOLLOWING && occurrenceId != Timing.of(master).occurrenceId() -> endSeriesBefore(master, occurrenceId!!)
			else -> trash(master)
		}
	}

	// --- single events

	private fun updateSingle(
		event: CalendarEvent,
		fields: EventChanges,
	): CalendarEvent {
		applyContent(event, fields)
		fields.calendarId?.let { event.calendarId = it.value }
		fields.rrule?.let { event.rrule = normalizeRule(it.value) }
		fields.timing?.let { event.setTiming(Timing.resolve(Timing.of(event), it, settings.timeZone())) }
		validate(event)
		return events.saveAndFlush(event).also { changed(it) }
	}

	// --- THIS

	private fun updateOccurrence(
		master: CalendarEvent,
		occurrence: OccurrenceId,
		existing: CalendarEvent?,
		fields: EventChanges,
	): CalendarEvent {
		val errors = mutableListOf<FieldError>()
		if (fields.rrule != null) errors += FieldError("rrule", "cannot change for a single occurrence (use scope ALL or FOLLOWING)")
		if (fields.calendarId != null && fields.calendarId.value != master.calendarId) {
			errors += FieldError("calendarId", "cannot change for a single occurrence (use scope ALL)")
		}
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)
		val override = existing ?: newOverride(master, occurrence, EventContent.of(master), Timing.of(master).occurrence(occurrence))
		applyContent(override, fields)
		fields.timing?.let { override.setTiming(Timing.resolve(Timing.of(override), it, settings.timeZone())) }
		validate(override)
		val saved =
			try {
				events.saveAndFlush(override)
			} catch (e: DataIntegrityViolationException) {
				// A concurrent change of the same occurrence created its override first (If-Match saw the master only).
				if (existing == null) throw ConflictException("The occurrence was changed concurrently")
				throw e
			}
		return saved.also { changed(it, if (existing == null) ChangeOp.CREATED else ChangeOp.UPDATED) }
	}

	private fun deleteOccurrence(
		master: CalendarEvent,
		occurrence: OccurrenceId,
		override: CalendarEvent?,
	) {
		if (override != null) {
			override.status = EventStatus.CANCELLED
			changed(events.saveAndFlush(override))
		} else {
			master.exdates.add(occurrence)
			changed(events.saveAndFlush(master))
		}
	}

	// --- ALL

	private fun updateSeries(
		master: CalendarEvent,
		occurrence: OccurrenceId?,
		override: CalendarEvent?,
		fields: EventChanges,
	): CalendarEvent {
		val oldTiming = Timing.of(master)
		val oldRule = master.rrule
		val before = EventContent.of(master)
		applyContent(master, fields)
		fields.calendarId?.let { master.calendarId = it.value }
		fields.rrule?.let { master.rrule = normalizeRule(it.value) }
		val requested =
			fields.timing?.let { input ->
				// The time fields are the new times of the occurrence the client changed (the first one by default).
				val original = occurrence?.let(oldTiming::occurrence) ?: oldTiming
				val shown = seriesBase(override?.let(Timing::of), original, input)
				Timing.resolve(shown, input, settings.timeZone()).also { master.setTiming(oldTiming.shifted(shown, it)) }
			}
		validate(master)
		val after = EventContent.of(master)
		val overrides = overridesOf(master)
		overrides.forEach {
			after.propagate(before, it)
			it.calendarId = master.calendarId
		}
		val exdates = master.exdates.toList()
		master.exdates.clear()
		val moved = Timing.of(master) != oldTiming || master.rrule != oldRule
		val saved = events.saveAndFlush(master)
		carryExceptions(oldTiming, oldTiming, saved, exdates, overrides, rekey = moved, addressed = addressedTiming(override, requested))
		val result = events.saveAndFlush(saved)
		changed(result)
		return result
	}

	/**
	 * The times a series edit's time fields apply to: those of the addressed [override] when it is of the kind (timed/all-day)
	 * the series gets (the requested `allDay`, else the series' own kind), otherwise those of the [original] occurrence. A
	 * series changes its kind only on request, so `allDay` equal to the series' kind behaves like a request without it; time
	 * fields of the other kind are then rejected with 400.
	 */
	private fun seriesBase(
		override: Timing?,
		original: Timing,
		input: TimingInput,
	): Timing {
		val allDay = input.allDay ?: (original is Timing.AllDay)
		return override?.takeIf { (it is Timing.AllDay) == allDay } ?: original
	}

	// --- FOLLOWING

	/** Ends [master] before [occurrence] and continues it with a new series, changed by [fields]. Returns the new series. */
	private fun splitSeries(
		master: CalendarEvent,
		occurrence: OccurrenceId,
		override: CalendarEvent?,
		fields: EventChanges,
	): CalendarEvent {
		val oldTiming = Timing.of(master)
		val rule = master.rrule!!
		val originalTiming = oldTiming.occurrence(occurrence)
		val next = CalendarEvent(master.calendarId, master.title)
		val before = EventContent.of(master)
		before.applyTo(next)
		next.rrule = remainingRule(master, rule, occurrence)
		next.setTiming(originalTiming)
		applyContent(next, fields)
		fields.calendarId?.let { next.calendarId = it.value }
		fields.rrule?.let { next.rrule = normalizeRule(it.value) }
		val requested =
			fields.timing?.let { input ->
				val shown = seriesBase(override?.let(Timing::of), originalTiming, input)
				Timing.resolve(shown, input, settings.timeZone()).also { next.setTiming(originalTiming.shifted(shown, it)) }
			}
		validate(next)
		val after = EventContent.of(next)

		master.rrule = ruleUntilBefore(rule, occurrence)
		val laterExdates = master.exdates.filter { it >= occurrence }
		master.exdates.removeAll(laterExdates.toSet())
		val laterOverrides = overridesOf(master).filter { it.recurrenceId!! >= occurrence }
		laterOverrides.forEach {
			after.propagate(before, it)
			it.calendarId = next.calendarId
		}
		val savedMaster = events.saveAndFlush(master)
		changed(savedMaster)
		val saved = events.saveAndFlush(next)
		carryExceptions(
			oldTiming,
			originalTiming,
			saved,
			laterExdates,
			laterOverrides,
			rekey = true,
			addressed = addressedTiming(override, requested),
		)
		val result = events.saveAndFlush(saved)
		changed(result, ChangeOp.CREATED)
		return result
	}

	/** Ends the series before [occurrence]: later EXDATEs go, later overrides are deleted. */
	private fun endSeriesBefore(
		master: CalendarEvent,
		occurrence: OccurrenceId,
	) {
		master.rrule = ruleUntilBefore(master.rrule!!, occurrence)
		master.exdates.removeAll(master.exdates.filter { it >= occurrence }.toSet())
		overridesOf(master).filter { it.recurrenceId!! >= occurrence }.forEach(::deleteOverride)
		changed(events.saveAndFlush(master))
	}

	// --- exceptions (EXDATEs and overrides)

	/**
	 * Gives [target] (a saved series master) the EXDATEs and overrides of a series whose occurrence [from] corresponds
	 * to [target]'s first occurrence (for `ALL` the series itself, before its change). With [rekey], each exception moves
	 * like that occurrence moved; one that is then no occurrence of [target] (or of another kind) is dropped. An override
	 * that was not moved from its occurrence moves along; overrides whose master or recurrence id changes are recreated
	 * (the database keeps both fixed). The override of the occurrence the client changed ([addressed]: its recurrence id
	 * and requested times) gets the requested times.
	 */
	private fun carryExceptions(
		oldMasterTiming: Timing,
		from: Timing,
		target: CalendarEvent,
		exdates: Collection<OccurrenceId>,
		overrides: Collection<CalendarEvent>,
		rekey: Boolean,
		addressed: Pair<OccurrenceId, Timing>? = null,
	) {
		val to = Timing.of(target)
		val newId: (OccurrenceId) -> OccurrenceId? = { id ->
			if (!rekey) id else from.moveOccurrenceId(id, to)?.takeIf { isGenerated(target, it) }
		}
		exdates.mapNotNullTo(target.exdates) { newId(it) }
		val replaced = mutableListOf<Pair<CalendarEvent, OccurrenceId>>()
		for (override in overrides) {
			val id = override.recurrenceId!!
			val moved = newId(id)
			when {
				moved == null -> deleteOverride(override)
				moved == id && override.parentEventId == target.id -> {
					val requested = addressed?.takeIf { it.first == id }?.second
					when {
						requested != null -> override.setTiming(requested)
						Timing.of(override) == oldMasterTiming.occurrence(id) -> override.setTiming(to.occurrence(id))
					}
					changed(events.saveAndFlush(override))
				}
				else -> replaced += override to moved
			}
		}
		// Delete first: the recreated overrides may take recurrence ids of deleted ones.
		val copies =
			replaced.map { (override, moved) ->
				val id = override.recurrenceId!!
				val unmoved = Timing.of(override) == oldMasterTiming.occurrence(id)
				val timing =
					addressed?.takeIf { it.first == id }?.second
						?: if (unmoved) to.occurrence(moved) else Timing.of(override)
				deleteOverride(override)
				Triple(EventContent.of(override), moved, timing)
			}
		events.flush()
		copies.forEach { (content, moved, timing) ->
			changed(events.saveAndFlush(newOverride(target, moved, content, timing)), ChangeOp.CREATED)
		}
	}

	/** The recurrence id of the addressed [override] with the [requested] times, when the client sent times for it. */
	private fun addressedTiming(
		override: CalendarEvent?,
		requested: Timing?,
	): Pair<OccurrenceId, Timing>? = if (override != null && requested != null) override.recurrenceId!! to requested else null

	private fun newOverride(
		master: CalendarEvent,
		occurrence: OccurrenceId,
		content: EventContent,
		timing: Timing,
	): CalendarEvent =
		CalendarEvent(master.calendarId, content.title).apply {
			content.applyTo(this)
			setTiming(timing)
			overrideOf(master, occurrence)
		}

	/** Removes an override for good (it is no occurrence any more): with its links and search entry. */
	private fun deleteOverride(override: CalendarEvent) {
		writeThrough.record(override, ChangeOp.DELETED)
		events.delete(override)
		val ref = ItemRef(ItemType.EVENT, override.id)
		appEvents.publishEvent(ItemDeletedEvent(ref))
		changes.publish(ItemType.EVENT, override.id, ChangeOp.DELETED)
	}

	private fun overridesOf(master: CalendarEvent) = events.findByParentEventIdOrderByIdAsc(master.id).filter { !it.isDeleted }

	// --- recurrence rules

	/** [rule] ending before [occurrence]: COUNT and UNTIL are replaced by an UNTIL just before it. */
	private fun ruleUntilBefore(
		rule: String,
		occurrence: OccurrenceId,
	): String {
		val until =
			occurrence.at?.let { UNTIL_TIME.format(it.minusSeconds(1).atOffset(ZoneOffset.UTC)) }
				?: UNTIL_DATE.format(occurrence.date!!.minusDays(1))
		return (ruleParts(rule).filterKeys { it != "COUNT" && it != "UNTIL" } + ("UNTIL" to until)).toRule()
	}

	/** [rule] of [master] continued from [occurrence]: a COUNT only counts the occurrences from there on. */
	private fun remainingRule(
		master: CalendarEvent,
		rule: String,
		occurrence: OccurrenceId,
	): String {
		val parts = ruleParts(rule)
		val count = parts["COUNT"]?.toIntOrNull() ?: return rule
		val timing = Timing.of(master)
		val earlier = expandUntil(master, timing, occurrence).size
		return (parts + ("COUNT" to maxOf(1, count - earlier).toString())).toRule()
	}

	/** The original starts of [master]'s rule before [occurrence], EXDATEs included (they count for COUNT). */
	private fun expandUntil(
		master: CalendarEvent,
		timing: Timing,
		occurrence: OccurrenceId,
	): List<OccurrenceId> {
		val probe = probe(master)
		val range =
			when (timing) {
				is Timing.Timed -> OccurrenceRange(timing.start, occurrence.at!!, ZoneOffset.UTC)
				is Timing.AllDay ->
					OccurrenceRange(
						timing.startDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
						occurrence.date!!.atStartOfDay(ZoneOffset.UTC).toInstant(),
						ZoneOffset.UTC,
					)
			}
		return expander.expandSeries(probe, emptyList(), range).mapNotNull { it.occurrenceId }.filter { it < occurrence }
	}

	private fun ruleParts(rule: String): Map<String, String> =
		rule
			.split(';')
			.filter { it.isNotBlank() }
			.associate { part -> part.substringBefore('=').uppercase() to part.substringAfter('=', "") }

	private fun Map<String, String>.toRule(): String = entries.joinToString(";") { (key, value) -> "$key=$value" }

	// --- targets

	/** The event a request addresses, its series (if any) and the occurrence of that series it is about. */
	private class Target(
		val addressed: CalendarEvent,
		val master: CalendarEvent?,
		val occurrence: OccurrenceId?,
		val override: CalendarEvent?,
		val scope: EditScope,
	)

	/**
	 * What `PATCH|DELETE /events/{id}?scope=&occurrence=` addresses. A single event ignores the scope. An override stands
	 * for its occurrence (default `THIS`); a series master needs the occurrence for `THIS` and `FOLLOWING` (default
	 * `ALL`).
	 */
	private fun resolve(
		id: UUID,
		scope: EditScope?,
		occurrence: String?,
	): Target {
		val event = load(id)
		if (event.isOverride) {
			val master = events.findActiveById(event.parentEventId!!) ?: throw NotFoundException("Event not found")
			val own = event.recurrenceId!!
			if (occurrence != null && parseOccurrence(occurrence, master) != own) {
				throw InvalidRequestException("occurrence", "is not the occurrence of this event")
			}
			return Target(event, master, own, event, scope ?: EditScope.THIS)
		}
		if (!event.isRecurring) {
			if (occurrence != null) throw InvalidRequestException("occurrence", "is only allowed for recurring events")
			return Target(event, null, null, null, EditScope.ALL)
		}
		val effective = scope ?: EditScope.ALL
		val occurrenceId = occurrence?.let { parseOccurrence(it, event) }
		if (effective != EditScope.ALL && occurrenceId == null) {
			throw InvalidRequestException("occurrence", "is required for scope $effective")
		}
		val override = occurrenceId?.let { occ -> overridesOf(event).firstOrNull { it.recurrenceId == occ } }
		if (occurrenceId != null && override == null && !isGenerated(event, occurrenceId)) {
			throw NotFoundException("The series has no such occurrence")
		}
		return Target(event, event, occurrenceId, override, effective)
	}

	private fun parseOccurrence(
		value: String,
		master: CalendarEvent,
	): OccurrenceId =
		try {
			if (master.allDay) {
				OccurrenceId.of(LocalDate.parse(value))
			} else {
				OccurrenceId.of(OffsetDateTime.parse(value).toInstant())
			}
		} catch (e: DateTimeParseException) {
			val expected = if (master.allDay) "a date (YYYY-MM-DD) for an all-day series" else "an ISO-8601 instant for a timed series"
			throw InvalidRequestException("occurrence", "must be $expected")
		}

	/** Whether the rule of [master] (with its current times and EXDATEs) has an occurrence originally starting at [id]. */
	private fun isGenerated(
		master: CalendarEvent,
		id: OccurrenceId,
	): Boolean {
		if (master.rrule == null || master.allDay != (id.date != null)) return false
		val range =
			id.at?.let { OccurrenceRange(it, it.plusSeconds(1), ZoneOffset.UTC) }
				?: id.date!!
					.atStartOfDay(ZoneOffset.UTC)
					.toInstant()
					.let { OccurrenceRange(it, it.plus(Duration.ofDays(1)), ZoneOffset.UTC) }
		return expander.expandSeries(master, emptyList(), range).any { it.occurrenceId == id }
	}

	/** An unsaved copy of [master]'s times and rule without EXDATEs, to expand the rule alone. */
	private fun probe(master: CalendarEvent): CalendarEvent =
		CalendarEvent(master.calendarId, master.title, master.id).apply {
			rrule = master.rrule
			setTiming(Timing.of(master))
		}

	// --- common

	/**
	 * Moves [event] (with its overrides) to the Trash. An event of a synced calendar is deleted at the provider and
	 * here for good instead: the provider keeps its own trash, and a restored copy would not be the provider's event.
	 */
	private fun trash(event: CalendarEvent) {
		if (writeThrough.isSynced(event.calendarId)) {
			val overrides = events.findByParentEventIdOrderByIdAsc(event.id)
			(overrides + event).forEach {
				writeThrough.record(it, ChangeOp.DELETED)
				appEvents.publishEvent(ItemDeletedEvent(ItemRef(ItemType.EVENT, it.id)))
				changes.publish(ItemType.EVENT, it.id, ChangeOp.DELETED)
			}
			events.deleteAll(overrides)
			events.delete(event)
			events.flush()
			return
		}
		event.softDelete(clock)
		val saved = events.saveAndFlush(event)
		indexer.index(saved)
		changes.publish(ItemType.EVENT, saved.id, ChangeOp.DELETED)
	}

	private fun changed(
		event: CalendarEvent,
		op: ChangeOp = ChangeOp.UPDATED,
	) {
		indexer.index(event)
		changes.publish(ItemType.EVENT, event.id, op)
		writeThrough.record(event, op)
	}

	/** An event that is not in the Trash; an override shares its series master's Trash state. */
	private fun load(id: UUID): CalendarEvent =
		events
			.findActiveById(id)
			?.takeIf { !it.isOverride || events.findActiveById(it.parentEventId!!) != null }
			?: throw NotFoundException("Event not found")

	private fun initialized(event: CalendarEvent): CalendarEvent = event.also { Hibernate.initialize(it.exdates) }

	private fun applyContent(
		event: CalendarEvent,
		fields: EventChanges,
	) {
		fields.title?.let { event.title = it.value }
		fields.description?.let { event.description = it.value }
		fields.location?.let { event.location = it.value }
		fields.attendees?.let { event.attendees = it.value }
		fields.reminderMinutes?.let { event.reminderMinutes = it.value }
		fields.status?.let { event.status = it.value }
		fields.transparency?.let { event.transparency = it.value }
		fields.conferenceUrl?.let { event.conferenceUrl = it.value }
	}

	/** The rule without an `RRULE:` prefix; blank is none. */
	private fun normalizeRule(rule: String?): String? =
		rule
			?.trim()
			?.removePrefix("RRULE:")
			?.removePrefix("rrule:")
			?.trim()
			?.ifEmpty { null }

	/** The invariants of an event, checked after every change; all violations are reported at once. */
	private fun validate(event: CalendarEvent) {
		val errors = mutableListOf<FieldError>()
		if (event.title.isBlank()) errors += FieldError("title", "must not be blank")
		if (event.title.length > MAX_TITLE_LENGTH) errors += FieldError("title", "must be at most $MAX_TITLE_LENGTH characters")
		if ((event.description?.length ?: 0) > MAX_DESCRIPTION_LENGTH) {
			errors += FieldError("description", "must be at most $MAX_DESCRIPTION_LENGTH characters")
		}
		if ((event.location?.length ?: 0) > MAX_TEXT_LENGTH) errors += FieldError("location", "must be at most $MAX_TEXT_LENGTH characters")
		event.conferenceUrl?.let {
			if (!isWebUrl(it)) {
				errors +=
					FieldError("conferenceUrl", "must be an http(s) URL of at most $MAX_TEXT_LENGTH characters")
			}
		}
		event.rrule?.let { rule ->
			val valid =
				rule.length <= MAX_TEXT_LENGTH &&
					runCatching { Recur<LocalDate>(rule) }.isSuccess &&
					ruleParts(rule).containsKey("FREQ")
			if (!valid) errors += FieldError("rrule", "is not a valid iCalendar RRULE")
		}
		if (event.attendees.size > MAX_ATTENDEES) errors += FieldError("attendees", "must have at most $MAX_ATTENDEES entries")
		event.attendees.forEachIndexed { i, attendee ->
			if (attendee.email.isBlank() || attendee.email.length > MAX_EMAIL_LENGTH || '@' !in attendee.email) {
				errors += FieldError("attendees[$i].email", "must be an e-mail address")
			}
			if ((attendee.name?.length ?: 0) >
				MAX_NAME_LENGTH
			) {
				errors += FieldError("attendees[$i].name", "must be at most $MAX_NAME_LENGTH characters")
			}
		}
		if (event.reminderMinutes.size > MAX_REMINDERS) errors += FieldError("reminderMinutes", "must have at most $MAX_REMINDERS entries")
		if (event.reminderMinutes.any { it !in 0..MAX_REMINDER_MINUTES }) {
			errors += FieldError("reminderMinutes", "must be between 0 and $MAX_REMINDER_MINUTES")
		}
		if (errors.isNotEmpty()) throw InvalidRequestException(errors)
	}

	private fun isWebUrl(value: String): Boolean =
		value.length <= MAX_TEXT_LENGTH &&
			runCatching { URI(value) }.getOrNull()?.let { (it.scheme == "https" || it.scheme == "http") && !it.host.isNullOrEmpty() } == true

	companion object {
		const val MAX_TITLE_LENGTH = 500
		const val MAX_DESCRIPTION_LENGTH = 100_000
		const val MAX_TEXT_LENGTH = 2000
		const val MAX_EMAIL_LENGTH = 320
		const val MAX_NAME_LENGTH = 200
		const val MAX_ATTENDEES = 200
		const val MAX_REMINDERS = 20
		const val MAX_REMINDER_MINUTES = 40_320

		/** The longest range `occurrences` expands. */
		val MAX_RANGE: Duration = Duration.ofDays(400)

		private val UNTIL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
		private val UNTIL_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
	}
}
