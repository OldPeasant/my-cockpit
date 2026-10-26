package ch.sonensei.cockpit.calendar.itip

import ch.sonensei.cockpit.calendar.model.OccurrenceId
import ch.sonensei.cockpit.core.persistence.UuidV7
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The SEQUENCE and DTSTAMP of the newest iTIP REPLY taken from attendee [email] (lower case) for event [eventId] (the
 * series master or single event) and the answered occurrence (none: the whole event). A REPLY older than this arrived
 * late and is ignored (RFC 5546 §2.1.5).
 */
@Entity
@Table(name = "calendar_itip_reply")
class ItipReplyStamp(
	@Column(name = "event_id", nullable = false, updatable = false)
	val eventId: UUID,
	@Column(name = "recurrence_id_at", updatable = false)
	val recurrenceIdAt: Instant?,
	@Column(name = "recurrence_id_date", updatable = false)
	val recurrenceIdDate: LocalDate?,
	@Column(name = "email", nullable = false, updatable = false)
	val email: String,
	@Column(name = "sequence")
	var sequence: Int?,
	@Column(name = "stamp")
	var stamp: Instant?,
	@Id
	@Column(name = "id", nullable = false, updatable = false)
	val id: UUID = UuidV7.generate(),
)

interface ItipReplyStampRepository : JpaRepository<ItipReplyStamp, UUID> {
	// Derived queries compare a null parameter with IS NULL.
	fun findByEventIdAndRecurrenceIdAtAndRecurrenceIdDateAndEmail(
		eventId: UUID,
		recurrenceIdAt: Instant?,
		recurrenceIdDate: LocalDate?,
		email: String,
	): ItipReplyStamp?
}

/** The stamp of the newest REPLY taken from [email] for [eventId] and [occurrence], null when none was taken. */
fun ItipReplyStampRepository.find(
	eventId: UUID,
	occurrence: OccurrenceId?,
	email: String,
): ItipReplyStamp? = findByEventIdAndRecurrenceIdAtAndRecurrenceIdDateAndEmail(eventId, occurrence?.at, occurrence?.date, email.lowercase())
