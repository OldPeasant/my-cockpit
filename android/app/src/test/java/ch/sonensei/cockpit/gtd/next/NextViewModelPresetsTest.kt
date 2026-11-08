package ch.sonensei.cockpit.gtd.next

import ch.sonensei.cockpit.core.settings.SettingsUpdateResult
import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.gtd.lists.Live
import ch.sonensei.cockpit.gtd.projects.Area
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The due and area/project filters and the saved presets of the Next view model. */
@OptIn(ExperimentalCoroutinesApi::class)
class NextViewModelPresetsTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 11, 8)
    private val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC)
    private val loadedAt = Instant.parse("2026-11-08T09:00:00Z")

    private val home = Area(id(90), "Home", 0)
    private val work = Area(id(91), "Work", 1)
    private val garden = GtdProject(id(50), "Garden", ProjectStatus.ACTIVE, false, 1, areaId = home.id)
    private val report = GtdProject(id(51), "Report", ProjectStatus.ACTIVE, false, 1, areaId = work.id)
    private val old = GtdProject(id(52), "Old", ProjectStatus.COMPLETED, false, 1, areaId = work.id)

    private val overdue = GtdTask(id(1), "Pay the bill", TaskList.NEXT, 0, 1, dueDate = today.minusDays(2))
    private val dueToday = GtdTask(id(2), "Plant tulips", TaskList.NEXT, 1, 1, projectId = garden.id, dueDate = today)
    private val dueSoon =
        GtdTask(id(3), "Draft report", TaskList.NEXT, 2, 1, projectId = report.id, dueDate = today.plusDays(6), energy = Energy.HIGH)
    private val later = GtdTask(id(4), "Book trip", TaskList.NEXT, 3, 1, dueDate = today.plusDays(7))
    private val undated = GtdTask(id(5), "Water plants", TaskList.NEXT, 4, 1, projectId = garden.id)

    private val nextFlow =
        MutableStateFlow(Cached(listOf(overdue, dueToday, dueSoon, later, undated), offline = false, refreshedAt = loadedAt))
    private val tasks = mockk<TaskRepository> { every { observe(TaskView.NEXT) } returns nextFlow }
    private val contexts =
        mockk<ContextRepository> {
            every { observe() } returns MutableStateFlow(Cached(emptyList<GtdContext>(), offline = false, refreshedAt = loadedAt))
        }
    private val projects = MutableStateFlow(Cached(listOf(garden, report, old), offline = false, refreshedAt = loadedAt))
    private val areas = MutableStateFlow(Live(listOf(home, work)))

    /** The fake `nextActionPresets` setting; `null`: not loaded. */
    private val stored = MutableStateFlow<List<String>?>(emptyList())
    private val writes = mutableListOf<List<String>>()
    private var loads = 0
    private var answer: SettingsUpdateResult = SettingsUpdateResult.Updated
    private val settings =
        PresetSettings(
            texts = stored,
            ensureLoaded = { loads++ },
            current = { stored.value },
            store = { texts ->
                writes += texts
                if (answer == SettingsUpdateResult.Updated) stored.value = texts
                answer
            },
        )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(block: suspend (NextViewModel) -> Unit) =
        runTest(UnconfinedTestDispatcher()) {
            val viewModel = NextViewModel(tasks, contexts, mockk(), clock, projects, areas, settings)
            backgroundScope.launch { viewModel.uiState.collect {} }
            block(viewModel)
        }

    private val NextViewModel.state get() = uiState.value
    private val NextViewModel.shown get() = uiState.value.tasks

    @Test
    fun `filters by due window`() =
        test { vm ->
            vm.toggleDue(DueWindow.OVERDUE)
            assertEquals(listOf(overdue), vm.shown)
            vm.toggleDue(DueWindow.TODAY)
            assertEquals(listOf(dueToday), vm.shown)
            vm.toggleDue(DueWindow.WEEK)
            assertEquals(listOf(dueToday, dueSoon), vm.shown)
            assertEquals(1, vm.state.filter.activeCount)
            vm.toggleDue(DueWindow.WEEK)
            assertEquals(5, vm.shown.size)
            vm.toggleDue(DueWindow.TODAY)
            vm.clearDue()
            assertNull(vm.state.filter.due)
        }

    @Test
    fun `filters by area through the task's project and by project`() =
        test { vm ->
            assertEquals(listOf(home, work), vm.state.areas)
            assertEquals(listOf(garden, report), vm.state.projects)
            vm.chooseArea(home.id)
            assertEquals(listOf(dueToday, undated), vm.shown)
            assertEquals(listOf(garden), vm.state.projects)
            vm.chooseProject(garden.id)
            assertEquals(listOf(dueToday, undated), vm.shown)
            assertEquals(2, vm.state.filter.activeCount)
            // Another area drops the project of the old area, which would show nothing.
            vm.chooseArea(work.id)
            assertNull(vm.state.filter.projectId)
            assertEquals(listOf(dueSoon), vm.shown)
            vm.chooseArea(null)
            vm.chooseProject(report.id)
            vm.chooseArea(work.id)
            assertEquals(report.id, vm.state.filter.projectId)
            vm.clearFilter()
            assertEquals(5, vm.shown.size)
        }

    @Test
    fun `saves the current filter as a preset in the shared setting`() =
        test { vm ->
            vm.toggleDue(DueWindow.TODAY)
            vm.chooseArea(home.id)
            vm.openSavePreset()
            assertEquals(PresetEditor(), vm.state.presets.editor)
            vm.savePreset()
            assertEquals(
                PresetMessage.NAME_REQUIRED,
                vm.state.presets.editor
                    ?.message,
            )
            vm.editPresetName("  Garden today ")
            vm.savePreset()
            assertNull(vm.state.presets.editor)
            val preset = NextPreset("Garden today", NextFilter(areaId = home.id, due = DueWindow.TODAY))
            assertEquals(listOf(listOf(NextPresets.encode(preset))), writes)
            assertEquals(listOf(preset), vm.state.presets.presets)
            assertEquals(preset, vm.state.presets.active)
        }

    @Test
    fun `applies a preset and clears the filter when it is applied again`() =
        test { vm ->
            val preset = NextPreset("Soon", NextFilter(due = DueWindow.WEEK, energy = Energy.HIGH))
            stored.value = listOf(NextPresets.encode(preset))
            vm.applyPreset(preset)
            assertEquals(preset.filter, vm.state.filter)
            assertEquals(listOf(dueSoon), vm.shown)
            assertEquals(preset, vm.state.presets.active)
            vm.applyPreset(preset)
            assertEquals(NextFilter(), vm.state.filter)
            assertNull(vm.state.presets.active)
        }

    @Test
    fun `deletes a preset and keeps the others`() =
        test { vm ->
            val a = NextPreset("A", NextFilter(due = DueWindow.TODAY))
            val b = NextPreset("B", NextFilter(due = DueWindow.OVERDUE))
            stored.value = listOf(NextPresets.encode(a), NextPresets.encode(b))
            vm.removePreset(a)
            assertEquals(listOf(listOf(NextPresets.encode(b))), writes)
            assertEquals(listOf(b), vm.state.presets.presets)
            assertNull(vm.state.presets.message)
        }

    @Test
    fun `reports conflicts, offline and refused writes`() =
        test { vm ->
            val a = NextPreset("A", NextFilter())
            stored.value = listOf(NextPresets.encode(a))
            answer = SettingsUpdateResult.Conflict
            vm.removePreset(a)
            assertEquals(PresetMessage.CONFLICT, vm.state.presets.message)
            answer = SettingsUpdateResult.Offline
            vm.toggleDue(DueWindow.TODAY)
            vm.openSavePreset()
            assertNull(vm.state.presets.message)
            vm.editPresetName("Today")
            vm.savePreset()
            assertEquals(PresetEditor("Today", PresetMessage.OFFLINE, saving = false), vm.state.presets.editor)
            answer = SettingsUpdateResult.Rejected
            vm.savePreset()
            assertEquals(
                PresetMessage.FAILED,
                vm.state.presets.editor
                    ?.message,
            )
            vm.cancelSavePreset()
            assertNull(vm.state.presets.editor)
        }

    @Test
    fun `does not write while the settings are not loaded`() =
        test { vm ->
            stored.value = null
            vm.toggleDue(DueWindow.TODAY)
            vm.openSavePreset()
            vm.editPresetName("Today")
            vm.savePreset()
            assertEquals(
                PresetMessage.OFFLINE,
                vm.state.presets.editor
                    ?.message,
            )
            assertEquals(emptyList<List<String>>(), writes)
            // Once at start, once before the write.
            assertEquals(2, loads)
        }

    @Test
    fun `refuses a 51st preset but replaces one of the same name`() =
        test { vm ->
            stored.value = (1..NextPresets.MAX_PRESETS).map { NextPresets.encode(NextPreset("P$it", NextFilter())) }
            vm.toggleDue(DueWindow.TODAY)
            vm.openSavePreset()
            vm.editPresetName("One more")
            vm.savePreset()
            assertEquals(
                PresetMessage.TOO_MANY,
                vm.state.presets.editor
                    ?.message,
            )
            assertEquals(emptyList<List<String>>(), writes)
            vm.editPresetName("p7")
            vm.savePreset()
            assertNull(vm.state.presets.editor)
            assertEquals(NextPresets.MAX_PRESETS, writes.single().size)
            assertEquals(NextPreset("p7", NextFilter(due = DueWindow.TODAY)), NextPresets.parse(writes.single()[6]))
        }

    @Test
    fun `the save dialog starts with the active preset's name`() =
        test { vm ->
            val preset = NextPreset("Soon", NextFilter(due = DueWindow.WEEK))
            stored.value = listOf(NextPresets.encode(preset))
            vm.applyPreset(preset)
            vm.openSavePreset()
            assertEquals(
                "Soon",
                vm.state.presets.editor
                    ?.name,
            )
            vm.editPresetName("x".repeat(NextPresets.MAX_NAME + 10))
            assertEquals(
                NextPresets.MAX_NAME,
                vm.state.presets.editor
                    ?.name
                    ?.length,
            )
        }
}
