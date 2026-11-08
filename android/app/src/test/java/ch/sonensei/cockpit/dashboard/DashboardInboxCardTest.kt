package ch.sonensei.cockpit.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Dashboard's Inbox card: it opens Mail when only unread mail waits, the GTD Inbox otherwise. */
@RunWith(AndroidJUnit4::class)
class DashboardInboxCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val opened = mutableListOf<CockpitDestination>()

    private fun show(inbox: WidgetState) =
        composeRule.setContent {
            CockpitTheme {
                DashboardContent(
                    DashboardUiState(widgets = listOf(WidgetId.INBOX), states = mapOf(WidgetId.INBOX to inbox)),
                    onRefresh = {},
                    onOpen = { opened += it },
                )
            }
        }

    /** Taps the card itself (its top edge): its two lines open the GTD Inbox and Mail on their own. */
    private fun tapInbox() =
        composeRule.onNodeWithTag(DashboardTags.card(WidgetId.INBOX)).performTouchInput { click(Offset(centerX, CARD_EDGE_PX)) }

    private companion object {
        /** Inside the card's top padding, above its title. */
        const val CARD_EDGE_PX = 4f
    }

    @Test
    fun `an empty Inbox with unread mail opens Mail`() {
        show(WidgetState(WidgetData.Inbox(Count(0, more = false), Count(26, more = true)), loading = false))
        composeRule.onNode(hasText("26+ unread emails", substring = true), useUnmergedTree = true).assertExists()
        tapInbox()
        assertEquals(listOf(CockpitDestination.Mail), opened)
    }

    @Test
    fun `an Inbox with tasks opens the GTD Inbox`() {
        show(WidgetState(WidgetData.Inbox(Count(3, more = false), Count(26, more = true)), loading = false))
        tapInbox()
        assertEquals(listOf(CockpitDestination.Inbox), opened)
    }

    @Test
    fun `an empty Inbox without unread mail opens the GTD Inbox`() {
        show(WidgetState(WidgetData.Inbox(Count(0, more = false), Count(0, more = false)), loading = false))
        tapInbox()
        assertEquals(listOf(CockpitDestination.Inbox), opened)
    }

    @Test
    fun `a loading or failed Inbox card opens the GTD Inbox`() {
        var inbox by mutableStateOf(WidgetState())
        composeRule.setContent {
            CockpitTheme {
                DashboardContent(
                    DashboardUiState(widgets = listOf(WidgetId.INBOX), states = mapOf(WidgetId.INBOX to inbox)),
                    onRefresh = {},
                    onOpen = { opened += it },
                )
            }
        }
        tapInbox()
        inbox = WidgetState(loading = false, error = WidgetError.FAILED)
        composeRule.waitForIdle()
        tapInbox()
        assertEquals(listOf(CockpitDestination.Inbox, CockpitDestination.Inbox), opened)
    }

    @Test
    fun `a failed refresh that kept an empty Inbox with unread mail opens Mail`() {
        // A failed refresh keeps the last loaded data; the card follows the data it shows.
        show(WidgetState(WidgetData.Inbox(Count(0, more = false), Count(26, more = true)), loading = false, error = WidgetError.FAILED))
        composeRule.onNodeWithTag(DashboardTags.error(WidgetId.INBOX), useUnmergedTree = true).assertExists()
        tapInbox()
        assertEquals(listOf(CockpitDestination.Mail), opened)
    }

    @Test
    fun `an offline refresh that kept an empty Inbox with unread mail opens Mail`() {
        show(WidgetState(WidgetData.Inbox(Count(0, more = false), Count(26, more = true)), loading = false, error = WidgetError.OFFLINE))
        composeRule.onNodeWithTag(DashboardTags.error(WidgetId.INBOX), useUnmergedTree = true).assertExists()
        tapInbox()
        assertEquals(listOf(CockpitDestination.Mail), opened)
    }

    @Test
    fun `a failed Inbox without data opens the GTD Inbox`() {
        show(WidgetState(loading = false, error = WidgetError.FAILED))
        composeRule.onNodeWithTag(DashboardTags.error(WidgetId.INBOX), useUnmergedTree = true).assertExists()
        tapInbox()
        assertEquals(listOf(CockpitDestination.Inbox), opened)
    }

    @Test
    fun `a failed refresh that kept an Inbox with tasks opens the GTD Inbox`() {
        show(WidgetState(WidgetData.Inbox(Count(2, more = false), Count(26, more = true)), loading = false, error = WidgetError.FAILED))
        tapInbox()
        assertEquals(listOf(CockpitDestination.Inbox), opened)
    }

    @Test
    fun `the tap follows the data as it changes`() {
        var inbox by mutableStateOf(WidgetState(WidgetData.Inbox(Count(2, more = false), Count(5, more = false)), loading = false))
        composeRule.setContent {
            CockpitTheme {
                DashboardContent(
                    DashboardUiState(widgets = listOf(WidgetId.INBOX), states = mapOf(WidgetId.INBOX to inbox)),
                    onRefresh = {},
                    onOpen = { opened += it },
                )
            }
        }
        tapInbox()
        inbox = WidgetState(WidgetData.Inbox(Count(0, more = false), Count(5, more = false)), loading = false)
        composeRule.waitForIdle()
        tapInbox()
        assertEquals(listOf(CockpitDestination.Inbox, CockpitDestination.Mail), opened)
    }
}
