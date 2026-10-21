package ch.sonensei.cockpit.calendar.backup

import ch.sonensei.cockpit.backup.domain.BackupContributor
import ch.sonensei.cockpit.backup.domain.BackupDataReader
import ch.sonensei.cockpit.backup.domain.BackupDataWriter
import ch.sonensei.cockpit.backup.domain.BackupReadableWriter
import ch.sonensei.cockpit.backup.domain.json
import ch.sonensei.cockpit.backup.domain.jsonLines
import ch.sonensei.cockpit.backup.domain.secret
import ch.sonensei.cockpit.calendar.api.Timing
import ch.sonensei.cockpit.calendar.infra.caldav.CalDavEventMapping
import ch.sonensei.cockpit.calendar.model.Attendee
import ch.sonensei.cockpit.calendar.model.CalendarEventIndexer
import ch.sonensei.cockpit.calendar.model.CalendarEventRepository
import ch.sonensei.cockpit.calendar.model.CalendarSourceType
import ch.sonensei.cockpit.calendar.model.EventStatus
import ch.sonensei.cockpit.calendar.model.EventSyncState
import ch.sonensei.cockpit.calendar.model.EventTransparency
import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.calendar.sync.RemoteEvent
import ch.sonensei.cockpit.calendar.sync.RemoteEventKey
import ch.sonensei.cockpit.core.backupcontrib.OAuthBackupContributor
import ch.sonensei.cockpit.core.backupcontrib.instant
import ch.sonensei.cockpit.core.backupcontrib.instantOrNull
import ch.sonensei.cockpit.core.backupcontrib.toTimestamp
import ch.sonensei.cockpit.core.backupcontrib.toTimestampOrNull
import ch.sonensei.cockpit.core.backupcontrib.uuid
import ch.sonensei.cockpit.core.crypto.SecretCipher
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.sql.Date
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Calendars in backups (SPEC.md §6.1, §10.2):
 *
 * - `data/calendar_sources.json`: every source with its non-secret `config`. Credentials (CalDAV password, ...) are
 *   secrets: only in the backup secret [CREDENTIALS] (source id → credential, with a passphrase); a source restored
 *   without them has to be reconnected.
 * - `data/calendars.json`: every calendar, LOCAL and external.
 * - `data/events.jsonl`: events of LOCAL sources fully (Trash included, with EXDATEs; series masters before their
 *   overrides). Events of external sources only as id refs ([EventBackup.content] null): the provider has them, but
 *   links to them must survive (SPEC.md §10.2). A ref is restored as a placeholder row with its id, external id and
 *   recurrence id, far in the past and without etag: the first sync after the restore (a full one, as sync cursors are
 *   not backed up) matches it by external id and fills it in, or deletes it when the provider no longer has it.
 *
 * Mirror: `readable/calendar/<calendar-name>.ics` per calendar of a LOCAL source, with its events not in the Trash
 * (UID: the event id), importable into other calendar apps.
 *
 * Sync status and cursors are not backed up. Rows are read and written with JDBC rather than JPA: a restore must keep
 * ids, `created_at`, `updated_at` and `version` exactly as backed up, which JPA auditing and versioning would overwrite.
 */
