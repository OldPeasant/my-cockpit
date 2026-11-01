package ch.sonensei.cockpit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.capture.entry.CaptureButtonTags
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FabClearanceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    private fun setShell(content: @Composable (CockpitDestination, Modifier) -> Unit) {
        composeRule.setContent {
            navController = rememberNavController()
            CockpitTheme { CockpitShellFrame(navController, prompts = {}) { destination, _, modifier -> content(destination, modifier) } }
        }
    }

    private fun open(destination: CockpitDestination) {
        composeRule.runOnUiThread { navController.navigate(destination.route) }
        composeRule.waitForIdle()
    }

    @Test
    fun `screens under the shell FAB get its clearance and screens with their own FAB get none`() {
        val seen = mutableMapOf<CockpitDestination, Dp>()
        setShell { destination, modifier ->
            seen[destination] = LocalFabClearance.current
            Box(modifier.fillMaxSize())
        }
        CockpitDestination.entries.forEach { open(it) }

        CockpitDestination.entries.forEach { destination ->
            val expected = if (destination in OWN_FAB) 0.dp else ShellFabClearance
            assertEquals("clearance on $destination", expected, seen[destination])
        }
        assertEquals(0.dp, seen[CockpitDestination.Notes])
        assertTrue(seen.getValue(CockpitDestination.Today) > 0.dp)
    }

    @Test
    fun `a screen's own snackbar padded by the clearance sits above the shell FAB`() {
        setShell { _, modifier ->
            val padded = remember { SnackbarHostState() }
            val unpadded = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { padded.showSnackbar("Task completed", "Undo", duration = SnackbarDuration.Indefinite) }
            LaunchedEffect(Unit) { unpadded.showSnackbar("Task completed", "Undo", duration = SnackbarDuration.Indefinite) }
            Box(modifier.fillMaxSize()) {
                // As in the GTD screens (TodayScreen, NextScreen, ListUi, ProjectsScreen).
                SnackbarHost(padded, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalFabClearance.current).testTag(PADDED))
                SnackbarHost(unpadded, Modifier.align(Alignment.BottomCenter).testTag(UNPADDED))
            }
        }
        composeRule.waitForIdle()

        val fab = composeRule.onNodeWithTag(CaptureButtonTags.FAB).getBoundsInRoot()
        val snackbar = composeRule.onNodeWithTag(PADDED).getBoundsInRoot()
        val control = composeRule.onNodeWithTag(UNPADDED).getBoundsInRoot()

        assertTrue("the snackbar is shown", snackbar.bottom > snackbar.top)
        // Without the clearance the FAB covers the snackbar's end (its Undo action): the test would catch a regression.
        assertTrue("control overlaps the FAB: $control vs $fab", control.overlaps(fab))
        assertTrue("snackbar $snackbar must not overlap FAB $fab", !snackbar.overlaps(fab))
        assertTrue("snackbar $snackbar ends above FAB $fab", snackbar.bottom <= fab.top)
    }

    private fun DpRect.overlaps(other: DpRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    private companion object {
        const val PADDED = "test:snackbar-padded"
        const val UNPADDED = "test:snackbar-unpadded"
        val OWN_FAB = setOf(CockpitDestination.Notes, CockpitDestination.Files, CockpitDestination.Mail, CockpitDestination.Calendar)
    }
}
