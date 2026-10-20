package ch.sonensei.cockpit.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.bookmarks.BookmarksScreen
import ch.sonensei.cockpit.calendar.agenda.CalendarScreen
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
import ch.sonensei.cockpit.notes.NotesScreen
import ch.sonensei.cockpit.review.ReviewScreen
import ch.sonensei.cockpit.settings.SettingsScreen
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.navigation.NavSection

/** Test tag of the content area of a destination, so tests can tell which screen is shown. */
fun screenTag(destination: CockpitDestination): String = "screen:${destination.route}"

/** App frame: top bar with the current title, bottom bar with the main destinations, and the navigation host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CockpitShell(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = CockpitDestination.fromRoute(backStackEntry?.destination?.route)
    val selected = CockpitDestination.bottomBarItemFor(current)

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
            )
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
        NavHost(
            navController = navController,
            startDestination = CockpitDestination.start.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            CockpitDestination.entries.forEach { destination ->
                composable(destination.route) {
                    DestinationContent(
                        destination = destination,
                        onOpen = { navController.navigate(it.route) },
                        modifier = Modifier.testTag(screenTag(destination)),
                    )
                }
            }
        }
    }
}

/**
 * Switches bottom-bar tabs the Material way: one entry per tab on the back stack, each tab keeps its state, and
 * reselecting More from one of its screens returns to the More list.
 */
private fun NavHostController.navigateToTopLevel(destination: CockpitDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = destination != CockpitDestination.More }
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
