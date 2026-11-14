package ch.sonensei.cockpit.dashboard

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.format.DateFormat
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.calendar.edit.BirthdayContact
import ch.sonensei.cockpit.calendar.edit.EventEditorCallbacks
import ch.sonensei.cockpit.calendar.edit.EventEditorContent
import ch.sonensei.cockpit.calendar.edit.EventEditorViewModel
import ch.sonensei.cockpit.gtd.task.LocalTaskOpener
import ch.sonensei.cockpit.mail.list.FollowMailChanges
import ch.sonensei.cockpit.notes.NoteEditorRoute
import ch.sonensei.cockpit.ui.LocalFabClearance
import ch.sonensei.cockpit.ui.PlaceholderScreen
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import dagger.hilt.internal.GeneratedComponentManagerHolder
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import kotlin.math.roundToInt

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

/** An item the dashboard shows on top of itself (kept across configuration changes); back returns to the cards. */
internal sealed interface DashboardOverlay {
    data class Note(
        val id: UUID,
    ) : DashboardOverlay

    /** The contact of a tapped birthday (a birthday has no event to edit). */
    data class Contact(
        val id: UUID,
    ) : DashboardOverlay

    fun encode(): String =
        when (this) {
            is Note -> "note:$id"
            is Contact -> "contact:$id"
        }

    companion object {
        fun parse(value: String): DashboardOverlay? {
            val (kind, id) = value.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
            val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return null
            return when (kind) {
                "note" -> Note(uuid)
                "contact" -> Contact(uuid)
                else -> null
            }
        }
    }
}

/**
 * The cards, or an item opened from them on top: a pinned note in the note editor, an event in the event editor (a
 * birthday as its contact). Closing it returns to the cards (at their scroll position) and reloads them, so a change
 * shows. Tasks open the shell's task detail screen, projects the Projects screen, bookmarks the browser.
 */
@Composable
fun DashboardRoute(
    onOpen: (CockpitDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: DashboardViewModel = hiltViewModel()
    val editor: EventEditorViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val editorState by editor.uiState.collectAsStateWithLifecycle()
    var overlay by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Loaded each time the screen is shown or the app comes back to it (also on a later day, so "today" moves on),
    // so changes made elsewhere show up. The first resume is the first load.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    // While shown, new mail synced on the server updates the unread-mail count (SPEC.md §2: SSE in the foreground).
    FollowMailChanges(viewModel) { viewModel.followMailChanges() }
    // A closed event editor may have changed the event.
    LaunchedEffect(editor) {
        snapshotFlow { editorState.open }.drop(1).filter { !it }.collect { viewModel.refresh() }
    }
    LaunchedEffect(state.open) {
        val open = state.open ?: return@LaunchedEffect
        when (open) {
            is DashboardOpen.Event -> {
                val birthday = open.occurrence.contactId(open.calendars)
                if (birthday != null) {
                    overlay = DashboardOverlay.Contact(birthday).encode()
                } else {
                    editor.edit(open.occurrence, open.calendars)
                }
            }
            is DashboardOpen.Screen -> onOpen(open.destination)
        }
        viewModel.opened(open)
    }
    when (val current = overlay?.let(DashboardOverlay::parse)) {
        is DashboardOverlay.Note ->
            NoteEditorRoute(
                id = current.id,
                key = "dashboard:${current.id}",
                onClose = { saving ->
                    overlay = null
                    scope.launch {
                        saving?.join()
                        viewModel.refresh()
                    }
                },
                modifier = modifier,
            )
        is DashboardOverlay.Contact -> BirthdayContact(current.id, back = { overlay = null }, modifier = modifier)
        null ->
            if (editorState.open) {
                EventEditorContent(state = editorState, callbacks = EventEditorCallbacks.of(editor), modifier = modifier)
            } else {
                DashboardContent(
                    state,
                    onRefresh = { viewModel.refresh(pulled = true) },
                    onOpen = onOpen,
                    modifier = modifier,
                    actions =
                        DashboardActions(
                            openEvent = viewModel::openEvent,
                            openProject = viewModel::openProject,
                            openNote = { overlay = DashboardOverlay.Note(it).encode() },
                            openBookmark = { item ->
                                val url = item.url
                                try {
                                    url?.let { context.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) }
                                        ?: onOpen(CockpitDestination.Bookmarks)
                                } catch (_: ActivityNotFoundException) {
                                    onOpen(CockpitDestination.Bookmarks)
                                }
                            },
                            customize = viewModel::customize,
                            setVisible = viewModel::setVisible,
                            move = viewModel::move,
                            reset = viewModel::reset,
                        ),
                    listState = listState,
                )
            }
    }
}

