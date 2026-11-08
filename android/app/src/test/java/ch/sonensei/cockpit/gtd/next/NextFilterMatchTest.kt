package ch.sonensei.cockpit.gtd.next

import ch.sonensei.cockpit.core.settings.SettingsLoadResult
import ch.sonensei.cockpit.core.settings.SettingsUpdateResult
import ch.sonensei.cockpit.core.settings.UserSettings
import ch.sonensei.cockpit.core.settings.UserSettingsChange
import ch.sonensei.cockpit.core.settings.UserSettingsRepository
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.UUID

/** [NextFilter.matches] for the new due and area/project filters, the due windows at edges, and [PresetSettings.of]. */
class NextFilterMatchTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 11, 8)
    private val home = id(90)
    private val work = id(91)
    private val garden = GtdProject(id(50), "Garden", ProjectStatus.ACTIVE, false, 1, areaId = home)
    private val loose = GtdProject(id(52), "Loose ends", ProjectStatus.ACTIVE, false, 1)
    private val byId = listOf(garden, loose).associateBy { it.id }

    private fun task(
        projectId: UUID? = null,
        dueDate: LocalDate? = null,
    ) = GtdTask(id(1), "Plant tulips", TaskList.NEXT, 0, 1, projectId = projectId, dueDate = dueDate)

    @Test
    fun `an area filter matches only tasks whose cached project is in that area`() {
        val filter = NextFilter(areaId = home)
        assertTrue(filter.matches(task(garden.id), today, byId))
        assertFalse(filter.matches(task(), today, byId))
        // The task's project is not in the cache (e.g. created on another device, not yet synced).
        assertFalse(filter.matches(task(id(99)), today, byId))
        // A project without an area.
        assertFalse(filter.matches(task(loose.id), today, byId))
        assertFalse(NextFilter(areaId = work).matches(task(garden.id), today, byId))
        // Without the project cache no task is known to be in the area.
        assertFalse(filter.matches(task(garden.id), today))
    }

    @Test
    fun `a project filter does not need the project cache`() {
        assertTrue(NextFilter(projectId = garden.id).matches(task(garden.id), today))
        assertFalse(NextFilter(projectId = garden.id).matches(task(), today))
        assertTrue(NextFilter(projectId = id(99)).matches(task(id(99)), today))
    }

    @Test
    fun `an area with a project of another area matches nothing`() {
        val filter = NextFilter(areaId = work, projectId = garden.id)
        assertFalse(filter.matches(task(garden.id), today, byId))
    }

    @Test
    fun `a due filter needs a due date`() {
        DueWindow.entries.forEach { window ->
            assertFalse(window.name, NextFilter(due = window).matches(task(), today))
        }
        assertTrue(NextFilter(due = DueWindow.OVERDUE).matches(task(dueDate = today.minusYears(1)), today))
        assertTrue(NextFilter().matches(task(), today))
    }

    @Test
    fun `the week window runs over a month and year end`() {
        val endOfYear = LocalDate.of(2026, 12, 29)
        assertTrue(DueWindow.WEEK.contains(LocalDate.of(2027, 1, 4), endOfYear))
        assertFalse(DueWindow.WEEK.contains(LocalDate.of(2027, 1, 5), endOfYear))
        assertTrue(DueWindow.OVERDUE.contains(LocalDate.of(2026, 12, 28), endOfYear))
        val leap = LocalDate.of(2028, 2, 28)
        assertTrue(DueWindow.WEEK.contains(LocalDate.of(2028, 2, 29), leap))
        assertTrue(DueWindow.WEEK.contains(LocalDate.of(2028, 3, 5), leap))
        assertFalse(DueWindow.WEEK.contains(LocalDate.of(2028, 3, 6), leap))
    }

    @Test
    fun `due windows are read only from their exact wire names`() {
        assertEquals(DueWindow.OVERDUE, DueWindow.of("overdue"))
        assertEquals(DueWindow.TODAY, DueWindow.of("today"))
        assertNull(DueWindow.of("TODAY"))
        assertNull(DueWindow.of(null))
        assertNull(DueWindow.of(""))
    }

    @Test
    fun `every filter counts as active`() {
        val all = NextFilter(id(70), Energy.LOW, Creativity.MIXED, 15, home, garden.id, DueWindow.TODAY)
        assertEquals(7, all.activeCount)
        assertEquals(0, NextFilter().activeCount)
    }

    @Test
    fun `blanks around a preset's name and filter values are trimmed`() {
        val text =
            """{"name":" Calls ","filter":{"area":" $home ","due":" today ","maxMinutes":" 15 ","energy":"LOW "}}"""
        assertEquals(
            NextPreset("Calls", NextFilter(energy = Energy.LOW, maxMinutes = 15, areaId = home, due = DueWindow.TODAY)),
            NextPresets.parse(text),
        )
    }

    @Test
    fun `fractional, negative and non-numeric estimates in a preset are left out`() {
        listOf("15.5", "-5", "\"abc\"", "true", "null").forEach { value ->
            val parsed = NextPresets.parse("""{"name":"Odd","filter":{"maxMinutes":$value}}""")
            assertNull(value, parsed?.filter?.maxMinutes)
        }
    }

    @Test
    fun `a preset whose name or filter has the wrong JSON type is handled`() {
        assertNull(NextPresets.parse("""{"name":7,"filter":{}}"""))
        assertNull(NextPresets.parse("\"just text\""))
        assertNull(NextPresets.parse(""))
        assertEquals(NextPreset("List", NextFilter()), NextPresets.parse("""{"name":"List","filter":[1,2]}"""))
    }

    @Test
    fun `saving under a new name appends, the same name in another case replaces only the first match`() {
        val a = NextPresets.encode(NextPreset("Calls", NextFilter()))
        val b = NextPresets.encode(NextPreset("CALLS", NextFilter(due = DueWindow.TODAY)))
        val saved = NextPresets.saved(listOf(a, b), NextPreset("calls", NextFilter(due = DueWindow.WEEK)))
        assertEquals(2, saved.size)
        assertEquals(NextPreset("calls", NextFilter(due = DueWindow.WEEK)), NextPresets.parse(saved[0]))
        assertEquals(b, saved[1])
        // Removing drops every preset of that name, in any case.
        assertEquals(emptyList<String>(), NextPresets.removed(listOf(a, b), "calls"))
        val fresh = NextPreset("New", NextFilter())
        assertEquals(listOf(NextPresets.encode(fresh)), NextPresets.saved(emptyList(), fresh))
    }

    @Test
    fun `the settings repository backs the presets`() =
        runTest {
            val texts = listOf("""{"name":"A","filter":{}}""")
            val state = MutableStateFlow<UserSettings?>(null)
            val repository =
                mockk<UserSettingsRepository> {
                    every { settings } returns state
                    coEvery { refresh() } answers {
                        state.value = UserSettings(nextActionPresets = texts)
                        SettingsLoadResult.LOADED
                    }
                    coEvery { update(any()) } returns SettingsUpdateResult.Updated
                }
            val presets = PresetSettings.of(repository)

            assertNull(presets.current())
            assertNull(presets.texts.first())
            presets.ensureLoaded()
            assertEquals(texts, presets.current())
            assertEquals(texts, presets.texts.first())
            // Already loaded: no second request.
            presets.ensureLoaded()
            coVerify(exactly = 1) { repository.refresh() }

            assertEquals(SettingsUpdateResult.Updated, presets.store(emptyList()))
            coVerify { repository.update(UserSettingsChange(nextActionPresets = emptyList())) }
        }
}
