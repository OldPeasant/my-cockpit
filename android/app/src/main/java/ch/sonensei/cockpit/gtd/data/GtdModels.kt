package ch.sonensei.cockpit.gtd.data

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The GTD list a task is on (SPEC.md §5.1). */
enum class TaskList { INBOX, NEXT, WAITING, SOMEDAY, SCHEDULED }

enum class Energy { LOW, MEDIUM, HIGH }

enum class Creativity { ROUTINE, MIXED, CREATIVE }

enum class ProjectStatus { ACTIVE, ON_HOLD, SOMEDAY, COMPLETED, DROPPED }

/** The task views whose last loaded content is cached for offline reading (SPEC.md §9.3). */
enum class TaskView {
    INBOX,
    NEXT,

    /** Overdue tasks, then tasks due today. */
    TODAY,
}

data class GtdTask(
    val id: UUID,
    val title: String,
    val list: TaskList,
    val position: Int,
    val version: Long,
    val projectId: UUID? = null,
    val contextIds: List<UUID> = emptyList(),
    val dueDate: LocalDate? = null,
    /** Local time such as `14:30:00`, as the server sends it. */
    val dueTime: String? = null,
    val scheduledDate: LocalDate? = null,
    val energy: Energy? = null,
    val creativity: Creativity? = null,
    val timeEstimateMin: Int? = null,
    val waitingForText: String? = null,
    val waitingSince: LocalDate? = null,
    val notesMd: String? = null,
)

data class GtdProject(
    val id: UUID,
    val title: String,
    val status: ProjectStatus,
    val stalled: Boolean,
    val version: Long,
    val areaId: UUID? = null,
    val nextTaskId: UUID? = null,
    val dueDate: LocalDate? = null,
    val reviewDate: LocalDate? = null,
    val descriptionMd: String? = null,
)

data class GtdContext(
    val id: UUID,
    val name: String,
    val position: Int,
    val version: Long,
    val color: String? = null,
)

/**
 * What a GTD screen shows: the last loaded [data] and whether the server is unreachable. While [offline], the data is the
 * cached copy from [refreshedAt] and must be shown read-only (with an "offline" banner). [refreshedAt] is `null` when
 * nothing was ever loaded for this view.
 */
data class Cached<T>(
    val data: T,
    val offline: Boolean,
    val refreshedAt: Instant?,
) {
    val readOnly: Boolean get() = offline
}

/** The outcome of loading a view from the server. */
sealed interface RefreshResult {
    /** The server answered; the cache now holds its answer. */
    data object Refreshed : RefreshResult

    /** The server could not be reached (network error or a 5xx from it or its proxy); the cache is unchanged. */
    data object Offline : RefreshResult

    /** The server rejected the request or answered with an unreadable body; the cache is unchanged. */
    data class Failed(
        val status: Int?,
    ) : RefreshResult
}
