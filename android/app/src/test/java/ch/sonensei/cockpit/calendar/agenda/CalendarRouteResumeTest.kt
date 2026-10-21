package ch.sonensei.cockpit.calendar.agenda

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * The calendar route reloads when the app comes back to it, so an agenda left open overnight starts at the new today
 * instead of showing yesterday as "Today".
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "en-rUS")
class CalendarRouteResumeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.parse("2026-10-21")
    private val tomorrow = today.plusDays(1)

    /** A clock the test can move to another day. */
    private class MovableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }

    /** The lifecycle the route sees; the test moves it to the background and back. */
    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val work = CalendarInfo(id(90), "Work", 0xFF3366FFL, true, false, 1)

    private fun allDay(
        n: Int,
        date: LocalDate,
    ) = Occurrence(id(n), work.id, "Event $n", null, true, null, null, date, date.plusDays(1))

    // Wednesday 21 October 2026, 22:00 UTC.
    private val clock = MovableClock(Instant.parse("2026-10-21T22:00:00Z"))
    private val repo = mockk<CalendarRepository>()
    private val owner = TestOwner()

    @Before
    fun setUp() {
        coEvery { repo.calendars() } returns CalendarResult.Ok(listOf(work))
        coEvery { repo.occurrences(any(), any(), any()) } answers {
            val from = firstArg<LocalDate>()
            val to = secondArg<LocalDate>()
            CalendarResult.Ok(listOf(allDay(1, today), allDay(2, tomorrow)).filter { it.startDate!! in from..<to })
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
    }

    @Test
    fun `coming back on a later day reloads the agenda from the new today, the first showing loads once`() {
        val viewModel = CalendarViewModel(repo, clock, Locale.US)
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                CockpitTheme { CalendarRoute(viewModel = viewModel) }
            }
        }
        composeRule.waitForIdle()
        // The first showing loads the agenda once (later days may still load by themselves when its end is in view).
        coVerify(exactly = 1) { repo.occurrences(today, today.plusDays(CalendarViewModel.AGENDA_CHUNK_DAYS), utc) }
        coVerify(exactly = 0) { repo.occurrences(tomorrow, any(), any()) }
        composeRule.onNodeWithTag(CalendarTags.day(today)).assertTextContains("Today", substring = true)

        // The app goes to the background in the evening and comes back the next morning.
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        clock.now = Instant.parse("2026-10-22T07:00:00Z")
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.waitForIdle()

        coVerify(exactly = 1) { repo.occurrences(tomorrow, tomorrow.plusDays(CalendarViewModel.AGENDA_CHUNK_DAYS), utc) }
        val state = viewModel.uiState.value
        assertEquals(tomorrow, state.today)
        assertEquals(tomorrow, state.from)
        composeRule.onNodeWithTag(CalendarTags.day(tomorrow)).assertTextContains("Today", substring = true)
        composeRule.onNodeWithTag(CalendarTags.day(today)).assertDoesNotExist()
    }
}
