package ch.sonensei.cockpit.gtd.projects

import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** The form's pure rules: which fields a save sends, how a reloaded project is merged with the user's edits. */
class ProjectEditingTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val home = id(90)
    private val work = id(91)
    private val holiday =
        GtdProject(
            id(10),
            "Plan holiday",
            ProjectStatus.ACTIVE,
            stalled = false,
            version = 2,
            areaId = home,
            dueDate = LocalDate.of(2026, 12, 1),
            reviewDate = LocalDate.of(2026, 11, 15),
            descriptionMd = "Two weeks in **Sicily**",
        )
    private val initial = ProjectDraft.of(holiday)

    @Test
    fun `a draft of a project holds its editable fields, a missing description is empty`() {
        assertEquals(
            ProjectDraft(
                title = "Plan holiday",
                status = ProjectStatus.ACTIVE,
                areaId = home,
                dueDate = LocalDate.of(2026, 12, 1),
                reviewDate = LocalDate.of(2026, 11, 15),
                description = "Two weeks in **Sicily**",
            ),
            initial,
        )
        assertEquals("", ProjectDraft.of(holiday.copy(descriptionMd = null)).description)
    }

    @Test
    fun `the edit form starts at the project's version with nothing changed`() {
        val form = ProjectFormState.edit(holiday)
        assertEquals(holiday.id, form.projectId)
        assertEquals(2L, form.version)
        assertEquals(form.initial, form.draft)
        assertFalse(form.isNew)
        assertTrue(ProjectFormState().isNew)
    }

    @Test
    fun `an unchanged draft sends an empty patch`() {
        assertEquals(JsonObject(emptyMap()), projectPatch(initial, initial))
    }

    @Test
    fun `a title padded with blanks that is otherwise unchanged sends nothing`() {
        assertEquals(JsonObject(emptyMap()), projectPatch(initial, initial.copy(title = "  Plan holiday\t")))
    }

    @Test
    fun `a changed title is sent trimmed, alone`() {
        assertEquals(
            JsonObject(mapOf("title" to JsonPrimitive("Plan the holiday"))),
            projectPatch(initial, initial.copy(title = "  Plan the holiday ")),
        )
    }

    @Test
    fun `every changed field is sent in the server's format`() {
        val draft =
            initial.copy(
                status = ProjectStatus.ON_HOLD,
                areaId = work,
                dueDate = LocalDate.of(2027, 1, 2),
                reviewDate = LocalDate.of(2026, 12, 20),
                description = "Ten days in Sardinia",
            )
        assertEquals(
            JsonObject(
                mapOf(
                    "status" to JsonPrimitive("ON_HOLD"),
                    "areaId" to JsonPrimitive(work.toString()),
                    "dueDate" to JsonPrimitive("2027-01-02"),
                    "reviewDate" to JsonPrimitive("2026-12-20"),
                    "descriptionMd" to JsonPrimitive("Ten days in Sardinia"),
                ),
            ),
            projectPatch(initial, draft),
        )
    }

    @Test
    fun `emptied fields are cleared with an explicit null`() {
        val draft = initial.copy(areaId = null, dueDate = null, reviewDate = null, description = "")
        assertEquals(
            JsonObject(
                mapOf(
                    "areaId" to JsonNull,
                    "dueDate" to JsonNull,
                    "reviewDate" to JsonNull,
                    "descriptionMd" to JsonNull,
                ),
            ),
            projectPatch(initial, draft),
        )
    }

    @Test
    fun `a description reduced to blanks clears it`() {
        assertEquals(JsonObject(mapOf("descriptionMd" to JsonNull)), projectPatch(initial, initial.copy(description = "  \n ")))
    }

    @Test
    fun `fields set on a project that had none are sent`() {
        val bare = ProjectDraft("Redo the garden")
        val draft = bare.copy(areaId = home, dueDate = LocalDate.of(2027, 4, 1), description = "Raised beds")
        assertEquals(
            JsonObject(
                mapOf(
                    "areaId" to JsonPrimitive(home.toString()),
                    "dueDate" to JsonPrimitive("2027-04-01"),
                    "descriptionMd" to JsonPrimitive("Raised beds"),
                ),
            ),
            projectPatch(bare, draft),
        )
    }

    @Test
    fun `a rebase keeps the user's changed fields and takes the others from the reloaded project, at its version`() {
        val form = ProjectFormState.edit(holiday).copy(saving = true)
        val edited = form.copy(draft = form.draft.copy(title = "Plan the holiday", dueDate = null))
        // Elsewhere: the area, the review date and the description changed.
        val current =
            holiday.copy(
                version = 5,
                areaId = work,
                reviewDate = LocalDate.of(2026, 11, 30),
                descriptionMd = "Two weeks in Sicily and Malta",
            )
        val rebased = edited.rebase(current)
        assertEquals(5L, rebased.version)
        assertEquals(ProjectDraft.of(current), rebased.initial)
        assertEquals(
            ProjectDraft(
                title = "Plan the holiday",
                status = ProjectStatus.ACTIVE,
                areaId = work,
                dueDate = null,
                reviewDate = LocalDate.of(2026, 11, 30),
                description = "Two weeks in Sicily and Malta",
            ),
            rebased.draft,
        )
        // Saving again sends only the user's changes against the current version.
        assertEquals(
            JsonObject(mapOf("title" to JsonPrimitive("Plan the holiday"), "dueDate" to JsonNull)),
            projectPatch(rebased.initial, rebased.draft),
        )
    }

    @Test
    fun `a rebase where the user changed the same field as someone else keeps the user's value`() {
        val form = ProjectFormState.edit(holiday)
        val edited = form.copy(draft = form.draft.copy(status = ProjectStatus.SOMEDAY))
        val rebased = edited.rebase(holiday.copy(version = 3, status = ProjectStatus.ON_HOLD))
        assertEquals(ProjectStatus.SOMEDAY, rebased.draft.status)
        assertEquals(JsonObject(mapOf("status" to JsonPrimitive("SOMEDAY"))), projectPatch(rebased.initial, rebased.draft))
    }

    @Test
    fun `a rebase without user changes shows the reloaded project unchanged`() {
        val current = holiday.copy(version = 4, title = "Plan the summer holiday", areaId = null)
        val rebased = ProjectFormState.edit(holiday).rebase(current)
        assertEquals(ProjectDraft.of(current), rebased.draft)
        assertEquals(JsonObject(emptyMap()), projectPatch(rebased.initial, rebased.draft))
    }

    @Test
    fun `only completing and dropping finish a project`() {
        assertEquals(
            setOf(ProjectStatus.COMPLETED, ProjectStatus.DROPPED),
            ProjectStatus.entries.filter { finishes(it) }.toSet(),
        )
    }
}
