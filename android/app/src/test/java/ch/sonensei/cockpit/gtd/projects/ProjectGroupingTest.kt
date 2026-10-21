package ch.sonensei.cockpit.gtd.projects

import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Grouping the project list by area (SPEC.md §5.3) and which projects count as finished. */
class ProjectGroupingTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = Area(id(90), "Work", 0)
    private val home = Area(id(91), "Home", 1)
    private val health = Area(id(92), "Health", 2)

    private fun project(
        n: Int,
        title: String,
        area: Area? = null,
        stalled: Boolean = false,
        status: ProjectStatus = ProjectStatus.ACTIVE,
        areaId: UUID? = area?.id,
    ) = GtdProject(id(n), title, status, stalled = stalled, version = 1, areaId = areaId)

    @Test
    fun `no projects give no groups`() {
        assertEquals(emptyList<ProjectGroup>(), groupByArea(emptyList(), listOf(work, home)))
    }

    @Test
    fun `areas without projects are left out`() {
        val report = project(1, "Annual report", work)
        val groups = groupByArea(listOf(report), listOf(work, home, health))
        assertEquals(listOf(ProjectGroup(work, listOf(report), stalled = 0)), groups)
    }

    @Test
    fun `a project of an unknown area goes to the group without area, after the areas`() {
        val report = project(1, "Annual report", work)
        val orphan = project(2, "Old area project", areaId = id(99))
        val loose = project(3, "Loose project")
        val groups = groupByArea(listOf(orphan, report, loose), listOf(work))
        assertEquals(listOf(work, null), groups.map { it.area })
        assertEquals(listOf(loose, orphan), groups[1].projects)
    }

    @Test
    fun `without any areas everything is in one group without area`() {
        val a = project(1, "Alpha", areaId = id(90))
        val b = project(2, "Beta")
        val groups = groupByArea(listOf(b, a), emptyList())
        assertEquals(listOf(ProjectGroup(null, listOf(a, b), stalled = 0)), groups)
    }

    @Test
    fun `without loaded areas the projects with an area are not shown as having none`() {
        val report = project(1, "Annual report", work, stalled = true)
        val garden = project(2, "Garden", home)
        val loose = project(3, "Loose project")
        val groups = groupByArea(listOf(garden, loose, report), null)
        assertEquals(
            listOf(
                ProjectGroup(null, listOf(report, garden), stalled = 1, areaUnloaded = true),
                ProjectGroup(null, listOf(loose), stalled = 0),
            ),
            groups,
        )
        assertEquals(listOf(ProjectGroup(null, listOf(loose), stalled = 0)), groupByArea(listOf(loose), null))
        assertEquals(emptyList<ProjectGroup>(), groupByArea(emptyList(), null))
    }

    @Test
    fun `areas are ordered by position, not by the order given`() {
        val report = project(1, "Annual report", work)
        val garden = project(2, "Garden", home)
        val run = project(3, "Run a 10k", health)
        val groups = groupByArea(listOf(run, garden, report), listOf(health, home, work))
        assertEquals(listOf(work, home, health), groups.map { it.area })
    }

    @Test
    fun `within a group stalled first, then by status, then by title ignoring case`() {
        val someday = project(1, "a someday", home, status = ProjectStatus.SOMEDAY)
        val onHold = project(2, "B on hold", home, status = ProjectStatus.ON_HOLD)
        val activeLower = project(3, "c active", home)
        val activeUpper = project(4, "B active", home)
        val stalledLate = project(5, "Zebra stalled", home, stalled = true)
        val stalledEarly = project(6, "apple stalled", home, stalled = true)
        val group = groupByArea(listOf(someday, onHold, activeLower, activeUpper, stalledLate, stalledEarly), listOf(home)).single()
        assertEquals(listOf(stalledEarly, stalledLate, activeUpper, activeLower, onHold, someday), group.projects)
        assertEquals(2, group.stalled)
    }

    @Test
    fun `the stalled count is per group`() {
        val groups =
            groupByArea(
                listOf(project(1, "A", work, stalled = true), project(2, "B", home), project(3, "C", stalled = true)),
                listOf(work, home),
            )
        assertEquals(listOf(1, 0, 1), groups.map { it.stalled })
    }

    @Test
    fun `completed and dropped projects are finished, the others are not`() {
        assertTrue(finished(project(1, "Done", status = ProjectStatus.COMPLETED)))
        assertTrue(finished(project(2, "Dropped", status = ProjectStatus.DROPPED)))
        assertFalse(finished(project(3, "Active")))
        assertFalse(finished(project(4, "Held", status = ProjectStatus.ON_HOLD)))
        assertFalse(finished(project(5, "Later", status = ProjectStatus.SOMEDAY)))
    }
}
