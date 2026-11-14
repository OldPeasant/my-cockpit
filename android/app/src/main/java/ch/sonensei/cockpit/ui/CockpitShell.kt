package ch.sonensei.cockpit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
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
import ch.sonensei.cockpit.dashboard.DashboardScreen
import ch.sonensei.cockpit.files.FilesScreen
import ch.sonensei.cockpit.gtd.inbox.InboxScreen
import ch.sonensei.cockpit.gtd.lists.LogbookScreen
import ch.sonensei.cockpit.gtd.lists.ScheduledScreen
import ch.sonensei.cockpit.gtd.lists.SomedayScreen
import ch.sonensei.cockpit.gtd.lists.WaitingScreen
import ch.sonensei.cockpit.gtd.next.NextScreen
import ch.sonensei.cockpit.gtd.projects.ProjectsScreen
import ch.sonensei.cockpit.gtd.task.ConvertedOpener
import ch.sonensei.cockpit.gtd.task.ConvertedType
import ch.sonensei.cockpit.gtd.task.LocalConvertedOpener
import ch.sonensei.cockpit.gtd.task.LocalTaskOpener
import ch.sonensei.cockpit.gtd.task.TaskDetailNavigation
import ch.sonensei.cockpit.gtd.task.TaskDetailScreen
import ch.sonensei.cockpit.gtd.task.TaskOpener
import ch.sonensei.cockpit.gtd.task.TaskRoutes
import ch.sonensei.cockpit.gtd.task.TaskSnapshots
import ch.sonensei.cockpit.gtd.today.TodayScreen
import ch.sonensei.cockpit.habits.HabitsScreen
import ch.sonensei.cockpit.journal.JournalScreen
import ch.sonensei.cockpit.mail.list.MailScreen
import ch.sonensei.cockpit.newitems.NewScreen
import ch.sonensei.cockpit.notes.NotesScreen
import ch.sonensei.cockpit.reminders.OpenItemRequest
import ch.sonensei.cockpit.reminders.ReminderOpenRequests
import ch.sonensei.cockpit.review.ReviewScreen
import ch.sonensei.cockpit.search.SearchItemScreen
import ch.sonensei.cockpit.search.SearchRoutes
import ch.sonensei.cockpit.search.SearchScreen
import ch.sonensei.cockpit.settings.SettingsScreen
import ch.sonensei.cockpit.trash.TrashScreen
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.navigation.NavSection
import java.util.UUID

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
    val route = backStackEntry?.destination?.route
    val current = CockpitDestination.fromRoute(route)
    // Search, its opened items and the task detail screen lie on top of a screen: they get a Back arrow.
    val stacked = SearchRoutes.isSearch(route) || TaskRoutes.isDetail(route)
    // A stacked screen (a task opened from New, search) keeps the tab of the screen it lies on selected: each stacked
    // entry inherits the destination below it, stored on the entry so a stack of stacked screens resolves too.
    val underlying = current ?: backStackEntry?.let { navController.underlyingDestination(it) }
    val selected = CockpitDestination.bottomBarItemFor(underlying)
    val showsFab = current != null && current !in CockpitDestination.withOwnFab

    LaunchedEffect(navController) {
        ReminderOpenRequests.requests.collect { request ->
            if (request != null && ReminderOpenRequests.take(request)) {
                navController.open(request.destination)
                // A task opens in its detail screen on top of Next, as when tapped there.
                request.taskId?.let { navController.navigate(TaskRoutes.detail(it)) }
            }
        }
    }

    // Task rows of every screen open the task detail screen on top of the current one; Back returns there.
    val openTask =
        remember(navController) {
            TaskOpener { id, snapshot, completedAt ->
                snapshot?.let { TaskSnapshots.put(it, completedAt) }
                navController.navigate(TaskRoutes.detail(id))
            }
        }

    // The note a task became opens on top (as from search); an event's Calendar tab has no screen per event yet.
    val openConverted =
        remember(navController) {
            ConvertedOpener { item ->
                when (item.type) {
                    ConvertedType.NOTE -> navController.navigate(SearchRoutes.item("NOTE", item.id))
                    ConvertedType.EVENT -> navController.open(CockpitDestination.Calendar)
                }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleRes(current, backStackEntry))) },
                navigationIcon = {
                    if (current?.section == NavSection.More || stacked) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                        }
                    }
                },
                actions = {
                    // Global search from every screen of the shell (SPEC.md §4.4).
                    if (current != null) {
                        IconButton(
                            onClick = { navController.navigate(SearchRoutes.SEARCH) { launchSingleTop = true } },
                            modifier = Modifier.testTag(SEARCH_ACTION_TAG),
                        ) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search_action))
                        }
                    }
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
            CompositionLocalProvider(LocalTaskOpener provides openTask, LocalConvertedOpener provides openConverted) {
                CockpitNavHost(navController, destinationContent, Modifier.weight(1f))
            }
        }
    }
}

