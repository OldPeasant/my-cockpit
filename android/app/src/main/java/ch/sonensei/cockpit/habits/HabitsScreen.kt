package ch.sonensei.cockpit.habits

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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

/** What the habit list can ask for, so it can be tested without a view model. */
data class HabitsCallbacks(
    val toggle: (UUID) -> Unit = {},
    val reload: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
)

internal object HabitsTags {
    const val LIST = "habits-list"
    const val EMPTY = "habits-empty"
    const val LOADING = "habits-loading"
    const val MESSAGE = "habits-message"
    const val RETRY = "habits-retry"

    fun habit(id: UUID) = "habit:$id"
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
        HabitsCallbacks(toggle = viewModel::toggle, reload = viewModel::reload, dismissMessage = viewModel::dismissMessage),
        modifier,
    )
}

@Composable
fun HabitsContent(
    state: HabitsUiState,
    callbacks: HabitsCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        state.message?.let { MessageBanner(it, callbacks) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag(HabitsTags.LOADING))
        when {
            !state.loaded -> Unit
            state.habits.isEmpty() ->
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.habits_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(HabitsTags.EMPTY),
                    )
                }
            else ->
                LazyColumn(Modifier.fillMaxSize().testTag(HabitsTags.LIST)) {
                    section(R.string.habits_due_today, state.dueToday, state, callbacks, dimmed = false)
                    section(R.string.habits_not_due_today, state.notDueToday, state, callbacks, dimmed = true)
                }
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
    items(habits, key = { it.id }) { habit ->
        HabitRow(habit, checking = habit.id in state.checking, dimmed = dimmed, onToggle = { callbacks.toggle(habit.id) })
    }
}

@Composable
private fun HabitRow(
    habit: Habit,
    checking: Boolean,
    dimmed: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = habit.doneToday, enabled = !checking, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag(HabitsTags.habit(habit.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CheckCircle(done = habit.doneToday, checking = checking)
        Column(Modifier.weight(1f).alpha(if (dimmed && !habit.doneToday) DIMMED else 1f)) {
            Text(habit.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                details(habit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The check-off mark: an empty ring, or a filled circle with a tick once today is done. */
@Composable
private fun CheckCircle(
    done: Boolean,
    checking: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(40.dp)
            .then(if (done) Modifier.background(colors.primary, CircleShape) else Modifier.border(2.dp, colors.outline, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            checking ->
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = if (done) colors.onPrimary else colors.primary,
                )
            done -> Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.onPrimary)
        }
    }
}

/** Schedule, today's progress toward a target above 1, streak and 30-day completion, joined by commas. */
@Composable
private fun details(habit: Habit): String {
    val parts = mutableListOf<String>()
    parts +=
        when (habit.schedule) {
            HabitSchedule.DAILY -> stringResource(R.string.habits_daily)
            HabitSchedule.TIMES_PER_WEEK -> stringResource(R.string.habits_times_per_week, habit.timesPerWeek ?: 1)
            HabitSchedule.WEEKDAYS -> habit.weekdays.joinToString(" ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        }
    if (habit.targetCount > 1 && !habit.doneToday && habit.todayCount > 0) {
        parts += stringResource(R.string.habits_progress, habit.todayCount, habit.targetCount)
    }
    if (habit.currentStreak > 0) {
        val streak = if (habit.streakInWeeks) R.plurals.habits_streak_weeks else R.plurals.habits_streak_days
        parts += pluralStringResource(streak, habit.currentStreak, habit.currentStreak)
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
