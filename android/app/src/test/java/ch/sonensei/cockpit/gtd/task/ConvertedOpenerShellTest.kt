package ch.sonensei.cockpit.gtd.task

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.search.SearchRoutes
import ch.sonensei.cockpit.ui.CockpitShellFrame
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.screenTag
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** In the shell, "Open" after a conversion shows the new note on top, or the Calendar tab for an event. */
@RunWith(AndroidJUnit4::class)
class ConvertedOpenerShellTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: NavHostController
    private val noteId = UUID.fromString("00000000-0000-7000-8000-000000000070")
    private val note = ConvertedItem(ConvertedType.NOTE, noteId, "Concert tickets")
    private val event =
        ConvertedItem(ConvertedType.EVENT, UUID.fromString("00000000-0000-7000-8000-000000000071"), "Concert", LocalDate.of(2026, 11, 14))
    private var outside: ConvertedOpener? = ConvertedOpener { }

    /** The real frame; the Inbox destination has buttons that open the converted items through the shell's opener. */
    private fun setFrame() =
        composeRule.setContent {
            navController = rememberNavController()
            CockpitTheme {
                CockpitShellFrame(navController, prompts = {}) { destination, _, modifier ->
                    Box(modifier.fillMaxSize().testTag(screenTag(destination))) {
                        if (destination == CockpitDestination.Inbox) {
                            val opener = LocalConvertedOpener.current
                            Column {
                                TextButton(onClick = { opener?.open(note) }) { Text("Open note") }
                                TextButton(onClick = { opener?.open(event) }) { Text("Open event") }
                            }
                        }
                    }
                }
            }
        }

    private fun currentRoute(): String? = composeRule.runOnUiThread { navController.currentDestination?.route }

    private fun openInbox() {
        composeRule.runOnUiThread { navController.navigate(CockpitDestination.Inbox.route) }
        composeRule.waitForIdle()
    }

    @Test
    fun `a converted note opens as an item on top of the inbox`() {
        setFrame()
        openInbox()

        composeRule.onNodeWithText("Open note").performClick()
        composeRule.waitForIdle()

        assertEquals(SearchRoutes.ITEM, currentRoute())
        assertEquals(
            "NOTE" to noteId.toString(),
            composeRule.runOnUiThread {
                val args = navController.currentBackStackEntry?.arguments
                args?.getString(SearchRoutes.ARG_TYPE) to args?.getString(SearchRoutes.ARG_ID)
            },
        )
    }

    @Test
    fun `a converted event opens the Calendar tab`() {
        setFrame()
        openInbox()

        composeRule.onNodeWithText("Open event").performClick()
        composeRule.waitForIdle()

        assertEquals(CockpitDestination.Calendar.route, currentRoute())
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Calendar)).assertIsDisplayed()
    }

    @Test
    fun `outside the shell there is no opener`() {
        composeRule.setContent { outside = LocalConvertedOpener.current }

        assertNull(outside)
    }
}