/**
 * What the cards' items and the layout editor ask for, so the content can be tested without view models. An item
 * opener left `null` opens the item's card screen instead (as outside the app's activity).
 */
data class DashboardActions(
    val openEvent: ((DashboardEvent) -> Unit)? = null,
    val openProject: ((UUID) -> Unit)? = null,
    val openNote: ((UUID) -> Unit)? = null,
    val openBookmark: ((DashboardItem) -> Unit)? = null,
    val customize: (Boolean) -> Unit = {},
    val setVisible: (WidgetId, Boolean) -> Unit = { _, _ -> },
    /** Moves the widget to the given index of the whole layout. */
    val move: (WidgetId, Int) -> Unit = { _, _ -> },
    val reset: () -> Unit = {},
)

internal object DashboardTags {
    const val LIST = "dashboard-list"
    const val CUSTOMIZE = "dashboard-customize"
    const val CUSTOMIZE_PANEL = "dashboard-customize-panel"
    const val RESET = "dashboard-reset"
    const val LAYOUT_ERROR = "dashboard-layout-error"
    const val ALL_HIDDEN = "dashboard-all-hidden"
    const val CHOOSE_WIDGETS = "dashboard-choose-widgets"
    const val REVIEW_BUTTON = "dashboard-review-button"
    const val INBOX_TASKS = "dashboard-inbox-tasks"
    const val INBOX_MAIL = "dashboard-inbox-mail"
    const val HABITS = "dashboard-habits"

    fun card(widget: WidgetId) = "dashboard-card:${widget.key}"

    fun error(widget: WidgetId) = "dashboard-error:${widget.key}"

    fun loading(widget: WidgetId) = "dashboard-loading:${widget.key}"

    fun task(id: UUID) = "dashboard-task:$id"

    fun event(key: String) = "dashboard-event:$key"

    fun item(id: UUID) = "dashboard-item:$id"

    fun slot(widget: WidgetId) = "dashboard-slot:${widget.key}"

    fun slotVisible(widget: WidgetId) = "dashboard-slot-visible:${widget.key}"
}

/** The widget's own screen: the source of its card's icon, and what its card opens by default (see [destinationFor]). */
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

/**
 * The screen a tap on a widget's card opens, given the card's loaded [data]. [data] is null until the card's first
 * successful load; a reload or a failed refresh keeps the last data, so the tap follows the data the card shows.
 * An Inbox without tasks but with unread mail opens Mail: nothing waits in the GTD Inbox, the unread mail does.
 */
