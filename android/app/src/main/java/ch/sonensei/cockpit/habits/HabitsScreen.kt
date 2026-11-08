package ch.sonensei.cockpit.habits

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/** What the habits screen can ask for, so it can be tested without a view model. */
data class HabitsCallbacks(
    val toggle: (UUID) -> Unit = {},
    val reload: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
    val showArchived: (Boolean) -> Unit = {},
    val create: () -> Unit = {},
    val edit: (UUID) -> Unit = {},
    val toggleArchived: (UUID) -> Unit = {},
    val updateDraft: (HabitDraft) -> Unit = {},
    val save: () -> Unit = {},
    val cancelEdit: () -> Unit = {},
)

internal object HabitsTags {
    const val LIST = "habits-list"
    const val EMPTY = "habits-empty"
    const val LOADING = "habits-loading"
    const val MESSAGE = "habits-message"
    const val RETRY = "habits-retry"
    const val NEW = "habits-new"
    const val VIEW_ACTIVE = "habits-view-active"
    const val VIEW_ARCHIVED = "habits-view-archived"
    const val MENU_EDIT = "habits-menu-edit"
    const val MENU_ARCHIVE = "habits-menu-archive"

    fun habit(id: UUID) = "habit:$id"

    fun menu(id: UUID) = "habit-menu:$id"
}

/**
 * The Habits screen. Its view model comes from Hilt, so outside a Hilt activity (the shell's navigation tests,
 * previews) it shows the placeholder instead of failing.
 */
@Composable
fun HabitsScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        HabitsRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_habits, Icons.Outlined.Repeat, modifier)
    }
}

@Composable
fun HabitsRoute(
    modifier: Modifier = Modifier,
    viewModel: HabitsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HabitsContent(
        state,
        HabitsCallbacks(
            toggle = viewModel::toggle,
            reload = viewModel::reload,
            dismissMessage = viewModel::dismissMessage,
            showArchived = viewModel::showArchived,
            create = viewModel::startCreate,
            edit = viewModel::startEdit,
            toggleArchived = viewModel::toggleArchived,
            updateDraft = viewModel::updateDraft,
            save = viewModel::save,
            cancelEdit = viewModel::cancelEdit,
        ),
        modifier,
    )
}

@Composable
fun HabitsContent(
    state: HabitsUiState,
    callbacks: HabitsCallbacks,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ViewChoice(state.archived, callbacks.showArchived)
            state.message?.let { MessageBanner(it, callbacks) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag(HabitsTags.LOADING))
            when {
                !state.loaded -> Unit
                state.habits.isEmpty() ->
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(if (state.archived) R.string.habits_archived_empty else R.string.habits_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag(HabitsTags.EMPTY),
                        )
                    }
                else ->
                    LazyColumn(
                        Modifier.fillMaxSize().testTag(HabitsTags.LIST),
                        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
                    ) {
                        if (state.archived) {
                            items(state.habits, key = { it.id }) { habit -> HabitRow(habit, state, callbacks, dimmed = true) }
                        } else {
                            section(R.string.habits_due_today, state.dueToday, state, callbacks, dimmed = false)
                            section(R.string.habits_not_due_today, state.notDueToday, state, callbacks, dimmed = true)
                        }
                    }
            }
        }
        // New habits are active: they are added in the active view only.
        if (!state.archived) {
            ExtendedFloatingActionButton(
                onClick = callbacks.create,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.habits_new)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag(HabitsTags.NEW),
            )
        }
    }
    state.editor?.let { HabitEditorDialog(it, callbacks) }
}

/** Switches between the active habits and the archived ones. */
@Composable
private fun ViewChoice(
    archived: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    val views = listOf(false, true)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        views.forEachIndexed { index, view ->
            SegmentedButton(
                selected = archived == view,
                onClick = { onSelect(view) },
                shape = SegmentedButtonDefaults.itemShape(index, views.size),
                icon = {},
                label = { Text(stringResource(if (view) R.string.habits_view_archived else R.string.habits_view_active)) },
                modifier = Modifier.testTag(if (view) HabitsTags.VIEW_ARCHIVED else HabitsTags.VIEW_ACTIVE),
            )
        }
    }
}

private fun LazyListScope.section(
    title: Int,
    habits: List<Habit>,
    state: HabitsUiState,
    callbacks: HabitsCallbacks,
    dimmed: Boolean,
) {
    if (habits.isEmpty()) return
    item(key = "header:$title") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
        )
    }
    items(habits, key = { it.id }) { habit -> HabitRow(habit, state, callbacks, dimmed = dimmed) }
}

/**
 * An active habit: a tap counts one more check for today until its target, then back to none. An archived habit is
 * not checked off. Both have a menu to edit them and to archive or unarchive them.
 */
