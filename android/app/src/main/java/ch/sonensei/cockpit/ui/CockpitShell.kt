package ch.sonensei.cockpit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.bookmarks.BookmarksScreen
import ch.sonensei.cockpit.calendar.agenda.CalendarScreen
import ch.sonensei.cockpit.capture.entry.CaptureButton
import ch.sonensei.cockpit.capture.entry.CaptureButtonPlacement
import ch.sonensei.cockpit.contacts.ContactsScreen
import ch.sonensei.cockpit.files.FilesScreen
import ch.sonensei.cockpit.gtd.inbox.InboxScreen
import ch.sonensei.cockpit.gtd.lists.SomedayScreen
import ch.sonensei.cockpit.gtd.lists.WaitingScreen
import ch.sonensei.cockpit.gtd.next.NextScreen
import ch.sonensei.cockpit.gtd.projects.ProjectsScreen
import ch.sonensei.cockpit.gtd.today.TodayScreen
import ch.sonensei.cockpit.habits.HabitsScreen
import ch.sonensei.cockpit.journal.JournalScreen
import ch.sonensei.cockpit.mail.list.MailScreen
import ch.sonensei.cockpit.newitems.NewScreen
import ch.sonensei.cockpit.notes.NotesScreen
import ch.sonensei.cockpit.reminders.ReminderOpenRequests
import ch.sonensei.cockpit.review.ReviewScreen
import ch.sonensei.cockpit.settings.SettingsScreen
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.navigation.NavSection

/** Test tag of the content area of a destination, so tests can tell which screen is shown. */
fun screenTag(destination: CockpitDestination): String = "screen:${destination.route}"

/**
 * App frame: top bar with the current title, bottom bar with the main destinations, and the navigation host, with
 * [prompts] (e.g. the reminder permission prompt) above the content. Opens the item of a tapped reminder notification.
 */
@Composable
fun CockpitShell(
    navController: NavHostController = rememberNavController(),
    prompts: @Composable () -> Unit = {},
) = CockpitShellFrame(navController, prompts) { destination, onOpen, modifier -> DestinationContent(destination, onOpen, modifier) }

/** The shell with replaceable screen content, so tests can place their own content inside the real frame. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CockpitShellFrame(
    navController: NavHostController,
    prompts: @Composable () -> Unit,
    destinationContent: @Composable (CockpitDestination, (CockpitDestination) -> Unit, Modifier) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = CockpitDestination.fromRoute(backStackEntry?.destination?.route)
    val selected = CockpitDestination.bottomBarItemFor(current)
    val showsFab = current != null && current !in CockpitDestination.withOwnFab

    LaunchedEffect(navController) {
        ReminderOpenRequests.requests.collect { request ->
            if (request != null && ReminderOpenRequests.take(request)) navController.open(request.destination)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(current?.labelRes ?: R.string.app_name)) },
                navigationIcon = {
                    if (current?.section == NavSection.More) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                        }
                    }
                },
                actions = {
                    if (current in CockpitDestination.withOwnFab) CaptureButton(CaptureButtonPlacement.AppBarAction)
                },
            )
        },
        // Quick capture on every screen (SPEC.md §2), and never a second FAB next to a screen's own.
        floatingActionButton = {
            if (showsFab) CaptureButton(CaptureButtonPlacement.Fab)
        },
        bottomBar = {
            NavigationBar {
                CockpitDestination.bottomBar.forEach { destination ->
                    NavigationBarItem(
                        selected = destination == selected,
                        onClick = { navController.navigateToTopLevel(destination) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            prompts()
            NavHost(
                navController = navController,
                startDestination = CockpitDestination.start.route,
                modifier = Modifier.weight(1f),
            ) {
                CockpitDestination.entries.forEach { destination ->
                    composable(destination.route) {
                        // Screens with their own snackbars keep them above the shell's FAB (LocalFabClearance).
                        CompositionLocalProvider(LocalFabClearance provides fabClearance(destination)) {
                            destinationContent(destination, { navController.navigate(it.route) }, Modifier.testTag(screenTag(destination)))
                        }
                    }
                }
            }
        }
    }
}

/** The space the shell's capture FAB takes at the bottom of [destination]'s content: none where the screen has its own FAB. */
internal fun fabClearance(destination: CockpitDestination): Dp =
    if (destination in
        CockpitDestination.withOwnFab
    ) {
        0.dp
    } else {
        ShellFabClearance
    }

/** Opens [destination] from outside the shell (a notification): as a tab, or a More screen on top of the current one. */
private fun NavHostController.open(destination: CockpitDestination) {
    if (destination.section == NavSection.BottomBar) {
        navigateToTopLevel(destination)
    } else {
        navigate(destination.route) { launchSingleTop = true }
    }
}

/**
 * Switches bottom-bar tabs the Material way: one entry per tab on the back stack, and each tab keeps its state.
 *
 * `saveState` applies to the tab being left (the entries popped here), so it is always on, also when the target
 * is More. Only More itself is never restored, so selecting More (from one of its screens or from another tab)
 * always opens the More list; the state saved for More is simply replaced the next time More is left.
 */
private fun NavHostController.navigateToTopLevel(destination: CockpitDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = destination != CockpitDestination.More
    }
}

@Composable
private fun DestinationContent(
    destination: CockpitDestination,
    onOpen: (CockpitDestination) -> Unit,
    modifier: Modifier,
) {
    when (destination) {
        CockpitDestination.Today -> TodayScreen(modifier)
        CockpitDestination.Inbox -> InboxScreen(modifier)
        CockpitDestination.Calendar -> CalendarScreen(modifier)
        CockpitDestination.Mail -> MailScreen(modifier)
        CockpitDestination.More -> MoreScreen(onOpen, modifier)
        CockpitDestination.New -> NewScreen(modifier)
        CockpitDestination.Next -> NextScreen(modifier)
        CockpitDestination.Projects -> ProjectsScreen(modifier)
        CockpitDestination.Waiting -> WaitingScreen(modifier)
        CockpitDestination.Someday -> SomedayScreen(modifier)
        CockpitDestination.Notes -> NotesScreen(modifier)
        CockpitDestination.Files -> FilesScreen(modifier)
        CockpitDestination.Contacts -> ContactsScreen(modifier)
        CockpitDestination.Bookmarks -> BookmarksScreen(modifier)
        CockpitDestination.Journal -> JournalScreen(modifier)
        CockpitDestination.Habits -> HabitsScreen(modifier)
        CockpitDestination.Review -> ReviewScreen(modifier)
        CockpitDestination.Settings -> SettingsScreen(modifier)
    }
}
