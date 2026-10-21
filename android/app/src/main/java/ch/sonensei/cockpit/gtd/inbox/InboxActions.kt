package ch.sonensei.cockpit.gtd.inbox

import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdTask
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.util.UUID

/**
 * What processing does with an inbox item (SPEC.md §5.3). "To reference" and "To calendar" come with the notes and
 * calendar screens.
 */
sealed interface InboxAction {
    /** Do it now (< 2 min): the item is done. */
    data object Done : InboxAction

    data object Next : InboxAction

    data object Someday : InboxAction

    data class Waiting(
        val forText: String?,
    ) : InboxAction

    data class Schedule(
        val date: LocalDate,
    ) : InboxAction

    /** Hides the item in the Inbox until [date]. */
    data class Tickler(
        val date: LocalDate,
    ) : InboxAction

    /** A next action of an existing project, or of a new one. */
    data class ToProject(
        val project: ProjectChoice,
    ) : InboxAction

    data object Trash : InboxAction
}

sealed interface ProjectChoice {
    data class Existing(
        val id: UUID,
    ) : ProjectChoice

    data class New(
        val title: String,
    ) : ProjectChoice
}

/** The attributes set inline while processing an item, before its action. */
data class Clarification(
    val energy: Energy? = null,
    val creativity: Creativity? = null,
    val timeEstimateMin: Int? = null,
    val contextIds: List<UUID> = emptyList(),
) {
    /** The PATCH that turns [before] into this: only the changed fields, `null` clears one; `null` if nothing changed. */
    fun patchFrom(before: Clarification): JsonObject? {
        val patch = mutableMapOf<String, JsonElement>()
        if (energy != before.energy) patch["energy"] = energy?.let { JsonPrimitive(it.name) } ?: JsonNull
        if (creativity != before.creativity) patch["creativity"] = creativity?.let { JsonPrimitive(it.name) } ?: JsonNull
        if (timeEstimateMin != before.timeEstimateMin) patch["timeEstimateMin"] = timeEstimateMin?.let(::JsonPrimitive) ?: JsonNull
        val contextsChanged = contextIds.toSet() != before.contextIds.toSet()
        if (contextsChanged) patch["contextIds"] = JsonArray(contextIds.map { JsonPrimitive(it.toString()) })
        return if (patch.isEmpty()) null else JsonObject(patch)
    }

    companion object {
        fun of(task: GtdTask) = Clarification(task.energy, task.creativity, task.timeEstimateMin, task.contextIds)
    }
}

/** The outcome of processing an item. */
sealed interface ProcessResult {
    data object Processed : ProcessResult

    /** The server could not be reached; nothing more was changed. */
    data object Offline : ProcessResult

    /** The item was changed elsewhere (or is gone) since it was loaded; the Inbox is reloaded. */
    data object Conflict : ProcessResult

    data class Rejected(
        val status: Int?,
    ) : ProcessResult
}

/** The estimates offered inline, in minutes (as on the web). */
val ESTIMATE_OPTIONS = listOf(5, 15, 30, 60, 120)