@Component
class CalendarBackupContributor(
	private val jdbc: JdbcTemplate,
	private val jsonMapper: JsonMapper,
	private val cipher: SecretCipher,
	private val events: CalendarEventRepository,
	private val indexer: CalendarEventIndexer,
) : BackupContributor {
	private val log = LoggerFactory.getLogger(javaClass)

	override val id = ID

	// External sources may refer to an OAuth connection in their config.
	override val dependsOn = setOf(OAuthBackupContributor.ID)

	override fun export(writer: BackupDataWriter) {
		val credentials = sortedMapOf<String, String>()
		val sources =
			jdbc.query("select * from calendar_source order by id") { rs, _ ->
				val source =
					CalendarSourceBackup(
						id = rs.uuid("id"),
						createdAt = rs.instant("created_at"),
						updatedAt = rs.instant("updated_at"),
						version = rs.getLong("version"),
						type = rs.getString("type"),
						name = rs.getString("name"),
						config = jsonMapper.readTree(rs.getString("config")),
					)
				if (writer.includesSecrets) rs.getString("credentials")?.let { credentials[source.id.toString()] = cipher.decrypt(it) }
				source
			}
		if (sources.isEmpty()) return
		writer.json(SOURCES, sources)
		if (credentials.isNotEmpty()) writer.secret(CREDENTIALS, credentials)
		calendars().takeIf { it.isNotEmpty() }?.let { writer.json(CALENDARS, it) }
		val exdates = exdates()
		eventRows { rows -> writer.jsonLines(EVENTS, rows.map { it.toBackup(exdates) }) }
	}

	override fun readable(writer: BackupReadableWriter) {
		val local = calendars().filter { it.sourceId in localSources() }
		if (local.isEmpty()) return
		val exdates = exdates()
		val byCalendar = mutableMapOf<UUID, MutableList<EventBackup>>()
		eventRows { rows ->
			rows.filter { it.local }.forEach { byCalendar.getOrPut(it.calendarId) { mutableListOf() } += it.toBackup(exdates) }
		}
		val names = mutableSetOf<String>()
		local.sortedWith(compareBy({ it.name }, { it.id })).forEach { calendar ->
			val base = fileName(calendar.name)
			val name = generateSequence(1) { it + 1 }.map { n -> if (n == 1) base else "$base ($n)" }.first { names.add(it.lowercase()) }
			writer.text("calendar/$name.ics", ics(byCalendar[calendar.id].orEmpty()))
		}
	}

	override fun import(reader: BackupDataReader) {
		val sources = reader.json<List<CalendarSourceBackup>>(SOURCES) ?: return
		val credentials = reader.secret<Map<String, String>>(CREDENTIALS).orEmpty()
		var reconnect = 0
		sources.forEach { source ->
			val credential = credentials[source.id.toString()]
			if (credential == null && source.type != CalendarSourceType.LOCAL.name) reconnect++
			jdbc.update(
				"""insert into calendar_source (id, created_at, updated_at, version, type, name, config, credentials)
				values (?, ?, ?, ?, ?, ?, ?::jsonb, ?)""",
				source.id,
				source.createdAt.toTimestamp(),
				source.updatedAt.toTimestamp(),
				source.version,
				source.type,
				source.name,
				jsonMapper.writeValueAsString(source.config),
				credential?.let(cipher::encrypt),
			)
		}
		reader.json<List<CalendarBackup>>(CALENDARS).orEmpty().forEach { calendar ->
			jdbc.update(
				"""insert into calendar
				(id, created_at, updated_at, version, source_id, name, color, visible, read_only, is_default, external_id, external_etag)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				calendar.id,
				calendar.createdAt.toTimestamp(),
				calendar.updatedAt.toTimestamp(),
				calendar.version,
				calendar.sourceId,
				calendar.name,
				calendar.color,
				calendar.visible,
				calendar.readOnly,
				calendar.isDefault,
				calendar.externalId,
				calendar.externalEtag,
			)
		}
		val restored = mutableListOf<UUID>()
		reader.jsonLines<EventBackup>(EVENTS) { event ->
			val content = event.content
			if (content == null) insertPlaceholder(event) else insert(event, content)
			if (content != null && event.parentEventId == null) restored += event.id
		}
		// Overrides are indexed with their series master.
		restored.forEach { id -> events.findById(id).ifPresent(indexer::index) }
		if (reconnect > 0) log.warn("Restored {} calendar sources without their credentials: they must be reconnected", reconnect)
	}

	override fun restoreWarnings(reader: BackupDataReader): List<String> {
		val external = reader.json<List<CalendarSourceBackup>>(SOURCES).orEmpty().count { it.type != CalendarSourceType.LOCAL.name }
		if (external == 0) return emptyList()
		return listOf("The events of the $external external calendar sources are fetched again from their providers with the next sync")
	}

	private fun insert(
		event: EventBackup,
		content: EventContentBackup,
	) {
		jdbc.update(
			"""insert into calendar_event
			(id, created_at, updated_at, version, deleted_at, calendar_id, title, description, location, all_day, start_at, end_at,
			time_zone, start_date, end_date, rrule, parent_event_id, recurrence_id_at, recurrence_id_date, attendees,
			reminder_minutes, status, transparency, conference_url, external_id, external_etag, sync_state)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)""",
		) { ps ->
			var i = 0
			ps.setObject(++i, event.id)
			ps.setTimestamp(++i, content.createdAt.toTimestamp())
			ps.setTimestamp(++i, content.updatedAt.toTimestamp())
			ps.setLong(++i, content.version)
			ps.setTimestamp(++i, content.deletedAt.toTimestampOrNull())
			ps.setObject(++i, event.calendarId)
			ps.setString(++i, content.title)
			ps.setString(++i, content.description)
			ps.setString(++i, content.location)
			ps.setBoolean(++i, content.allDay)
			ps.setTimestamp(++i, content.startAt.toTimestampOrNull())
			ps.setTimestamp(++i, content.endAt.toTimestampOrNull())
			ps.setString(++i, content.timeZone)
			ps.setDate(++i, content.startDate?.let(Date::valueOf))
			ps.setDate(++i, content.endDate?.let(Date::valueOf))
			ps.setString(++i, content.rrule)
			ps.setObject(++i, event.parentEventId)
			ps.setTimestamp(++i, event.recurrenceIdAt.toTimestampOrNull())
			ps.setDate(++i, event.recurrenceIdDate?.let(Date::valueOf))
			ps.setString(++i, jsonMapper.writeValueAsString(content.attendees))
			ps.setArray(++i, ps.connection.createArrayOf("integer", content.reminderMinutes.toTypedArray()))
			ps.setString(++i, content.status)
			ps.setString(++i, content.transparency)
			ps.setString(++i, content.conferenceUrl)
			ps.setString(++i, event.externalId)
			ps.setString(++i, content.externalEtag)
			ps.setString(++i, content.syncState)
		}
		content.exdates.forEach {
			jdbc.update(
				"insert into calendar_event_exdate (event_id, occurrence_at, occurrence_date) values (?, ?, ?)",
				event.id,
				it.at.toTimestampOrNull(),
				it.date?.let(Date::valueOf),
			)
		}
	}

	/** A ref of an external event as a row the next sync fills in (see the class): timed at its occurrence or the epoch. */
	private fun insertPlaceholder(event: EventBackup) {
		val allDay = event.recurrenceIdDate != null
		val start = event.recurrenceIdAt ?: Instant.EPOCH
		jdbc.update(
			"""insert into calendar_event
			(id, created_at, updated_at, version, calendar_id, title, all_day, start_at, end_at, time_zone, start_date, end_date,
			parent_event_id, recurrence_id_at, recurrence_id_date, status, transparency, external_id, sync_state)
			values (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
			event.id,
			Instant.EPOCH.toTimestamp(),
			Instant.EPOCH.toTimestamp(),
			event.calendarId,
			PLACEHOLDER_TITLE,
			allDay,
			if (allDay) null else start.toTimestamp(),
			if (allDay) null else start.toTimestamp(),
			if (allDay) null else ZoneOffset.UTC.id,
			event.recurrenceIdDate?.let(Date::valueOf),
			event.recurrenceIdDate?.plusDays(1)?.let(Date::valueOf),
			event.parentEventId,
			event.recurrenceIdAt.toTimestampOrNull(),
			event.recurrenceIdDate?.let(Date::valueOf),
			EventStatus.CONFIRMED.name,
			EventTransparency.OPAQUE.name,
			event.externalId,
			EventSyncState.SYNCED.name,
		)
	}

	private fun calendars(): List<CalendarBackup> =
		jdbc.query("select * from calendar order by id") { rs, _ ->
			CalendarBackup(
				id = rs.uuid("id"),
				createdAt = rs.instant("created_at"),
				updatedAt = rs.instant("updated_at"),
				version = rs.getLong("version"),
				sourceId = rs.uuid("source_id"),
				name = rs.getString("name"),
				color = rs.getString("color"),
				visible = rs.getBoolean("visible"),
				readOnly = rs.getBoolean("read_only"),
				isDefault = rs.getBoolean("is_default"),
				externalId = rs.getString("external_id"),
				externalEtag = rs.getString("external_etag"),
			)
		}

	private fun localSources(): Set<UUID> =
		jdbc.query("select id from calendar_source where type = ?", { rs, _ -> rs.uuid("id") }, CalendarSourceType.LOCAL.name).toSet()

	/** The EXDATEs of the events of LOCAL sources, per event, sorted. */
	private fun exdates(): Map<UUID, List<ExdateBackup>> {
		val result = mutableMapOf<UUID, MutableList<ExdateBackup>>()
		jdbc.query(
			"""select x.* from calendar_event_exdate x
			join calendar_event e on e.id = x.event_id join calendar c on c.id = e.calendar_id
			join calendar_source s on s.id = c.source_id
			where s.type = ? order by x.event_id, x.occurrence_date nulls first, x.occurrence_at nulls first""",
			RowCallbackHandler { rs ->
				result.getOrPut(rs.uuid("event_id")) { mutableListOf() } +=
					ExdateBackup(rs.instantOrNull("occurrence_at"), rs.getObject("occurrence_date", LocalDate::class.java))
			},
			CalendarSourceType.LOCAL.name,
		)
		return result
	}

	/** Every event, series masters and single events first, streamed from the database. */
	private fun <R> eventRows(block: (Sequence<EventRow>) -> R): R =
		jdbc
			.queryForStream(
				PreparedStatementCreator { connection ->
					connection
						.prepareStatement(
							"""select e.*, s.type = ? as is_local from calendar_event e
							join calendar c on c.id = e.calendar_id join calendar_source s on s.id = c.source_id
							order by e.parent_event_id is not null, e.id""",
						).apply {
							setString(1, CalendarSourceType.LOCAL.name)
							fetchSize = FETCH_SIZE
						}
				},
			) { rs, _ -> EventRow(rs, rs.getBoolean("is_local")) }
			.use { block(it.iterator().asSequence()) }

	/** One row of `calendar_event`, read while the result set is on it. */
	private inner class EventRow(
		rs: ResultSet,
		val local: Boolean,
	) {
		val id = rs.uuid("id")
		val calendarId = rs.uuid("calendar_id")
		val externalId: String? = rs.getString("external_id")
		val parentEventId: UUID? = rs.getObject("parent_event_id", UUID::class.java)
		val recurrenceIdAt = rs.instantOrNull("recurrence_id_at")
		val recurrenceIdDate: LocalDate? = rs.getObject("recurrence_id_date", LocalDate::class.java)
		val content =
			if (!local) {
				null
			} else {
				EventContentBackup(
					createdAt = rs.instant("created_at"),
					updatedAt = rs.instant("updated_at"),
					version = rs.getLong("version"),
					deletedAt = rs.instantOrNull("deleted_at"),
					title = rs.getString("title"),
					description = rs.getString("description"),
					location = rs.getString("location"),
					allDay = rs.getBoolean("all_day"),
					startAt = rs.instantOrNull("start_at"),
					endAt = rs.instantOrNull("end_at"),
					timeZone = rs.getString("time_zone"),
					startDate = rs.getObject("start_date", LocalDate::class.java),
					endDate = rs.getObject("end_date", LocalDate::class.java),
					rrule = rs.getString("rrule"),
					attendees = jsonMapper.readTree(rs.getString("attendees")),
					reminderMinutes = (rs.getArray("reminder_minutes").array as Array<*>).map { (it as Number).toInt() },
					status = rs.getString("status"),
					transparency = rs.getString("transparency"),
					conferenceUrl = rs.getString("conference_url"),
					externalEtag = rs.getString("external_etag"),
					syncState = rs.getString("sync_state"),
					exdates = emptyList(),
				)
			}

		fun toBackup(exdates: Map<UUID, List<ExdateBackup>>) =
			EventBackup(
				id = id,
				calendarId = calendarId,
				externalId = externalId,
				parentEventId = parentEventId,
				recurrenceIdAt = recurrenceIdAt,
				recurrenceIdDate = recurrenceIdDate,
				content = content?.copy(exdates = exdates[id].orEmpty()),
			)
	}

	/** A VCALENDAR with [events] (of one calendar) not in the Trash; overrides of a series in the Trash are left out too. */
	private fun ics(events: List<EventBackup>): String {
		val calendar = CalDavEventMapping.newCalendar()
		val active = events.filter { it.content!!.deletedAt == null }
		val masters = active.filter { it.parentEventId == null }.associateBy { it.id }
		masters.values.forEach { CalDavEventMapping.write(calendar, remote(it, it.id.toString()), it.content!!.updatedAt) }
		active
			.filter { it.parentEventId in masters }
			.forEach { CalDavEventMapping.write(calendar, remote(it, it.parentEventId.toString()), it.content!!.updatedAt) }
		return calendar.format()
	}

	private fun remote(
		event: EventBackup,
		uid: String,
	): RemoteEvent {
		val content = event.content!!
		val recurrenceId = if (event.parentEventId == null) null else OccurrenceId(event.recurrenceIdAt, event.recurrenceIdDate)
		return RemoteEvent(
			key = RemoteEventKey(uid, recurrenceId),
			etag = null,
			title = content.title,
			timing =
				if (content.allDay) {
					Timing.AllDay(content.startDate!!, content.endDate!!)
				} else {
					Timing.Timed(content.startAt!!, content.endAt!!, ZoneId.of(content.timeZone))
				},
			description = content.description,
			location = content.location,
			rrule = content.rrule,
			exdates = content.exdates.map { OccurrenceId(it.at, it.date) }.toSet(),
			attendees = jsonMapper.treeToValue(content.attendees, ATTENDEES),
			reminderMinutes = content.reminderMinutes,
			status = EventStatus.valueOf(content.status),
			transparency = EventTransparency.valueOf(content.transparency),
			conferenceUrl = content.conferenceUrl,
		)
	}

	companion object {
		const val ID = "calendar"
		const val SOURCES = "calendar_sources"
		const val CALENDARS = "calendars"
		const val EVENTS = "events"

		/** Name of the source credentials (source id → credential) in the backup's secrets. */
		const val CREDENTIALS = "calendar_source_credentials"

		/** Title of a restored external event until the next sync fills it in. */
		const val PLACEHOLDER_TITLE = "(restored, waiting for sync)"

		private const val FETCH_SIZE = 1000
		private const val MAX_FILE_NAME_LENGTH = 100
		private val NOT_IN_FILE_NAME = Regex("""[/\\:*?"<>|\p{Cntrl}]""")
		private val ATTENDEES = object : TypeReference<List<Attendee>>() {}

		/** [name] usable as a file name on common systems: reserved characters become `_`, at most 100 characters. */
		fun fileName(name: String): String =
			name
				.replace(NOT_IN_FILE_NAME, "_")
				.trim()
				.trimStart('.')
				.take(MAX_FILE_NAME_LENGTH)
				.trim()
				.ifEmpty { "calendar" }
	}
}

/** One source in `data/calendar_sources.json`; its credentials are in the backup's secrets only. */
data class CalendarSourceBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val type: String,
	val name: String,
	/** The provider's non-secret settings, a JSON object. */
	val config: JsonNode,
)