/** The screens of the shell: its destinations, search with its opened items, and the task detail screen. */
@Composable
private fun CockpitNavHost(
    navController: NavHostController,
    destinationContent: @Composable (CockpitDestination, (CockpitDestination) -> Unit, Modifier) -> Unit,
    modifier: Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = CockpitDestination.start.route,
        modifier = modifier,
    ) {
        CockpitDestination.entries.forEach { destination ->
            composable(destination.route) {
                // Screens with their own snackbars keep them above the shell's FAB (LocalFabClearance).
                // The review opens Mail and its lists on top of itself, so back returns to the wizard (PARITY.md decision 6).
                val onTop = destination == CockpitDestination.Review
                CompositionLocalProvider(LocalFabClearance provides fabClearance(destination)) {
                    destinationContent(
                        destination,
                        { if (onTop) navController.navigate(it.route) { launchSingleTop = true } else navController.open(it) },
                        Modifier.testTag(screenTag(destination)),
                    )
                }
            }
        }
        composable(SearchRoutes.SEARCH) {
            SearchScreen(
                openItem = { type, id ->
                    navController.navigate(if (type == "TASK") TaskRoutes.detail(id) else SearchRoutes.item(type, id))
                },
                openScreen = { navController.open(it) },
                modifier = Modifier.testTag(SEARCH_SCREEN_TAG),
            )
        }
        composable(SearchRoutes.ITEM) { entry ->
            val type = entry.arguments?.getString(SearchRoutes.ARG_TYPE).orEmpty()
            val id = entry.arguments?.getString(SearchRoutes.ARG_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id != null) {
                SearchItemScreen(type, id, onClose = { navController.close(entry) })
            }
        }
        composable(TaskRoutes.DETAIL) { entry ->
            val id = entry.arguments?.getString(TaskRoutes.ARG_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (id != null) {
                TaskDetailScreen(
                    id = id,
                    navigation =
                        TaskDetailNavigation(
                            close = { navController.close(entry) },
                            openTask = { navController.navigate(TaskRoutes.detail(it)) },
                            openItem = { type, item -> navController.navigate(SearchRoutes.item(type, item)) },
                            openScreen = { navController.open(it) },
                        ),
                    modifier = Modifier.testTag(TASK_DETAIL_SCREEN_TAG),
                )
            }
        }
    }
}

/** Test tags of the app bar's search action and of the search screen. */
internal const val SEARCH_ACTION_TAG = "shell-search"
internal const val SEARCH_SCREEN_TAG = "screen:search"
internal const val TASK_DETAIL_SCREEN_TAG = "screen:task"