@Composable
private fun HabitRow(
    habit: Habit,
    state: HabitsUiState,
    callbacks: HabitsCallbacks,
    dimmed: Boolean,
) {
    val busy = habit.id in state.checking
    val progress =
        if (habit.targetCount > 1) stringResource(R.string.habits_progress_state, habit.todayCount, habit.targetCount) else null
    val toggle =
        if (habit.archived) {
            Modifier
        } else {
            Modifier
                .toggleable(value = habit.doneToday, enabled = !busy, role = Role.Checkbox, onValueChange = { callbacks.toggle(habit.id) })
                .semantics { if (progress != null) stateDescription = progress }
        }
    Row(
        Modifier
            .fillMaxWidth()
            .then(toggle)
            .padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)
            .testTag(HabitsTags.habit(habit.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!habit.archived) CheckCircle(habit, checking = busy)
        Column(Modifier.weight(1f).alpha(if (dimmed && !habit.doneToday) DIMMED else 1f)) {
            Text(habit.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                details(habit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HabitMenu(habit, enabled = !busy, callbacks)
    }
}

@Composable
private fun HabitMenu(
    habit: Habit,
    enabled: Boolean,
    callbacks: HabitsCallbacks,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag(HabitsTags.menu(habit.id))) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.habits_menu, habit.name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.habits_edit)) },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = {
                    open = false
                    callbacks.edit(habit.id)
                },
                modifier = Modifier.testTag(HabitsTags.MENU_EDIT),
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (habit.archived) R.string.habits_unarchive else R.string.habits_archive)) },
                leadingIcon = {
                    Icon(if (habit.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, contentDescription = null)
                },
                onClick = {
                    open = false
                    callbacks.toggleArchived(habit.id)
                },
                modifier = Modifier.testTag(HabitsTags.MENU_ARCHIVE),
            )
        }
    }
}

/**
 * The check-off mark: an empty ring that fills with today's progress toward a target above 1 (today's count inside),
 * or a filled circle with a tick once today is done.
 */
@Composable
private fun CheckCircle(
    habit: Habit,
    checking: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val done = habit.doneToday
    val partial = !done && habit.targetCount > 1 && habit.todayCount > 0
    Box(
        Modifier
            .size(CIRCLE)
            .then(
                when {
                    done -> Modifier.background(colors.primary, CircleShape)
                    partial -> Modifier
                    else -> Modifier.border(2.dp, colors.outline, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (partial) {
            CircularProgressIndicator(
                progress = { habit.todayCount.toFloat() / habit.targetCount },
                modifier = Modifier.size(CIRCLE),
                color = colors.primary,
                strokeWidth = 3.dp,
                trackColor = colors.outlineVariant,
                gapSize = 0.dp,
            )
        }
        when {
            checking ->
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = if (done) colors.onPrimary else colors.primary,
                )
            done -> Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.onPrimary)
            partial -> Text(habit.todayCount.toString(), style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
    }
}

/**
 * Schedule, times a day, today's progress, streak, best streak and 30-day completion, joined by commas (only the parts
 * that say something).
 */
@Composable
private fun details(habit: Habit): String {
    val parts = mutableListOf<String>()
    parts +=
        when (habit.schedule) {
            HabitSchedule.DAILY -> stringResource(R.string.habits_daily)
            HabitSchedule.TIMES_PER_WEEK -> stringResource(R.string.habits_times_per_week, habit.timesPerWeek ?: 1)
            HabitSchedule.WEEKDAYS -> habit.weekdays.joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        }
    if (habit.targetCount > 1 && !habit.archived && !habit.doneToday && habit.todayCount > 0) {
        parts += stringResource(R.string.habits_progress, habit.todayCount, habit.targetCount)
    } else if (habit.targetCount > 1) {
        parts += stringResource(R.string.habits_times_per_day, habit.targetCount)
    }
    if (habit.currentStreak > 0) {
        val streak = if (habit.streakInWeeks) R.plurals.habits_streak_weeks else R.plurals.habits_streak_days
        parts += pluralStringResource(streak, habit.currentStreak, habit.currentStreak)
    }
    if (habit.longestStreak > 0) {
        val best = if (habit.streakInWeeks) R.plurals.habits_best_weeks else R.plurals.habits_best_days
        parts += pluralStringResource(best, habit.longestStreak, habit.longestStreak)
    }
    habit.completionLast30Days?.let { parts += stringResource(R.string.habits_completion, (it * PERCENT).roundToInt()) }
    return parts.joinToString(", ")
}

@Composable
private fun MessageBanner(
    message: HabitsMessage,
    callbacks: HabitsCallbacks,
) {
    val text =
        when (message) {
            HabitsMessage.LOAD_OFFLINE -> R.string.habits_load_offline
            HabitsMessage.LOAD_FAILED -> R.string.habits_load_failed
            HabitsMessage.CHECK_OFFLINE -> R.string.habits_check_offline
            HabitsMessage.CHECK_FAILED -> R.string.habits_check_failed
            HabitsMessage.ARCHIVE_OFFLINE -> R.string.habits_archive_offline
            HabitsMessage.ARCHIVE_FAILED -> R.string.habits_archive_failed
            HabitsMessage.CONFLICT -> R.string.habits_conflict
        }
    val load = message == HabitsMessage.LOAD_OFFLINE || message == HabitsMessage.LOAD_FAILED
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag(HabitsTags.MESSAGE)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            if (load) {
                TextButton(onClick = callbacks.reload, modifier = Modifier.testTag(HabitsTags.RETRY)) {
                    Text(stringResource(R.string.habits_retry))
                }
            } else {
                TextButton(onClick = callbacks.dismissMessage) { Text(stringResource(R.string.habits_dismiss)) }
            }
        }
    }
}

private const val DIMMED = 0.6f
private const val PERCENT = 100
private val CIRCLE = 40.dp
private val FAB_CLEARANCE = 88.dp
