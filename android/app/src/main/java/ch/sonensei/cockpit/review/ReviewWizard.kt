package ch.sonensei.cockpit.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.habits.Habit
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * The wizard of an open review: the step header with the step track (tap a segment to jump), the step itself, and
 * previous / next (or complete, on the last step) at the bottom.
 */
@Composable
internal fun ReviewWizard(
    state: ReviewUiState,
    review: OpenReview,
    callbacks: ReviewCallbacks,
    inbox: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val step = review.step
    // Each time a step is shown its data is loaded again, so a calendar changed meanwhile shows up.
    LaunchedEffect(step) { callbacks.loadStep(step) }
    Column(modifier.fillMaxWidth().testTag(ReviewTags.WIZARD)) {
        StepHeader(step, state.saving, callbacks)
        StepTrack(step, state.saving, callbacks.goTo)
        Text(
            stringResource(step.hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (step) {
                ReviewStep.GET_CLEAR -> GetClearStep(inbox, callbacks.open)
                ReviewStep.PAST_CALENDAR -> CalendarStep(state.pastEvents, upcoming = false, state, callbacks)
                ReviewStep.UPCOMING_CALENDAR -> CalendarStep(state.upcomingEvents, upcoming = true, state, callbacks)
                ReviewStep.WAITING_FOR -> ListStep(CockpitDestination.Waiting, callbacks.open)
                ReviewStep.PROJECTS -> ListStep(CockpitDestination.Projects, callbacks.open)
                ReviewStep.NEXT_ACTIONS -> ListStep(CockpitDestination.Next, callbacks.open)
                ReviewStep.SOMEDAY -> ListStep(CockpitDestination.Someday, callbacks.open)
                ReviewStep.REFLECTION -> ReflectionStep(state, callbacks)
            }
        }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = callbacks.previous,
                enabled = step.after(-1) != null && !state.saving,
                modifier = Modifier.testTag(ReviewTags.PREVIOUS),
            ) { Text(stringResource(R.string.review_previous)) }
            Box(Modifier.weight(1f))
            if (step.after(1) == null) {
                Button(onClick = callbacks.complete, enabled = !state.saving, modifier = Modifier.testTag(ReviewTags.FINISH)) {
                    Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.review_finish), Modifier.padding(start = 8.dp))
                }
            } else {
                Button(onClick = callbacks.next, enabled = !state.saving, modifier = Modifier.testTag(ReviewTags.NEXT)) {
                    Text(stringResource(R.string.review_next))
                }
            }
        }
    }
}

/** "Step 3 of 8" and the step's name, which opens the list of steps to jump to. */
@Composable
private fun StepHeader(
    step: ReviewStep,
    saving: Boolean,
    callbacks: ReviewCallbacks,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = callbacks.close, modifier = Modifier.testTag(ReviewTags.CLOSE)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.review_close))
        }
        val jump = stringResource(R.string.review_jump)
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .clickable(enabled = !saving, onClickLabel = jump, role = Role.Button) { menu = true }
                    .padding(vertical = 6.dp, horizontal = 4.dp)
                    .testTag(ReviewTags.JUMP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        stringResource(R.string.review_step_of, step.number, ReviewStep.entries.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(step.label),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(ReviewTags.STEP_TITLE),
                    )
                }
                Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.padding(start = 4.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ReviewStep.entries.forEach { target ->
                    DropdownMenuItem(
                        text = { Text("${target.number}. ${stringResource(target.label)}") },
                        leadingIcon = {
                            if (target.stateAt(step) == StepState.DONE) {
                                Icon(Icons.Outlined.Check, contentDescription = null)
                            }
                        },
                        enabled = target != step,
                        onClick = {
                            menu = false
                            callbacks.goTo(target)
                        },
                        modifier = Modifier.testTag(ReviewTags.jumpTo(target)),
                    )
                }
            }
        }
    }
}

/** The eight steps as one segmented track: done ones filled, the current one strong, the ones ahead empty. */
@Composable
private fun StepTrack(
    current: ReviewStep,
    saving: Boolean,
    onJump: (ReviewStep) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        ReviewStep.entries.forEach { step ->
            val stepState = step.stateAt(current)
            val label = stringResource(step.label)
            val stateText =
                when (stepState) {
                    StepState.DONE -> stringResource(R.string.review_step_done)
                    StepState.CURRENT -> stringResource(R.string.review_step_current)
                    StepState.AHEAD -> null
                }
            Box(
                Modifier
                    .weight(1f)
                    .height(24.dp)
                    .clickable(enabled = !saving && step != current, role = Role.Button) { onJump(step) }
                    .semantics { contentDescription = listOfNotNull("${step.number}. $label", stateText).joinToString(", ") }
                    .testTag(ReviewTags.segment(step)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .fillMaxWidth()
                        .height(if (stepState == StepState.CURRENT) 8.dp else 4.dp)
                        .background(
                            when (stepState) {
                                StepState.DONE -> colors.primary.copy(alpha = 0.55f)
                                StepState.CURRENT -> colors.primary
                                StepState.AHEAD -> colors.surfaceVariant
                            },
                            RoundedCornerShape(4.dp),
                        ),
                )
            }
        }
    }
}

private val ReviewStep.hint: Int
    get() =
        when (this) {
            ReviewStep.GET_CLEAR -> R.string.review_hint_get_clear
            ReviewStep.PAST_CALENDAR -> R.string.review_hint_past_calendar
            ReviewStep.UPCOMING_CALENDAR -> R.string.review_hint_upcoming_calendar
            ReviewStep.WAITING_FOR -> R.string.review_hint_waiting_for
            ReviewStep.PROJECTS -> R.string.review_hint_projects
            ReviewStep.NEXT_ACTIONS -> R.string.review_hint_next_actions
            ReviewStep.SOMEDAY -> R.string.review_hint_someday
            ReviewStep.REFLECTION -> R.string.review_hint_reflection
        }