/** The app bar title: the destination's label, "Search" on the search screen, the item type's screen for an opened item. */
private fun titleRes(
    current: CockpitDestination?,
    entry: NavBackStackEntry?,
): Int =
    when {
        current != null -> current.labelRes
        entry?.destination?.route == SearchRoutes.SEARCH -> R.string.search_title
        TaskRoutes.isDetail(entry?.destination?.route) -> R.string.task_detail_title
        entry?.destination?.route == SearchRoutes.ITEM ->
            entry.arguments
                ?.getString(SearchRoutes.ARG_TYPE)
                ?.let { OpenItemRequest(it, "").destination.labelRes }
                ?: R.string.search_title
        else -> R.string.app_name
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

/**
 * Leaves [entry] back to the screen below, if it is still the one shown: a screen asking twice (a mail deleted from
 * global search closes once for the delete, once for the view) must not also leave the screen it was opened from.
 */
private fun NavHostController.close(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) navigateUp()
}

/** Key under which a stacked entry keeps the route of the cockpit destination it lies on (survives recreation). */
private const val UNDERLYING_ROUTE_KEY = "cockpit.shell.underlyingRoute"

/**
 * The cockpit destination the stacked [entry] lies on: the one stored on the entry, else the entry below it (or the
 * destination stored on that one, if it is stacked too). A resolved destination is stored on [entry]; null never is.
 */
private fun NavHostController.underlyingDestination(entry: NavBackStackEntry): CockpitDestination? {
    CockpitDestination.fromRoute(entry.savedStateHandle.get<String>(UNDERLYING_ROUTE_KEY))?.let { return it }
    val below = previousBackStackEntry ?: return null
    val resolved =
        CockpitDestination.fromRoute(below.destination.route)
            ?: CockpitDestination.fromRoute(below.savedStateHandle.get<String>(UNDERLYING_ROUTE_KEY))
    resolved?.let { entry.savedStateHandle[UNDERLYING_ROUTE_KEY] = it.route }
    return resolved
}

/** Opens [destination] (from a notification, the More list, a Dashboard card): as a tab, or a More screen on top of the current one. */
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
 * The start destination is never restored either: it stays at the bottom of the stack with its own state, and the
 * state saved for it is that of the screens popped above it (e.g. the Inbox opened from its card), which would come back.
 */
private fun NavHostController.navigateToTopLevel(destination: CockpitDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = destination != CockpitDestination.More && destination != CockpitDestination.start
    }
}

@Composable
private fun DestinationContent(
    destination: CockpitDestination,
    onOpen: (CockpitDestination) -> Unit,
    modifier: Modifier,
) {
    when (destination) {
        CockpitDestination.Dashboard -> DashboardScreen(onOpen, modifier)
        CockpitDestination.Today -> TodayScreen(modifier, openNext = { onOpen(CockpitDestination.Next) })
        CockpitDestination.Inbox -> InboxScreen(modifier)
        CockpitDestination.Calendar -> CalendarScreen(modifier)
        CockpitDestination.Mail -> MailScreen(modifier, openNext = { onOpen(CockpitDestination.Next) })
        CockpitDestination.More -> MoreScreen(onOpen, modifier)
        CockpitDestination.New -> NewScreen(modifier)
        CockpitDestination.Next -> NextScreen(modifier)
        CockpitDestination.Projects -> ProjectsScreen(modifier)
        CockpitDestination.Waiting -> WaitingScreen(modifier)
        CockpitDestination.Someday -> SomedayScreen(modifier)
        CockpitDestination.Scheduled -> ScheduledScreen(modifier)
        CockpitDestination.Logbook -> LogbookScreen(modifier)
        CockpitDestination.Notes -> NotesScreen(modifier)
        CockpitDestination.Files -> FilesScreen(modifier)
        CockpitDestination.Contacts -> ContactsScreen(modifier)
        CockpitDestination.Bookmarks -> BookmarksScreen(modifier)
        CockpitDestination.Journal -> JournalScreen(modifier)
        CockpitDestination.Habits -> HabitsScreen(modifier)
        CockpitDestination.Review -> ReviewScreen(onOpen, modifier)
        CockpitDestination.Trash -> TrashScreen(modifier)
        CockpitDestination.Settings -> SettingsScreen(modifier)
    }
}
