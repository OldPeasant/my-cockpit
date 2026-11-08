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
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Edge cases of the Next presets in the view model: stale ids in presets, a context deleted while its preset is active,
 * overlapping saves, settings that load late or fail, and the shared setting changing elsewhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NextViewModelPresetEdgeCasesTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 11, 8)
    private val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC)
    private val loadedAt = Instant.parse("2026-11-08T09:00:00Z")

    private val home = Area(id(90), "Home", 0)
    private val garden = GtdProject(id(50), "Garden", ProjectStatus.ACTIVE, false, 1, areaId = home.id)
    private val phone = GtdContext(id(70), "@phone", 0, 1)

    private val call = GtdTask(id(1), "Call the plumber", TaskList.NEXT, 0, 1, contextIds = listOf(phone.id), dueDate = today)
    private val plant = GtdTask(id(2), "Plant tulips", TaskList.NEXT, 1, 1, projectId = garden.id, dueDate = today)
    private val undated = GtdTask(id(3), "Water plants", TaskList.NEXT, 2, 1, projectId = garden.id)

    private val nextFlow = MutableStateFlow(Cached(listOf(call, plant, undated), offline = false, refreshedAt = loadedAt))
    private val tasks = mockk<TaskRepository> { every { observe(TaskView.NEXT) } returns nextFlow }
    private val contextFlow = MutableStateFlow(Cached(listOf(phone), offline = false, refreshedAt = loadedAt))
    private val contexts = mockk<ContextRepository> { every { observe() } returns contextFlow }
    private val projects = MutableStateFlow(Cached(listOf(garden), offline = false, refreshedAt = loadedAt))
    private val areas = MutableStateFlow(Live(listOf(home)))

    /** The fake `nextActionPresets` setting; `null`: not loaded. */
    private val stored = MutableStateFlow<List<String>?>(emptyList())
    private val writes = mutableListOf<List<String>>()
    private var storeCalls = 0
    private var answer: SettingsUpdateResult = SettingsUpdateResult.Updated

    /** While set, a write waits for it (a save that is still running). */
    private var gate: CompletableDeferred<Unit>? = null
    private var load: suspend () -> Unit = {}
    private var storeFailure: Exception? = null

    private val settings =
        PresetSettings(
            texts = stored,
            ensureLoaded = { load() },
            current = { stored.value },
            store = { texts ->
                storeCalls++
                gate?.await()
                storeFailure?.let { throw it }
                writes += texts
                if (answer == SettingsUpdateResult.Updated) stored.value = texts
                answer
            },
        )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(
        presetSettings: PresetSettings = settings,
        block: suspend (NextViewModel) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val viewModel = NextViewModel(tasks, contexts, mockk(), clock, projects, areas, presetSettings)
        backgroundScope.launch { viewModel.uiState.collect {} }
        block(viewModel)
    }

    private val NextViewModel.state get() = uiState.value
    private val NextViewModel.shown get() = uiState.value.tasks

    private fun NextViewModel.saveAs(name: String) {
        openSavePreset()
        editPresetName(name)
        savePreset()
    }

    @Test
    fun `a preset with an unknown area and project applies, shows nothing and stays active`() =
        test { vm ->
            val stale = NextPreset("Old area", NextFilter(areaId = id(98), projectId = id(99)))
            stored.value = listOf(NextPresets.encode(stale))
            vm.applyPreset(stale)
            assertEquals(stale.filter, vm.state.filter)
            assertEquals(emptyList<GtdTask>(), vm.shown)
            assertEquals(3, vm.state.total)
            assertEquals(emptyList<GtdProject>(), vm.state.projects)
            assertEquals(stale, vm.state.presets.active)
            vm.applyPreset(stale)
            assertEquals(3, vm.shown.size)
        }

    @Test
    fun `a context deleted while its preset is active drops the context filter and the preset is no longer active`() =
        test { vm ->
            val calls = NextPreset("Calls today", NextFilter(contextId = phone.id, due = DueWindow.TODAY))
            stored.value = listOf(NextPresets.encode(calls))
            vm.applyPreset(calls)
            assertEquals(listOf(call), vm.shown)
            assertEquals(calls, vm.state.presets.active)

            contextFlow.value = Cached(emptyList(), offline = false, refreshedAt = loadedAt)

            assertEquals(NextFilter(due = DueWindow.TODAY), vm.state.filter)
            assertEquals(listOf(call, plant), vm.shown)
            assertNull(vm.state.presets.active)
        }

    @Test
    fun `tapping a preset that is not shown as active applies it, also when its context was deleted`() =
        test { vm ->
            val calls = NextPreset("Calls today", NextFilter(contextId = phone.id, due = DueWindow.TODAY))
            stored.value = listOf(NextPresets.encode(calls))
            vm.applyPreset(calls)
            contextFlow.value = Cached(emptyList(), offline = false, refreshedAt = loadedAt)
            // The chip is not selected now, so a tap must apply the preset (what is left of it), not clear the filter.
            assertNull(vm.state.presets.active)
            vm.applyPreset(calls)
            assertEquals(NextFilter(due = DueWindow.TODAY), vm.state.filter)
        }

    @Test
    fun `saving after the chosen context was deleted stores the filter the screen shows`() =
        test { vm ->
            vm.toggleContext(phone.id)
            vm.toggleDue(DueWindow.TODAY)
            contextFlow.value = Cached(emptyList(), offline = false, refreshedAt = loadedAt)
            assertEquals(NextFilter(due = DueWindow.TODAY), vm.state.filter)
            vm.saveAs("Today")
            assertNull(vm.state.presets.editor)
            assertEquals(NextPreset("Today", NextFilter(due = DueWindow.TODAY)), NextPresets.parse(writes.single().single()))
        }

    @Test
    fun `a second save while one is running is ignored`() =
        test { vm ->
            val running = CompletableDeferred<Unit>()
            gate = running
            vm.toggleDue(DueWindow.TODAY)
            vm.saveAs("Today")
            assertEquals(PresetEditor("Today", saving = true), vm.state.presets.editor)
            vm.savePreset()
            vm.savePreset()
            assertEquals(1, storeCalls)
            running.complete(Unit)
            assertNull(vm.state.presets.editor)
            assertEquals(1, writes.size)
        }

    @Test
    fun `cancelling while a save is running keeps the dialog closed when the save fails`() =
        test { vm ->
            val running = CompletableDeferred<Unit>()
            gate = running
            answer = SettingsUpdateResult.Conflict
            vm.toggleDue(DueWindow.TODAY)
            vm.saveAs("Today")
            vm.cancelSavePreset()
            running.complete(Unit)
            assertNull(vm.state.presets.editor)
        }

    @Test
    fun `a save that finishes after the dialog was cancelled and opened again leaves the new dialog open`() =
        test { vm ->
            val running = CompletableDeferred<Unit>()
            gate = running
            vm.toggleDue(DueWindow.TODAY)
            vm.saveAs("Today")
            vm.cancelSavePreset()
            vm.openSavePreset()
            vm.editPresetName("Other")
            running.complete(Unit)
            assertEquals(PresetEditor("Other"), vm.state.presets.editor)
            assertEquals(
                listOf("Today"),
                vm.state.presets.presets
                    .map { it.name },
            )
        }

    @Test
    fun `a failed save that finishes after the dialog was opened again does not annotate the new dialog`() =
        test { vm ->
            val running = CompletableDeferred<Unit>()
            gate = running
            answer = SettingsUpdateResult.Conflict
            vm.toggleDue(DueWindow.TODAY)
            vm.saveAs("Today")
            vm.cancelSavePreset()
            vm.openSavePreset()
            running.complete(Unit)
            assertEquals(PresetEditor(), vm.state.presets.editor)
        }

    @Test
    fun `two deletes back to back both stay deleted`() =
        test { vm ->
            val a = NextPreset("A", NextFilter(due = DueWindow.TODAY))
            val b = NextPreset("B", NextFilter(due = DueWindow.OVERDUE))
            val c = NextPreset("C", NextFilter(energy = Energy.HIGH))
            stored.value = listOf(a, b, c).map(NextPresets::encode)
            val running = CompletableDeferred<Unit>()
            gate = running
            vm.removePreset(a)
            vm.removePreset(b)
            // The second delete waits for the first instead of reading the list the first has not changed yet.
            assertEquals(1, storeCalls)
            running.complete(Unit)
            assertEquals(listOf(NextPresets.encode(c)), stored.value)
            assertEquals(listOf(listOf(b, c), listOf(c)).map { it.map(NextPresets::encode) }, writes)
            assertNull(vm.state.presets.message)
        }

    @Test
    fun `unreadable stored texts count against the preset limit`() =
        test { vm ->
            val readable = (1 until NextPresets.MAX_PRESETS).map { NextPresets.encode(NextPreset("P$it", NextFilter())) }
            stored.value = readable + "garbage"
            vm.toggleDue(DueWindow.TODAY)
            vm.saveAs("New")
            assertEquals(PresetEditor("New", PresetMessage.TOO_MANY, saving = false), vm.state.presets.editor)
            assertEquals(0, storeCalls)
        }

    @Test
    fun `settings that load only on the save keep the stored presets`() =
        test { vm ->
            // Not loaded at start (e.g. offline); loaded by the time the user saves.
            val existing = NextPresets.encode(NextPreset("Existing", NextFilter(energy = Energy.HIGH)))
            assertTrue(
                vm.state.presets.presets
                    .isEmpty(),
            )
            stored.value = null
            load = { if (stored.value == null) stored.value = listOf(existing) }
            vm.toggleDue(DueWindow.TODAY)
            vm.saveAs("Today")
            assertNull(vm.state.presets.editor)
            val written = writes.single()
            assertEquals(2, written.size)
            assertEquals(existing, written[0])
            assertEquals(
                listOf("Existing", "Today"),
                vm.state.presets.presets
                    .map { it.name },
            )
        }

    @Test
    fun `presets loaded after the screen opened are shown`() =
        test { vm ->
            stored.value = null
            assertTrue(
                vm.state.presets.presets
                    .isEmpty(),
            )
            val later = NextPreset("Later", NextFilter(due = DueWindow.OVERDUE))
            stored.value = listOf(NextPresets.encode(later))
            assertEquals(listOf(later), vm.state.presets.presets)
        }

    @Test
    fun `a settings load that throws does not break the screen and a save reports a failure`() {
        load = { throw IOException("unreachable") }
        test { vm ->
            assertEquals(3, vm.shown.size)
            vm.toggleDue(DueWindow.TODAY)
            vm.saveAs("Today")
            assertEquals(PresetEditor("Today", PresetMessage.FAILED, saving = false), vm.state.presets.editor)
            assertEquals(0, storeCalls)
        }
    }

    @Test
    fun `a write that throws reports a failure and keeps the dialog open`() =
        test { vm ->
            storeFailure = IOException("broken pipe")
            vm.toggleDue(DueWindow.TODAY)
            vm.saveAs("Today")
            assertEquals(PresetEditor("Today", PresetMessage.FAILED, saving = false), vm.state.presets.editor)
            assertTrue(
                vm.state.presets.presets
                    .isEmpty(),
            )
        }

    @Test
    fun `without a settings store a save reports offline`() =
        test(PresetSettings.NONE) { vm ->
            vm.toggleDue(DueWindow.TODAY)
            vm.saveAs("Today")
            assertEquals(
                PresetMessage.OFFLINE,
                vm.state.presets.editor
                    ?.message,
            )
        }

    @Test
    fun `deleting a preset that is already gone writes nothing and reports nothing`() =
        test { vm ->
            val other = NextPresets.encode(NextPreset("Other", NextFilter()))
            stored.value = listOf(other)
            vm.removePreset(NextPreset("Gone", NextFilter()))
            assertEquals(0, storeCalls)
            assertNull(vm.state.presets.message)
            assertEquals(listOf(other), stored.value)
        }

    @Test
    fun `applying a preset clears the message of a failed delete`() =
        test { vm ->
            val a = NextPreset("A", NextFilter(due = DueWindow.TODAY))
            stored.value = listOf(NextPresets.encode(a))
            answer = SettingsUpdateResult.Offline
            vm.removePreset(a)
            assertEquals(PresetMessage.OFFLINE, vm.state.presets.message)
            vm.applyPreset(a)
            assertNull(vm.state.presets.message)
        }

    @Test
    fun `saving keeps unreadable stored texts as they are`() =
        test { vm ->
            val webOnly = """{"name":"Web","filter":{"context":"not-a-uuid"}}"""
            stored.value = listOf("garbage", webOnly)
            assertEquals(
                listOf("Web"),
                vm.state.presets.presets
                    .map { it.name },
            )
            vm.toggleDue(DueWindow.TODAY)
            vm.saveAs("Today")
            val written = writes.single()
            assertEquals(listOf("garbage", webOnly), written.take(2))
            assertEquals(NextPreset("Today", NextFilter(due = DueWindow.TODAY)), NextPresets.parse(written[2]))
        }

    @Test
    fun `presets changed on another device replace the shown ones`() =
        test { vm ->
            val a = NextPreset("A", NextFilter(due = DueWindow.TODAY))
            stored.value = listOf(NextPresets.encode(a))
            vm.applyPreset(a)
            assertEquals(a, vm.state.presets.active)
            stored.value = emptyList()
            assertTrue(
                vm.state.presets.presets
                    .isEmpty(),
            )
            assertNull(vm.state.presets.active)
            // The filter itself stays: only the preset is gone.
            assertEquals(a.filter, vm.state.filter)
        }
}
