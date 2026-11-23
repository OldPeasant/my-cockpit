package ch.sonensei.cockpit.gtd.next

import ch.sonensei.cockpit.core.settings.SettingsUpdateResult
import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
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
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** The Next view model lists tasks released from Scheduled today first, through the filters and presets. */
@OptIn(ExperimentalCoroutinesApi::class)
class NextViewModelNewTodayTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val today = LocalDate.of(2026, 11, 23)
    private val loadedAt = Instant.parse("2026-11-23T06:00:00Z")
    private val home = GtdContext(id(80), "@home", 0, 1)
    private val phone = GtdContext(id(81), "@phone", 1, 1)

    private val call = GtdTask(id(1), "Call the dentist", TaskList.NEXT, 0, 1, contextIds = listOf(phone.id), energy = Energy.LOW)
    private val sketch = GtdTask(id(2), "Sketch the garden plan", TaskList.NEXT, 1, 1, contextIds = listOf(home.id), energy = Energy.HIGH)
    private val water =
        GtdTask(id(3), "Water the plants", TaskList.NEXT, 2, 1, contextIds = listOf(home.id), energy = Energy.LOW, releasedOn = today)
    private val tiles =
        GtdTask(id(4), "Order tiles", TaskList.NEXT, 3, 1, contextIds = listOf(phone.id), energy = Energy.HIGH, releasedOn = today)
    private val report = GtdTask(id(5), "Send the report", TaskList.NEXT, 4, 1, releasedOn = today.minusDays(1))

    private val nextFlow = MutableStateFlow(Cached(listOf(call, sketch, water, tiles, report), offline = false, refreshedAt = loadedAt))
    private val tasks = mockk<TaskRepository> { every { observe(TaskView.NEXT) } returns nextFlow }
    private val contexts =
        mockk<ContextRepository> {
            every { observe() } returns MutableStateFlow(Cached(listOf(home, phone), offline = false, refreshedAt = loadedAt))
        }
    private val projects = MutableStateFlow(Cached(emptyList<GtdProject>(), offline = false, refreshedAt = loadedAt))
    private val areas = MutableStateFlow(Live(emptyList<Area>()))
    private val stored = MutableStateFlow<List<String>?>(emptyList())
    private val settings =
        PresetSettings(
            texts = stored,
            ensureLoaded = {},
            current = { stored.value },
            store = { texts ->
                stored.value = texts
                SettingsUpdateResult.Updated
            },
        )

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(
        clock: Clock = Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC),
        block: suspend (NextViewModel) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val viewModel = NextViewModel(tasks, contexts, mockk(), clock, projects, areas, settings)
        backgroundScope.launch { viewModel.uiState.collect {} }
        block(viewModel)
    }

    private val NextViewModel.shown get() = uiState.value.tasks

    @Test
    fun `tasks released today come first in their own order, the others follow in theirs`() =
        test { vm ->
            assertEquals(listOf(water, tiles, call, sketch, report), vm.shown)
            assertEquals(5, vm.uiState.value.total)
        }

    @Test
    fun `filters still apply to tasks released today`() =
        test { vm ->
            vm.toggleContext(home.id)
            // Tiles was released today but is not @home: the filter hides it.
            assertEquals(listOf(water, sketch), vm.shown)
            vm.toggleContext(home.id)
            vm.toggleEnergy(Energy.HIGH)
            assertEquals(listOf(tiles, sketch), vm.shown)
        }

    @Test
    fun `a preset still applies to tasks released today`() =
        test { vm ->
            val preset = NextPreset("Phone, low", NextFilter(contextId = phone.id, energy = Energy.LOW))
            stored.value = listOf(NextPresets.encode(preset))
            vm.applyPreset(preset)
            // Tiles is new today and @phone but needs high energy.
            assertEquals(listOf(call), vm.shown)
        }

    @Test
    fun `from the next day on they keep their normal place`() =
        test(Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC)) { vm ->
            assertEquals(listOf(call, sketch, water, tiles, report), vm.shown)
        }

    @Test
    fun `today is the user's local date, not the UTC date`() {
        // 23:30 UTC on 22 November is already 23 November in Zurich.
        val lateEvening = Clock.fixed(Instant.parse("2026-11-22T23:30:00Z"), ZoneId.of("Europe/Zurich"))
        test(lateEvening) { vm ->
            assertEquals(today, vm.uiState.value.today)
            assertEquals(listOf(water, tiles, call, sketch, report), vm.shown)
        }
    }
}
