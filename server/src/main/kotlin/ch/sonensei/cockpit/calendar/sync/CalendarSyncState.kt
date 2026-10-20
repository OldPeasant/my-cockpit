package ch.sonensei.cockpit.calendar.sync

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * The sync status of an external calendar source: last attempt, last success and the error of the last attempt (null
 * after a successful one). Created by the first sync.
 */
@Entity
@Table(name = "calendar_source_sync")
class SourceSyncStatus(
	@Id
	@Column(name = "source_id", nullable = false, updatable = false)
	val sourceId: UUID,
	@Column(name = "last_attempt_at", nullable = false)
	var lastAttemptAt: Instant,
) {
	@Column(name = "last_success_at")
	var lastSuccessAt: Instant? = null

	@Column(name = "last_error")
	var lastError: String? = null
}

/** The provider's incremental sync cursor of a calendar; no row means the next fetch is a full one. */
@Entity
@Table(name = "calendar_sync_cursor")
class CalendarSyncCursor(
	@Id
	@Column(name = "calendar_id", nullable = false, updatable = false)
	val calendarId: UUID,
	@Column(name = "cursor", nullable = false)
	var cursor: String,
)

interface SourceSyncStatusRepository : JpaRepository<SourceSyncStatus, UUID>

interface CalendarSyncCursorRepository : JpaRepository<CalendarSyncCursor, UUID>