internal fun WidgetId.destinationFor(data: WidgetData?): CockpitDestination =
    if (this == WidgetId.INBOX && data is WidgetData.Inbox && data.tasks.count == 0 && data.unreadMail.count > 0) {
        CockpitDestination.Mail
    } else {
        destination
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

/** Days after the last review from which the review card offers to start the next one (as on the web). */
internal const val REVIEW_DUE_DAYS = 7L

/** The weekly review card offers to continue the open review, or to start one when none was done for a week. */
internal fun WidgetData.WeeklyReview.reviewButton(): Int? =
    when {
        openStep != null -> R.string.dashboard_review_continue
        daysSinceLastReview == null || daysSinceLastReview >= REVIEW_DUE_DAYS -> R.string.dashboard_review_start
        else -> null
    }

/**
 * "Customize" and its layout editor, then the shown widgets as cards in their order; pull to refresh reloads all of
 * them. The items of a card open themselves, the rest of the card opens its screen.
 */
@Composable
fun DashboardContent(
    state: DashboardUiState,
    onRefresh: () -> Unit,
    onOpen: (CockpitDestination) -> Unit,
    modifier: Modifier = Modifier,
    actions: DashboardActions = DashboardActions(),
    listState: LazyListState = rememberLazyListState(),
) {
    // A keyed list keeps its first visible card when the order changes, so the default order shown before the
    // layout loads would push the user's first card above the screen: a new order starts at the top again. Not while
    // the order is being edited: the editor is at the top and must stay where the user works in it.
    var shownOrder by remember { mutableStateOf(state.widgets) }
    LaunchedEffect(state.widgets) {
        if (state.widgets != shownOrder) {
            shownOrder = state.widgets
            if (!state.customizing) listState.scrollToItem(0)
        }
    }
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag(DashboardTags.LIST),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp + LocalFabClearance.current),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.widgets.isEmpty()) {
                item(key = DashboardTags.ALL_HIDDEN) { AllHidden(state.customizing, actions) }
            }
            items(state.widgets, key = { it.key }) { widget ->
                val widgetState = state.state(widget)
                WidgetCard(widget, widgetState, onOpen, actions, onClick = { onOpen(widget.destinationFor(widgetState.data)) })
            }
            // Below the cards, so the first card stays at the top of the screen.
            if (state.customizing) {
                item(key = DashboardTags.CUSTOMIZE_PANEL) { CustomizePanel(state, actions) }
            }
            item(key = DashboardTags.CUSTOMIZE) { CustomizeToggle(state, actions) }
        }
    }
}

@Composable
private fun CustomizeToggle(
    state: DashboardUiState,
    actions: DashboardActions,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TextButton(
            onClick = { actions.customize(!state.customizing) },
            // Until the stored layout is known a change would replace it with the default.
            enabled = state.layoutLoaded,
            modifier = Modifier.testTag(DashboardTags.CUSTOMIZE),
        ) {
            Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(if (state.customizing) R.string.dashboard_customize_done else R.string.dashboard_customize))
        }
    }
}

@Composable
private fun AllHidden(
    customizing: Boolean,
    actions: DashboardActions,
) {
    Column(Modifier.fillMaxWidth().testTag(DashboardTags.ALL_HIDDEN), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.dashboard_all_hidden), style = MaterialTheme.typography.bodyLarge)
        if (!customizing) {
            OutlinedButton(
                onClick = { actions.customize(true) },
                modifier = Modifier.padding(top = 8.dp).testTag(DashboardTags.CHOOSE_WIDGETS),
            ) {
                Text(stringResource(R.string.dashboard_choose_widgets))
            }
        }
    }
}

/**
 * Every widget with a checkbox to show it; long-press and drag a row to move it (by whole rows), or use the
 * accessibility actions "Move up" / "Move down". Each change is saved at once.
 */
