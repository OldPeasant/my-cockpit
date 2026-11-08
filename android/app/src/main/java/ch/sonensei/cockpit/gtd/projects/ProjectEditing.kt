package ch.sonensei.cockpit.gtd.projects

import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import java.util.UUID

/** The longest title the server accepts. */
const val MAX_PROJECT_TITLE = 500

/** The editable fields of a project; an empty [description] means none. */
data class ProjectDraft(
    val title: String = "",
    val status: ProjectStatus = ProjectStatus.ACTIVE,
    val areaId: UUID? = null,
    val dueDate: LocalDate? = null,
    val reviewDate: LocalDate? = null,
    val description: String = "",
) {
    companion object {
        fun of(project: GtdProject) =
            ProjectDraft(
                title = project.title,
                status = project.status,
                areaId = project.areaId,
                dueDate = project.dueDate,
                reviewDate = project.reviewDate,
                description = project.descriptionMd.orEmpty(),
            )
    }
}

/**
 * The project form: a new project ([projectId] `null`) or the edit of one, opened at [version]. [initial] is what the
 * form started from, so saving sends only what the user changed.
 */
data class ProjectFormState(
    val projectId: UUID? = null,
    val version: Long = 0,
    val initial: ProjectDraft = ProjectDraft(),
    val draft: ProjectDraft = initial,
    val saving: Boolean = false,
    /** Saving was tried without a title. */
    val titleMissing: Boolean = false,
    /** The project was changed elsewhere and reloaded: the form now shows the current project with the user's edits. */
    val conflict: Boolean = false,
    /** The description shows rendered instead of as text. */
    val preview: Boolean = false,
) {
    val isNew: Boolean get() = projectId == null

    companion object {
        fun edit(project: GtdProject) = ProjectFormState(project.id, project.version, ProjectDraft.of(project))
    }
}

/** The PATCH body for the fields the user changed from [initial] to [draft] (an emptied field is cleared). */
internal fun projectPatch(
    initial: ProjectDraft,
    draft: ProjectDraft,
): JsonObject {
    val patch = LinkedHashMap<String, JsonElement>()
    if (draft.title.trim() != initial.title) patch["title"] = JsonPrimitive(draft.title.trim())
    if (draft.status != initial.status) patch["status"] = JsonPrimitive(draft.status.name)
    if (draft.areaId != initial.areaId) patch["areaId"] = draft.areaId?.let { JsonPrimitive(it.toString()) } ?: JsonNull
    if (draft.dueDate != initial.dueDate) patch["dueDate"] = draft.dueDate?.let { JsonPrimitive(it.toString()) } ?: JsonNull
    if (draft.reviewDate != initial.reviewDate) patch["reviewDate"] = draft.reviewDate?.let { JsonPrimitive(it.toString()) } ?: JsonNull
    if (draft.description != initial.description) {
        patch["descriptionMd"] = if (draft.description.isBlank()) JsonNull else JsonPrimitive(draft.description)
    }
    return JsonObject(patch)
}

/**
 * The form after its project was reloaded as [current]: the fields the user changed keep the user's value, the others
 * show the current one, and saving again applies only the user's changes to the current version.
 */
internal fun ProjectFormState.rebase(current: GtdProject): ProjectFormState {
    val fresh = ProjectDraft.of(current)

    fun <T> pick(field: ProjectDraft.() -> T): T = if (draft.field() != initial.field()) draft.field() else fresh.field()
    return copy(
        version = current.version,
        initial = fresh,
        draft =
            ProjectDraft(
                title = pick { title },
                status = pick { status },
                areaId = pick { areaId },
                dueDate = pick { dueDate },
                reviewDate = pick { reviewDate },
                description = pick { description },
            ),
    )
}

/** Whether moving to [status] finishes a project (completed or dropped): with open tasks left, that is confirmed first. */
internal fun finishes(status: ProjectStatus): Boolean = status == ProjectStatus.COMPLETED || status == ProjectStatus.DROPPED
