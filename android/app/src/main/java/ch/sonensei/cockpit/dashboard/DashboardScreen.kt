package ch.sonensei.cockpit.dashboard

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.LocalFabClearance
import ch.sonensei.cockpit.ui.PlaceholderScreen
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The dashboard, the app's start screen. Its view model comes from Hilt, so outside a Hilt activity (the shell's
 * navigation tests, previews) it shows the placeholder instead of failing.
 */
@Composable
fun DashboardScreen(
    onOpen: (CockpitDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        DashboardRoute(onOpen, modifier)
    } else {
        PlaceholderScreen(R.string.nav_dashboard, Icons.Outlined.Dashboard, modifier)
    }
}

@Composable
fun DashboardRoute(
    onOpen: (CockpitDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: DashboardViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Loaded each time the screen is shown, so changes made elsewhere show up.
    LaunchedEffect(Unit) { viewModel.refresh() }
    DashboardContent(state, onRefresh = { viewModel.refresh(pulled = true) }, onOpen = onOpen, modifier = modifier)
}

internal object DashboardTags {
    const val LIST = "dashboard-list"

    fun card(widget: WidgetId) = "dashboard-card:${widget.key}"

    fun error(widget: WidgetId) = "dashboard-error:${widget.key}"

    fun loading(widget: WidgetId) = "dashboard-loading:${widget.key}"
}

/** The screen a widget's card opens. */
internal val WidgetId.destination: CockpitDestination
    get() =
        when (this) {
            WidgetId.TODAY -> CockpitDestination.Today
            WidgetId.INBOX -> CockpitDestination.Inbox
            WidgetId.UPCOMING -> CockpitDestination.Calendar
            WidgetId.WAITING_OVERDUE -> CockpitDestination.Waiting
            WidgetId.STALLED_PROJECTS -> CockpitDestination.Projects
            WidgetId.WEEKLY_REVIEW -> CockpitDestination.Review
            WidgetId.READ_LATER -> CockpitDestination.Bookmarks
            WidgetId.PINNED_NOTES -> CockpitDestination.Notes
        }

private val WidgetId.title: Int
    get() =
        when (this) {
            WidgetId.TODAY -> R.string.dashboard_today
            WidgetId.INBOX -> R.string.dashboard_inbox
            WidgetId.UPCOMING -> R.string.dashboard_upcoming
            WidgetId.WAITING_OVERDUE -> R.string.dashboard_waiting_overdue
            WidgetId.STALLED_PROJECTS -> R.string.dashboard_stalled_projects
            WidgetId.WEEKLY_REVIEW -> R.string.dashboard_weekly_review
            WidgetId.READ_LATER -> R.string.dashboard_read_later
            WidgetId.PINNED_NOTES -> R.string.dashboard_pinned_notes
        }

/** Items a card lists at most; the rest are behind its screen. */
private const val MAX_ROWS = 5

/** The shown widgets as cards in their order; pull to refresh reloads all of them. */
@Composable
fun DashboardContent(
    state: DashboardUiState,
    onRefresh: () -> Unit,
    onOpen: (CockpitDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(DashboardTags.LIST),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + LocalFabClearance.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.widgets, key = { it.key }) { widget ->
                WidgetCard(widget, state.state(widget), onClick = { onOpen(widget.destination) })
            }
        }
    }
}

@Composable
private fun WidgetCard(
    widget: WidgetId,
    widgetState: WidgetState,
    onClick: () -> Unit,
) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag(DashboardTags.card(widget))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(widget.destination.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(widget.title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                if (widgetState.loading) {
                    CircularProgressIndicator(Modifier.size(18.dp).testTag(DashboardTags.loading(widget)), strokeWidth = 2.dp)
                }
            }
            widgetState.error?.let { ErrorLine(widget, it) }
            widgetState.data?.let { WidgetBody(it) }
        }
    }
}