/** Step 1: the Inbox with its processing mode, embedded, and the way to Mail for flagged mails. */
@Composable
private fun GetClearStep(
    inbox: @Composable (Modifier) -> Unit,
    onOpen: (CockpitDestination) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.review_mail_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onOpen(CockpitDestination.Mail) }, modifier = Modifier.testTag(ReviewTags.OPEN_MAIL)) {
                    Icon(Icons.Outlined.Mail, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.review_open_mail), Modifier.padding(start = 6.dp))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().testTag(ReviewTags.INBOX)) { inbox(Modifier.fillMaxSize()) }
    }
}

/** Steps 2 and 3: the events by day, with a quick-add field that captures into the Inbox. */
@Composable
private fun CalendarStep(
    events: StepData<List<ReviewEvent>>,
    upcoming: Boolean,
    state: ReviewUiState,
    callbacks: ReviewCallbacks,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { CaptureField(state, callbacks) }
        val list = events.value
        when {
            list == null && events.failed ->
                item {
                    StepFailed(
                        callbacks,
                        if (upcoming) ReviewStep.UPCOMING_CALENDAR else ReviewStep.PAST_CALENDAR,
                    )
                }
            list == null -> Unit
            list.isEmpty() ->
                item {
                    Text(
                        stringResource(if (upcoming) R.string.review_upcoming_empty else R.string.review_past_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp).testTag(ReviewTags.EVENTS_EMPTY),
                    )
                }
            else -> eventDays(list)
        }
    }
}

private fun LazyListScope.eventDays(events: List<ReviewEvent>) {
    events.groupBy { it.day }.forEach { (day, dayEvents) ->
        item(key = "day:$day") { DayHeader(day) }
        items(dayEvents, key = { it.key }) { EventRow(it) }
    }
}

@Composable
private fun DayHeader(day: LocalDate) {
    Text(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(day),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun EventRow(event: ReviewEvent) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag(ReviewTags.event(event))
            .semantics(mergeDescendants = true) {},
    ) {
        Text(
            event.time?.let { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(it) }
                ?: stringResource(R.string.review_all_day),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(event.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CaptureField(
    state: ReviewUiState,
    callbacks: ReviewCallbacks,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.captureText,
                onValueChange = callbacks.setCaptureText,
                label = { Text(stringResource(R.string.review_capture_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { callbacks.capture() }),
                modifier = Modifier.weight(1f).testTag(ReviewTags.CAPTURE_FIELD),
            )
            TextButton(
                onClick = callbacks.capture,
                enabled = state.captureText.isNotBlank(),
                modifier = Modifier.padding(start = 4.dp).testTag(ReviewTags.CAPTURE_ADD),
            ) { Text(stringResource(R.string.review_capture_add)) }
        }
        state.captured?.let {
            Text(
                stringResource(R.string.review_captured, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp).testTag(ReviewTags.CAPTURED),
            )
        }
    }
}

@Composable
private fun StepFailed(
    callbacks: ReviewCallbacks,
    step: ReviewStep,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.review_step_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).testTag(ReviewTags.STEP_FAILED),
        )
        TextButton(onClick = { callbacks.loadStep(step) }, modifier = Modifier.testTag(ReviewTags.STEP_RETRY)) {
            Text(stringResource(R.string.review_retry))
        }
    }
}

/** Steps 4 to 7: reviewed in their list for now (their in-wizard actions come with and-review-steps). */
@Composable
private fun ListStep(
    list: CockpitDestination,
    onOpen: (CockpitDestination) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.review_list_hint), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { onOpen(list) }, modifier = Modifier.testTag(ReviewTags.OPEN_LIST)) {
            Icon(list.icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.review_open_list, stringResource(list.labelRes)), Modifier.padding(start = 8.dp))
        }
    }
}

/** Step 8: the habits of the last 30 days and the reflection, saved in today's journal entry on completion. */
@Composable
private fun ReflectionStep(
    state: ReviewUiState,
    callbacks: ReviewCallbacks,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Text(
                stringResource(R.string.review_habits),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        val habits = state.habits.value
        when {
            habits == null && state.habits.failed ->
                item {
                    Text(
                        stringResource(R.string.review_habits_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(ReviewTags.HABITS_FAILED),
                    )
                }
            habits == null -> Unit
            habits.isEmpty() ->
                item {
                    Text(
                        stringResource(R.string.review_no_habits),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(ReviewTags.NO_HABITS),
                    )
                }
            else -> items(habits, key = { it.id }) { HabitRow(it) }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                OutlinedTextField(
                    value = state.reflection,
                    onValueChange = callbacks.setReflection,
                    label = { Text(stringResource(R.string.review_reflection_label)) },
                    placeholder = { Text(stringResource(R.string.review_reflection_placeholder)) },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth().testTag(ReviewTags.REFLECTION),
                )
                Text(
                    stringResource(R.string.review_reflection_journal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun HabitRow(habit: Habit) {
    val streak =
        pluralStringResource(
            if (habit.streakInWeeks) R.plurals.review_habit_streak_weeks else R.plurals.review_habit_streak_days,
            habit.currentStreak,
            habit.currentStreak,
        )
    val rate = habit.completionLast30Days?.let { stringResource(R.string.review_habit_rate, (it * PERCENT).roundToInt()) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag(ReviewTags.habit(habit.id))
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(habit.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            listOfNotNull(rate, streak).joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val PERCENT = 100
