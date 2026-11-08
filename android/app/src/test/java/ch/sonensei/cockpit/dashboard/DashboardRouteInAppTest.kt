package ch.sonensei.cockpit.dashboard

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.PersistentTokenStore
import ch.sonensei.cockpit.calendar.agenda.CalendarInfo
import ch.sonensei.cockpit.calendar.agenda.Occurrence
import ch.sonensei.cockpit.calendar.edit.EventEditorTags
import ch.sonensei.cockpit.core.network.AuthTokens
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.screenTag
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/**
 * The item openers and the layout editor as [DashboardRoute] wires them in the Hilt activity (a fake repository): what
 * the content tests only record as calls really opens the review, the Projects screen, the browser, the calendar, the
 * event editor and the note editor, and a Customize change reaches the repository.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class DashboardRouteInAppTest {
    private val hilt = HiltAndroidRule(this)
    private val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @BindValue
    @JvmField
    val repo: DashboardRepository = mockk()

    @Inject lateinit var tokenStore: PersistentTokenStore

    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val day = LocalDate.of(2026, 10, 21)

    private fun occurrence(calendar: UUID) =
        Occurrence(
            eventId = id(5),
            calendarId = calendar,
            title = "Dentist",
            location = null,
            allDay = false,
            start = Instant.parse("2026-10-21T08:00:00Z"),
            end = Instant.parse("2026-10-21T09:00:00Z"),
            startDate = null,
            endDate = null,
        )

    private val personal = CalendarInfo(id(90), "Personal", color = null, visible = true, readOnly = false, version = 1)
    private val birthdays = CalendarInfo(id(91), "Birthdays", color = null, visible = true, readOnly = true, version = 1, virtual = true)

    /** An event the editor can open, one without an occurrence (opens the calendar) and a birthday. */
    private val editable = DashboardEvent("e:5", "Dentist", day, Instant.parse("2026-10-21T08:00:00Z"), occurrence(personal.id))
    private val plain = DashboardEvent("e:6", "Yoga", day, start = null)
    private val birthday = DashboardEvent("e:7", "Birthday Erika Muster", day, start = null, occurrence = occurrence(birthdays.id))

    private val layout =
        layoutOf(WidgetId.TODAY, WidgetId.STALLED_PROJECTS, WidgetId.WEEKLY_REVIEW, WidgetId.READ_LATER, WidgetId.PINNED_NOTES)

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        hilt.inject()
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())
        tokenStore.login(AuthTokens("access-fictitious", "refresh-fictitious"), null)
        coEvery { repo.layout() } returns DashboardResult.Ok(layout)
        coEvery { repo.load(WidgetId.TODAY) } returns
            DashboardResult.Ok(WidgetData.Today(emptyList(), listOf(editable, plain, birthday), habitsDue = 0, habitsDone = 0))
        coEvery { repo.load(WidgetId.STALLED_PROJECTS) } returns
            DashboardResult.Ok(WidgetData.StalledProjects(listOf(DashboardItem(id(2), "Move flat"))))
        coEvery { repo.load(WidgetId.WEEKLY_REVIEW) } returns
            DashboardResult.Ok(WidgetData.WeeklyReview(daysSinceLastReview = null, openStep = null))
        coEvery { repo.load(WidgetId.READ_LATER) } returns
            DashboardResult.Ok(WidgetData.ReadLater(listOf(DashboardItem(id(3), "Page 3", "https://www.example.org/3")), more = false))
        coEvery { repo.load(WidgetId.PINNED_NOTES) } returns
            DashboardResult.Ok(WidgetData.PinnedNotes(listOf(DashboardItem(id(4), "Packing list")), more = false))
        coEvery { repo.calendars() } returns DashboardResult.Ok(listOf(personal, birthdays))
        coEvery { repo.saveLayout(any()) } coAnswers { DashboardResult.Ok(firstArg()) }
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun activity(): Activity {
        var activity: Activity? = null
        scenario!!.onActivity { activity = it }
        return activity!!
    }

    private fun exists(tag: String) = composeRule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitForTag(tag: String) = composeRule.waitUntil(WAIT_MS) { exists(tag) }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForTag(DashboardTags.LIST)
        // The first card has its data, so the stored layout is loaded (Customize is enabled only then). Cards further
        // down are composed only when tap() scrolls to them.
        waitForTag(DashboardTags.event(editable.key))
    }

    /** Scrolls to [tag] and taps it (items sit inside the clickable card, so the unmerged node is tapped). */
    private fun tap(tag: String) {
        composeRule.onNodeWithTag(DashboardTags.LIST).performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
    }

    private fun back() = composeRule.runOnUiThread { (activity() as ComponentActivity).onBackPressedDispatcher.onBackPressed() }

    @Test
    fun `the review button opens the Review screen`() {
        launch()

        tap(DashboardTags.REVIEW_BUTTON)

        waitForTag(screenTag(CockpitDestination.Review))
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Review)).assertIsDisplayed()
    }

    @Test
    fun `a stalled project opens the Projects screen`() {
        launch()

        tap(DashboardTags.item(id(2)))

        waitForTag(screenTag(CockpitDestination.Projects))
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Projects)).assertIsDisplayed()
    }

    @Test
    fun `a read-later bookmark opens its address in the browser and stays on the dashboard`() {
        launch()

        tap(DashboardTags.item(id(3)))
        composeRule.waitUntil(WAIT_MS) { shadowOf(activity()).peekNextStartedActivity() != null }

        val started = shadowOf(activity()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://www.example.org/3", started.dataString)
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Dashboard)).assertExists()
    }

    @Test
    fun `without a browser a read-later bookmark opens the Bookmarks screen`() {
        launch()
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).checkActivities(true)

        tap(DashboardTags.item(id(3)))

        waitForTag(screenTag(CockpitDestination.Bookmarks))
        assertNull(shadowOf(activity()).nextStartedActivity)
    }

    @Test
    fun `an event without an occurrence opens the Calendar`() {
        launch()

        tap(DashboardTags.event(plain.key))

        waitForTag(screenTag(CockpitDestination.Calendar))
        coVerify(exactly = 0) { repo.calendars() }
    }

    /** The editor's root carries the screen's tag (the outer one wins), so its close button tells that it is open. */
    @Test
    fun `an event opens the event editor on top of the dashboard, and back returns to the cards and reloads them`() {
        launch()
        coVerify(exactly = 1) { repo.layout() }

        tap(DashboardTags.event(editable.key))

        waitForTag(EventEditorTags.CLOSE)
        assertEquals(false, exists(DashboardTags.LIST))
        coVerify(exactly = 1) { repo.calendars() }

        back()

        waitForTag(DashboardTags.LIST)
        composeRule.waitUntil(WAIT_MS) { runCatching { coVerify(exactly = 2) { repo.layout() } }.isSuccess }
        assertEquals(false, exists(EventEditorTags.CLOSE))
    }

    @Test
    fun `a birthday opens its contact instead of the event editor, and back returns to the cards`() {
        launch()

        tap(DashboardTags.event(birthday.key))

        composeRule.waitUntil(WAIT_MS) { !exists(DashboardTags.LIST) }
        assertEquals(false, exists(EventEditorTags.CLOSE))
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Dashboard)).assertExists()

        back()

        waitForTag(DashboardTags.LIST)
    }

    @Test
    fun `a pinned note opens the note editor on top of the dashboard, and back returns to the cards and reloads them`() {
        launch()
        coVerify(exactly = 1) { repo.layout() }

        tap(DashboardTags.item(id(4)))

        composeRule.waitUntil(WAIT_MS) { !exists(DashboardTags.LIST) }
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Dashboard)).assertExists()

        back()

        waitForTag(DashboardTags.LIST)
        composeRule.waitUntil(WAIT_MS) { runCatching { coVerify(exactly = 2) { repo.layout() } }.isSuccess }
    }

    @Test
    fun `hiding a widget in Customize removes its card and saves the layout with it hidden`() {
        launch()

        tap(DashboardTags.CUSTOMIZE)
        waitForTag(DashboardTags.CUSTOMIZE_PANEL)
        tap(DashboardTags.slotVisible(WidgetId.READ_LATER))

        composeRule.waitUntil(WAIT_MS) { runCatching { coVerify(exactly = 1) { repo.saveLayout(any()) } }.isSuccess }
        coVerify { repo.saveLayout(layout.withVisible(WidgetId.READ_LATER, false)) }
        assertEquals(false, exists(DashboardTags.card(WidgetId.READ_LATER)))
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
