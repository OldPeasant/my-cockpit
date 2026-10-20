package ch.sonensei.cockpit.gtd.data

import ch.sonensei.cockpit.core.network.generated.models.ContextResponse
import ch.sonensei.cockpit.core.network.generated.models.ProjectResponse
import ch.sonensei.cockpit.core.network.generated.models.TaskResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** API answer -> cache row -> domain, as the screens read it back from Room. */
class GtdMappersTest {
    private val at = OffsetDateTime.parse("2026-10-01T08:00:00Z")

    private fun id(n: Int) = UUID.fromString(uuid(n))

    private fun task(
        list: TaskResponse.List = TaskResponse.List.INBOX,
        energy: TaskResponse.Energy? = null,
        creativity: TaskResponse.Creativity? = null,
    ) = TaskResponse(
        contextIds = emptyList(),
        createdAt = at,
        id = id(1),
        list = list,
        position = 3,
        title = "Water the plants",
        updatedAt = at,
        version = 4,
        energy = energy,
        creativity = creativity,
    )

    private fun project(status: ProjectResponse.Status = ProjectResponse.Status.ACTIVE) =
        ProjectResponse(
            createdAt = at,
            id = id(2),
            stalled = false,
            status = status,
            title = "Plan the trip",
            updatedAt = at,
            version = 5,
        )

    @Test
    fun `a task without optional fields maps to nulls and no contexts`() {
        val entity = task().toEntity(TaskView.TODAY, 7)

        assertEquals("TODAY", entity.view)
        assertEquals(7, entity.ordinal)
        assertEquals("", entity.contextIds)
        assertEquals(
            GtdTask(id = id(1), title = "Water the plants", list = TaskList.INBOX, position = 3, version = 4),
            entity.toDomain(),
        )
    }

    @Test
    fun `a single context id survives the comma-separated column`() {
        val entity = task().copy(contextIds = listOf(id(80))).toEntity(TaskView.NEXT, 0)

        assertEquals(listOf(id(80)), entity.toDomain().contextIds)
    }

    @Test
    fun `every list, energy and creativity value of the API has a domain value`() {
        TaskResponse.List.entries.forEach { list ->
            assertEquals(
                list.name,
                task(list = list)
                    .toEntity(TaskView.INBOX, 0)
                    .toDomain()
                    .list.name,
            )
        }
        TaskResponse.Energy.entries.forEach { energy ->
            assertEquals(
                energy.name,
                task(energy = energy)
                    .toEntity(TaskView.INBOX, 0)
                    .toDomain()
                    .energy
                    ?.name,
            )
        }
        TaskResponse.Creativity.entries.forEach { creativity ->
            assertEquals(
                creativity.name,
                task(creativity = creativity)
                    .toEntity(TaskView.INBOX, 0)
                    .toDomain()
                    .creativity
                    ?.name,
            )
        }
    }

    @Test
    fun `a project maps all fields, and nulls when they are absent`() {
        val full =
            project(ProjectResponse.Status.ON_HOLD).copy(
                stalled = true,
                areaId = id(10),
                nextTaskId = id(11),
                dueDate = LocalDate.of(2026, 11, 30),
                reviewDate = LocalDate.of(2026, 11, 1),
                descriptionMd = "Pack *light*",
            )

        assertEquals(
            GtdProject(
                id = id(2),
                title = "Plan the trip",
                status = ProjectStatus.ON_HOLD,
                stalled = true,
                version = 5,
                areaId = id(10),
                nextTaskId = id(11),
                dueDate = LocalDate.of(2026, 11, 30),
                reviewDate = LocalDate.of(2026, 11, 1),
                descriptionMd = "Pack *light*",
            ),
            full.toEntity(0).toDomain(),
        )
        assertEquals(
            GtdProject(id = id(2), title = "Plan the trip", status = ProjectStatus.ACTIVE, stalled = false, version = 5),
            project().toEntity(1).toDomain(),
        )
    }

    @Test
    fun `every project status of the API has a domain value`() {
        ProjectResponse.Status.entries.forEach { status ->
            assertEquals(
                status.name,
                project(status)
                    .toEntity(0)
                    .toDomain()
                    .status.name,
            )
        }
    }

    @Test
    fun `a context without a color maps to null`() {
        val context = ContextResponse(id = id(3), name = "@errands", position = 2, version = 1)

        val entity = context.toEntity(4)

        assertEquals(4, entity.ordinal)
        assertEquals(GtdContext(id = id(3), name = "@errands", position = 2, version = 1, color = null), entity.toDomain())
    }
}