/** One calendar in `data/calendars.json`. */
data class CalendarBackup(
	val id: UUID,
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val sourceId: UUID,
	val name: String,
	val color: String?,
	val visible: Boolean,
	val readOnly: Boolean,
	val isDefault: Boolean,
	val externalId: String?,
	val externalEtag: String?,
)

/**
 * One line of `data/events.jsonl`: the identity of an event (an override has [parentEventId] and exactly one recurrence
 * id), with its [content] for events of LOCAL sources; null for external ones (a ref, see [CalendarBackupContributor]).
 */
data class EventBackup(
	val id: UUID,
	val calendarId: UUID,
	val externalId: String?,
	val parentEventId: UUID?,
	val recurrenceIdAt: Instant?,
	val recurrenceIdDate: LocalDate?,
	val content: EventContentBackup?,
)

/** Everything of an event of a LOCAL source but its identity ([EventBackup]). Enums by name, as stored. */
data class EventContentBackup(
	val createdAt: Instant,
	val updatedAt: Instant,
	val version: Long,
	val deletedAt: Instant?,
	val title: String,
	val description: String?,
	val location: String?,
	val allDay: Boolean,
	val startAt: Instant?,
	val endAt: Instant?,
	val timeZone: String?,
	val startDate: LocalDate?,
	/** Exclusive. */
	val endDate: LocalDate?,
	val rrule: String?,
	/** JSON array of `{email, name, status}`, as stored. */
	val attendees: JsonNode,
	val reminderMinutes: List<Int>,
	val status: String,
	val transparency: String,
	val conferenceUrl: String?,
	val externalEtag: String?,
	val syncState: String,
	/** Sorted: instants, then all-day dates. */
	val exdates: List<ExdateBackup>,
)

/** An excluded occurrence of a series: [at] for a timed series, [date] for an all-day one. */
data class ExdateBackup(
	val at: Instant?,
	val date: LocalDate?,
)