@Composable
private fun CustomizePanel(
    state: DashboardUiState,
    actions: DashboardActions,
) {
    ElevatedCard(Modifier.fillMaxWidth().testTag(DashboardTags.CUSTOMIZE_PANEL)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                stringResource(R.string.dashboard_customize_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                stringResource(R.string.dashboard_customize_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            state.layoutError?.let { error ->
                Text(
                    stringResource(layoutErrorText(error)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(DashboardTags.LAYOUT_ERROR),
                )
            }
            var dragging by remember { mutableStateOf<WidgetId?>(null) }
            var dragOffset by remember { mutableFloatStateOf(0f) }
            val moveUp = stringResource(R.string.dashboard_move_up)
            val moveDown = stringResource(R.string.dashboard_move_down)
            val last = state.layout.lastIndex
            state.layout.forEachIndexed { index, slot ->
                key(slot.id) {
                    var rowHeight by remember { mutableIntStateOf(1) }
                    val lifted = dragging == slot.id
                    val title = stringResource(slot.id.title)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { rowHeight = it.height.coerceAtLeast(1) }
                            .zIndex(if (lifted) 1f else 0f)
                            .graphicsLayer { translationY = if (lifted) dragOffset else 0f }
                            .background(if (lifted) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
                            .pointerInput(slot.id, index, last) {
                                // Long-press, then drag: the widget lands where it is let go, by whole rows.
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        dragging = slot.id
                                        dragOffset = 0f
                                    },
                                    onDragEnd = {
                                        val steps = (dragOffset / rowHeight).roundToInt()
                                        dragging = null
                                        dragOffset = 0f
                                        if (steps != 0) actions.move(slot.id, (index + steps).coerceIn(0, last))
                                    },
                                    onDragCancel = {
                                        dragging = null
                                        dragOffset = 0f
                                    },
                                ) { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                }
                            }.padding(horizontal = 8.dp)
                            .testTag(DashboardTags.slot(slot.id)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.DragIndicator,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Checkbox(
                            checked = slot.visible,
                            onCheckedChange = { actions.setVisible(slot.id, it) },
                            // TalkBack focuses the checkbox (it names the widget), so the move actions sit on it.
                            modifier =
                                Modifier.testTag(DashboardTags.slotVisible(slot.id)).semantics {
                                    contentDescription = title
                                    customActions = moveActions(index, last, moveUp, moveDown) { actions.move(slot.id, it) }
                                },
                        )
                        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                }
            }
            TextButton(onClick = actions.reset, modifier = Modifier.padding(horizontal = 8.dp).testTag(DashboardTags.RESET)) {
                Text(stringResource(R.string.dashboard_customize_reset))
            }
        }
    }
}

@Composable
private fun WidgetCard(
    widget: WidgetId,
    widgetState: WidgetState,
    onOpen: (CockpitDestination) -> Unit,
    actions: DashboardActions,
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
            widgetState.data?.let { WidgetBody(it, onOpen, actions) }
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
private fun WidgetBody(
    data: WidgetData,
    onOpen: (CockpitDestination) -> Unit,
    actions: DashboardActions,
) {
    val openEvent = actions.openEvent ?: { onOpen(CockpitDestination.Calendar) }
    val openLabel = stringResource(R.string.dashboard_open)
    when (data) {
        is WidgetData.Today -> {
            if (data.tasks.isEmpty() && data.events.isEmpty()) Muted(stringResource(R.string.dashboard_today_empty))
            Events(data.events, withDate = false, openEvent)
            Tasks(data.tasks)
            if (data.habitsDue > 0) {
                Line(
                    stringResource(R.string.dashboard_today_habits, data.habitsDone, data.habitsDue),
                    Modifier.opens(openLabel) { onOpen(CockpitDestination.Habits) }.testTag(DashboardTags.HABITS),
                )
            }
        }
        is WidgetData.Inbox -> {
            Line(
                pluralStringResource(R.plurals.dashboard_inbox_tasks, data.tasks.count, data.tasks.text()),
                Modifier.opens(openLabel) { onOpen(CockpitDestination.Inbox) }.testTag(DashboardTags.INBOX_TASKS),
            )
            Line(
                pluralStringResource(R.plurals.dashboard_inbox_mail, data.unreadMail.count, data.unreadMail.text()),
                Modifier.opens(openLabel) { onOpen(CockpitDestination.Mail) }.testTag(DashboardTags.INBOX_MAIL),
            )
        }
        is WidgetData.Upcoming -> {
            if (data.events.isEmpty()) Muted(stringResource(R.string.dashboard_upcoming_empty))
            Events(data.events, withDate = true, openEvent)
        }
        is WidgetData.WaitingOverdue -> {
            if (data.tasks.isEmpty()) Muted(stringResource(R.string.dashboard_waiting_empty))
            Tasks(data.tasks)
        }
        is WidgetData.StalledProjects ->
            Items(data.projects, more = false, empty = R.string.dashboard_stalled_empty) { item ->
                actions.openProject?.invoke(item.id) ?: onOpen(CockpitDestination.Projects)
            }
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
            data.reviewButton()?.let { label ->
                Button(onClick = { onOpen(CockpitDestination.Review) }, modifier = Modifier.testTag(DashboardTags.REVIEW_BUTTON)) {
                    Text(stringResource(label))
                }
            }
        }
        is WidgetData.ReadLater ->
            Items(data.items, data.more, R.string.dashboard_read_later_empty) { item ->
                actions.openBookmark?.invoke(item) ?: onOpen(CockpitDestination.Bookmarks)
            }
        is WidgetData.PinnedNotes ->
            Items(data.items, data.more, R.string.dashboard_pinned_empty) { item ->
                actions.openNote?.invoke(item.id) ?: onOpen(CockpitDestination.Notes)
            }
    }
}

private fun Count.text(): String = if (more) "$count+" else count.toString()

/** A tap opens something; [label] ("Open") tells accessibility services what a tap does. */
private fun Modifier.opens(
    label: String,
    open: () -> Unit,
): Modifier = clickable(onClickLabel = label, onClick = open)

@Composable
private fun Items(
    items: List<DashboardItem>,
    more: Boolean,
    empty: Int,
    open: (DashboardItem) -> Unit,
) {
    val openLabel = stringResource(R.string.dashboard_open)
    if (items.isEmpty()) Muted(stringResource(empty))
    items.take(MAX_ROWS).forEach { item ->
        Line(item.title, Modifier.opens(openLabel) { open(item) }.testTag(DashboardTags.item(item.id)))
    }
    if (more || items.size > MAX_ROWS) Muted(stringResource(R.string.dashboard_more))
}

@Composable
private fun Tasks(tasks: List<DashboardTask>) {
    val today = LocalDate.now()
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val opener = LocalTaskOpener.current
    val openLabel = stringResource(R.string.task_detail_open)
    tasks.take(MAX_ROWS).forEach { task ->
        val due = task.dueDate
        // A task opens its detail screen (where the shell provides it); the rest of the card opens the card's screen.
        val open = opener?.let { Modifier.clickable(onClickLabel = openLabel) { it.open(task.id, null) } } ?: Modifier
        Row(open.testTag(DashboardTags.task(task.id)), verticalAlignment = Alignment.CenterVertically) {
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
    open: (DashboardEvent) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val times = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    val locale = LocalConfiguration.current.locales[0]
    val dates = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale)
    val openLabel = stringResource(R.string.dashboard_open)
    events.take(MAX_ROWS).forEach { event ->
        key(event.key) {
            val time = event.start?.atZone(zone)?.format(times) ?: stringResource(R.string.dashboard_all_day)
            val prefix = if (withDate) "${event.date.format(dates)} · $time" else time
            Row(Modifier.fillMaxWidth().opens(openLabel) { open(event) }.testTag(DashboardTags.event(event.key))) {
                Text(
                    prefix,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(event.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (events.size > MAX_ROWS) Muted(stringResource(R.string.dashboard_more))
}

@Composable
private fun Line(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@StringRes
private fun layoutErrorText(error: WidgetError): Int =
    if (error == WidgetError.OFFLINE) R.string.dashboard_layout_offline else R.string.dashboard_layout_failed

/** "Move up" except for the first of [last] + 1 rows, "Move down" except for the last; [move] gets the new index. */
private fun moveActions(
    index: Int,
    last: Int,
    moveUp: String,
    moveDown: String,
    move: (Int) -> Unit,
): List<CustomAccessibilityAction> {
    val up = CustomAccessibilityAction(moveUp) { true.also { move(index - 1) } }
    val down = CustomAccessibilityAction(moveDown) { true.also { move(index + 1) } }
    return listOfNotNull(up.takeIf { index > 0 }, down.takeIf { index < last })
}