@Composable
private fun ErrorLine(
    widget: WidgetId,
    error: WidgetError,
) {
    Row(Modifier.testTag(DashboardTags.error(widget)), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (error == WidgetError.OFFLINE) Icons.Outlined.CloudOff else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(if (error == WidgetError.OFFLINE) R.string.dashboard_offline else R.string.dashboard_failed) +
                " " + stringResource(R.string.dashboard_retry_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun WidgetBody(data: WidgetData) {
    when (data) {
        is WidgetData.Today -> {
            if (data.tasks.isEmpty() && data.events.isEmpty()) Muted(stringResource(R.string.dashboard_today_empty))
            Events(data.events, withDate = false)
            Tasks(data.tasks)
            if (data.habitsDue > 0) Muted(stringResource(R.string.dashboard_today_habits, data.habitsDone, data.habitsDue))
        }
        is WidgetData.Inbox -> {
            Line(pluralStringResource(R.plurals.dashboard_inbox_tasks, data.tasks.count, data.tasks.text()))
            Line(pluralStringResource(R.plurals.dashboard_inbox_mail, data.unreadMail.count, data.unreadMail.text()))
        }
        is WidgetData.Upcoming -> {
            if (data.events.isEmpty()) Muted(stringResource(R.string.dashboard_upcoming_empty))
            Events(data.events, withDate = true)
        }
        is WidgetData.WaitingOverdue -> {
            if (data.tasks.isEmpty()) Muted(stringResource(R.string.dashboard_waiting_empty))
            Tasks(data.tasks)
        }
        is WidgetData.StalledProjects -> Items(data.projects, more = false, empty = R.string.dashboard_stalled_empty)
        is WidgetData.WeeklyReview -> {
            val days = data.daysSinceLastReview
            Line(
                when {
                    days == null -> stringResource(R.string.dashboard_review_never)
                    days <= 0L -> stringResource(R.string.dashboard_review_today)
                    else -> pluralStringResource(R.plurals.dashboard_review_days, days.toInt(), days.toInt())
                },
            )
            data.openStep?.let { Muted(stringResource(R.string.dashboard_review_open, it)) }
        }
        is WidgetData.ReadLater -> Items(data.items, data.more, R.string.dashboard_read_later_empty)
        is WidgetData.PinnedNotes -> Items(data.items, data.more, R.string.dashboard_pinned_empty)
    }
}

private fun Count.text(): String = if (more) "$count+" else count.toString()

@Composable
private fun Items(
    items: List<DashboardItem>,
    more: Boolean,
    empty: Int,
) {
    if (items.isEmpty()) Muted(stringResource(empty))
    items.take(MAX_ROWS).forEach { Line(it.title) }
    if (more || items.size > MAX_ROWS) Muted(stringResource(R.string.dashboard_more))
}

@Composable
private fun Tasks(tasks: List<DashboardTask>) {
    val today = LocalDate.now()
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    tasks.take(MAX_ROWS).forEach { task ->
        val due = task.dueDate
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (due != null && due.isBefore(today)) {
                Text(
                    "${stringResource(R.string.dashboard_overdue)} · ${due.format(dates)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
    if (tasks.size > MAX_ROWS) Muted(stringResource(R.string.dashboard_more))
}

@Composable
private fun Events(
    events: List<DashboardEvent>,
    withDate: Boolean,
) {
    val zone = ZoneId.systemDefault()
    val times = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    val dates = DateTimeFormatter.ofPattern("EEE d MMM")
    events.take(MAX_ROWS).forEach { event ->
        val time = event.start?.atZone(zone)?.format(times) ?: stringResource(R.string.dashboard_all_day)
        val prefix = if (withDate) "${event.date.format(dates)} · $time" else time
        Row {
            Text(
                prefix,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(event.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (events.size > MAX_ROWS) Muted(stringResource(R.string.dashboard_more))
}

@Composable
private fun Line(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
