package ch.sonensei.cockpit.gtd.data

import ch.sonensei.cockpit.core.network.generated.models.ContextResponse
import ch.sonensei.cockpit.core.network.generated.models.ProjectResponse
import ch.sonensei.cockpit.core.network.generated.models.TaskResponse
import java.time.LocalDate
import java.util.UUID

internal fun TaskResponse.toEntity(
    view: TaskView,
    ordinal: Int,
) = CachedTaskEntity(
    view = view.name,
    id = id.toString(),
    ordinal = ordinal,
    title = title,
    list = list.name,
    position = position,
    version = version,
    projectId = projectId?.toString(),
    contextIds = contextIds.joinToString(","),
    dueDate = dueDate?.toString(),
    dueTime = dueTime,
    scheduledDate = scheduledDate?.toString(),
    energy = energy?.name,
    creativity = creativity?.name,
    timeEstimateMin = timeEstimateMin,
    waitingForText = waitingForText,
    waitingSince = waitingSince?.toString(),
    notesMd = notesMd,
)

internal fun CachedTaskEntity.toDomain() =
    GtdTask(
        id = UUID.fromString(id),
        title = title,
        list = TaskList.valueOf(list),
        position = position,
        version = version,
        projectId = projectId?.let(UUID::fromString),
        contextIds = contextIds.split(',').filter { it.isNotEmpty() }.map(UUID::fromString),
        dueDate = dueDate?.let(LocalDate::parse),
        dueTime = dueTime,
        scheduledDate = scheduledDate?.let(LocalDate::parse),
        energy = energy?.let(Energy::valueOf),
        creativity = creativity?.let(Creativity::valueOf),
        timeEstimateMin = timeEstimateMin,
        waitingForText = waitingForText,
        waitingSince = waitingSince?.let(LocalDate::parse),
        notesMd = notesMd,
    )

internal fun ProjectResponse.toEntity(ordinal: Int) =
    CachedProjectEntity(
        id = id.toString(),
        ordinal = ordinal,
        title = title,
        status = status.name,
        stalled = stalled,
        version = version,
        areaId = areaId?.toString(),
        nextTaskId = nextTaskId?.toString(),
        dueDate = dueDate?.toString(),
        reviewDate = reviewDate?.toString(),
        descriptionMd = descriptionMd,
    )

internal fun CachedProjectEntity.toDomain() =
    GtdProject(
        id = UUID.fromString(id),
        title = title,
        status = ProjectStatus.valueOf(status),
        stalled = stalled,
        version = version,
        areaId = areaId?.let(UUID::fromString),
        nextTaskId = nextTaskId?.let(UUID::fromString),
        dueDate = dueDate?.let(LocalDate::parse),
        reviewDate = reviewDate?.let(LocalDate::parse),
        descriptionMd = descriptionMd,
    )

internal fun ContextResponse.toEntity(ordinal: Int) =
    CachedContextEntity(
        id = id.toString(),
        ordinal = ordinal,
        name = name,
        position = position,
        version = version,
        color = color,
    )

internal fun CachedContextEntity.toDomain() =
    GtdContext(
        id = UUID.fromString(id),
        name = name,
        position = position,
        version = version,
        color = color,
    )
