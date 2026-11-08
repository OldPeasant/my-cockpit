package ch.sonensei.cockpit.review

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.inbox.InboxRoute
import ch.sonensei.cockpit.gtd.projects.ProjectOpenRequests
import ch.sonensei.cockpit.ui.PlaceholderScreen
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.util.UUID

/**
 * The weekly review screen. Its view model comes from Hilt, so outside a Hilt activity (the shell's navigation tests,
 * previews) it shows the placeholder instead of failing. [onOpen] opens another screen (Mail, the GTD lists).
 */
@Composable
fun ReviewScreen(
    onOpen: (CockpitDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        ReviewRoute(onOpen, modifier)
    } else {
        PlaceholderScreen(R.string.nav_review, Icons.Outlined.EventRepeat, modifier)
    }
}

@Composable
fun ReviewRoute(
    onOpen: (CockpitDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ReviewViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Reloaded each time the screen is shown, so progress made on the web meanwhile shows up.
    LaunchedEffect(Unit) { viewModel.refresh() }
    // Back in the wizard returns to the overview; the review stays open on the server.
    BackHandler(enabled = state.wizardReview != null, onBack = viewModel::closeWizard)
    ReviewContent(state, ReviewCallbacks.of(viewModel, onOpen), modifier = modifier, inbox = { InboxRoute(it) })
}

/** What the review screen can ask for; one function per user action, so it can be tested without a view model. */
data class ReviewCallbacks(
    val retry: () -> Unit = {},
    val start: () -> Unit = {},
    val close: () -> Unit = {},
    val goTo: (ReviewStep) -> Unit = {},
    val next: () -> Unit = {},
    val previous: () -> Unit = {},
    val complete: () -> Unit = {},
    val loadStep: (ReviewStep) -> Unit = {},
    val setCaptureText: (String) -> Unit = {},
    val capture: () -> Unit = {},
    val setReflection: (String) -> Unit = {},
    val dismissMessage: () -> Unit = {},
    val open: (CockpitDestination) -> Unit = {},
    val keepWaiting: (ReviewTask) -> Unit = {},
    val followUp: (ReviewTask, String) -> Unit = { _, _ -> },
    val completeWaiting: (ReviewTask) -> Unit = {},
    val openProject: (ReviewProject) -> Unit = {},
    val keepProject: (ReviewProject) -> Unit = {},
    val setProjectStatus: (ReviewProject, ProjectStatus) -> Unit = { _, _ -> },
    val setNextStepDraft: (ReviewProject, String) -> Unit = { _, _ -> },
    val addNextStep: (ReviewProject) -> Unit = {},
    val keepNextAction: (ReviewTask) -> Unit = {},
    val moveToSomeday: (ReviewTask) -> Unit = {},
    val deleteNextAction: (ReviewTask) -> Unit = {},
    val activate: (SomedayItem) -> Unit = {},
    val keepSomeday: (SomedayItem) -> Unit = {},
    val deleteSomeday: (SomedayItem) -> Unit = {},
) {
    companion object {
        fun of(
            vm: ReviewViewModel,
            onOpen: (CockpitDestination) -> Unit,
        ) = ReviewCallbacks(
            retry = vm::refresh,
            start = vm::start,
            close = vm::closeWizard,
            goTo = vm::goTo,
            next = vm::next,
            previous = vm::previous,
            complete = vm::complete,
            loadStep = vm::loadStep,
            setCaptureText = vm::setCaptureText,
            capture = vm::capture,
            setReflection = vm::setReflection,
            dismissMessage = vm::dismissMessage,
            open = onOpen,
            keepWaiting = vm::keepWaiting,
            followUp = vm::followUp,
            completeWaiting = vm::completeWaiting,
            openProject = {
                ProjectOpenRequests.request(it.id)
                onOpen(CockpitDestination.Projects)
            },
            keepProject = vm::keepProject,
            setProjectStatus = vm::setProjectStatus,
            setNextStepDraft = vm::setNextStepDraft,
            addNextStep = vm::addNextStep,
            keepNextAction = vm::keepNextAction,
            moveToSomeday = vm::moveToSomeday,
            deleteNextAction = vm::deleteNextAction,
            activate = vm::activate,
            keepSomeday = vm::keepSomeday,
            deleteSomeday = vm::deleteSomeday,
        )
    }
}

/** Where a step stands in the open review. */
enum class StepState { DONE, CURRENT, AHEAD }

internal fun ReviewStep.stateIn(current: OpenReview?): StepState = current?.let { stateAt(it.step) } ?: StepState.AHEAD

internal fun ReviewStep.stateAt(current: ReviewStep): StepState =
    when {
        this > current -> StepState.AHEAD
        this == current -> StepState.CURRENT
        else -> StepState.DONE
    }

internal object ReviewTags {
    const val CONTENT = "review-content"
    const val MESSAGE = "review-message"
    const val RETRY = "review-retry"
    const val DISMISS = "review-dismiss"
    const val LAST = "review-last"
    const val PROGRESS = "review-progress"
    const val NO_STALLED = "review-no-stalled"
    const val START = "review-start"
    const val COMPLETED = "review-completed"
    const val WIZARD = "review-wizard"
    const val STEP_TITLE = "review-step-title"
    const val JUMP = "review-jump"
    const val CLOSE = "review-close"
    const val PREVIOUS = "review-previous"
    const val NEXT = "review-next"
    const val FINISH = "review-finish"
    const val INBOX = "review-inbox"
    const val OPEN_MAIL = "review-open-mail"
    const val CAPTURE_FIELD = "review-capture-field"
    const val CAPTURE_ADD = "review-capture-add"
    const val CAPTURED = "review-captured"
    const val EVENTS_EMPTY = "review-events-empty"
    const val STEP_FAILED = "review-step-failed"
    const val STEP_RETRY = "review-step-retry"
    const val HABITS_FAILED = "review-habits-failed"
    const val NO_HABITS = "review-no-habits"
    const val REFLECTION = "review-reflection"
    const val DECISION = "review-decision"
    const val ITEMS_EMPTY = "review-items-empty"
    const val KEEP = "review-keep"
    const val FOLLOW_UP = "review-follow-up"
    const val DONE = "review-done"
    const val TO_SOMEDAY = "review-to-someday"
    const val DELETE = "review-delete"
    const val ACTIVATE = "review-activate"
    const val OPEN_PROJECT = "review-open-project"
    const val STATUS = "review-status"
    const val NEXT_STEP_FIELD = "review-next-step-field"
    const val NEXT_STEP_ADD = "review-next-step-add"

    fun step(step: ReviewStep) = "review-step:${step.name}"

    fun segment(step: ReviewStep) = "review-segment:${step.name}"

    fun jumpTo(step: ReviewStep) = "review-jump:${step.name}"

    fun project(project: ReviewProject) = "review-project:${project.id}"

    fun event(event: ReviewEvent) = "review-event:${event.key}"

    fun habit(id: UUID) = "review-habit:$id"

    /** A row of steps 4 to 7. */
    fun item(id: UUID) = "review-item:$id"

    fun status(status: ProjectStatus) = "review-status:${status.name}"
}

internal val ReviewStep.label: Int
    get() =
        when (this) {
            ReviewStep.GET_CLEAR -> R.string.review_step_get_clear
            ReviewStep.PAST_CALENDAR -> R.string.review_step_past_calendar
            ReviewStep.UPCOMING_CALENDAR -> R.string.review_step_upcoming_calendar
            ReviewStep.WAITING_FOR -> R.string.review_step_waiting_for
            ReviewStep.PROJECTS -> R.string.review_step_projects
            ReviewStep.NEXT_ACTIONS -> R.string.review_step_next_actions
            ReviewStep.SOMEDAY -> R.string.review_step_someday
            ReviewStep.REFLECTION -> R.string.review_step_reflection
        }

/**
 * The review: the wizard while the user is in an open review, else the overview with the progress, the stalled
 * projects and the button that starts or continues the review. [inbox] is the embedded inbox of the first step.
 */
@Composable
fun ReviewContent(
    state: ReviewUiState,
    callbacks: ReviewCallbacks,
    modifier: Modifier = Modifier,
    inbox: @Composable (Modifier) -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        state.message?.let { Banner(it, state.loading, callbacks) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.padding(top = 4.dp))
        val review = state.wizardReview
        if (review != null) {
            ReviewWizard(state, review, callbacks, inbox, Modifier.weight(1f))
            return@Column
        }
        val overview = state.overview ?: return@Column
        LazyColumn(Modifier.fillMaxSize().testTag(ReviewTags.CONTENT), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Summary(overview, state.completed) }
            item { StartButton(open = overview.current != null, saving = state.saving, onStart = callbacks.start) }
            item { SectionTitle(R.string.review_steps) }
            items(ReviewStep.entries, key = { it.name }) { StepRow(it, it.stateIn(overview.current)) }
            item { SectionTitle(R.string.review_stalled) }
            val stalled = overview.stalledProjects
            if (stalled.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.review_no_stalled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(ReviewTags.NO_STALLED),
                    )
                }
            } else {
                items(stalled, key = { it.id }) { ProjectRow(it) }
            }
        }
    }
}

@Composable
private fun Summary(
    overview: ReviewOverview,
    completed: Boolean,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (completed) {
            Text(
                stringResource(R.string.review_completed),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(ReviewTags.COMPLETED),
            )
        }
        val days = overview.daysSinceLastReview
        Text(
            when {
                days == null -> stringResource(R.string.review_never)
                days <= 0L -> stringResource(R.string.review_last_today)
                else -> pluralStringResource(R.plurals.review_last_days, days.toInt(), days.toInt())
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag(ReviewTags.LAST),
        )
        Text(
            overview.current?.let { stringResource(R.string.review_in_progress, it.step.number, ReviewStep.entries.size) }
                ?: stringResource(R.string.review_none_open),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ReviewTags.PROGRESS),
        )
    }
}

@Composable
private fun StartButton(
    open: Boolean,
    saving: Boolean,
    onStart: () -> Unit,
) {
    Button(
        onClick = onStart,
        enabled = !saving,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(ReviewTags.START),
    ) {
        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(if (open) R.string.review_continue else R.string.review_start), Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            stringResource(text),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun StepRow(
    step: ReviewStep,
    stepState: StepState,
) {
    val stateText =
        when (stepState) {
            StepState.DONE -> stringResource(R.string.review_step_done)
            StepState.CURRENT -> stringResource(R.string.review_step_current)
            StepState.AHEAD -> null
        }
    val label = stringResource(step.label)
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag(ReviewTags.step(step))
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull("${step.number}. $label", stateText).joinToString(", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color =
                when (stepState) {
                    StepState.DONE -> colors.secondaryContainer
                    StepState.CURRENT -> colors.primary
                    StepState.AHEAD -> colors.surfaceVariant
                },
            contentColor =
                when (stepState) {
                    StepState.DONE -> colors.onSecondaryContainer
                    StepState.CURRENT -> colors.onPrimary
                    StepState.AHEAD -> colors.onSurfaceVariant
                },
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (stepState == StepState.DONE) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                } else {
                    Text(step.number.toString(), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (stepState == StepState.CURRENT) FontWeight.SemiBold else null,
            color = if (stepState == StepState.DONE) colors.onSurfaceVariant else colors.onSurface,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        stateText?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProjectRow(project: ReviewProject) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(ReviewTags.project(project)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.PauseCircle, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(project.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.review_stalled_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Load failures offer a retry; a refused or conflicting change, or a failed capture, can be dismissed. */
@Composable
private fun Banner(
    message: ReviewMessage,
    loading: Boolean,
    callbacks: ReviewCallbacks,
) {
    val loadFailure = message == ReviewMessage.OFFLINE || message == ReviewMessage.FAILED
    val text =
        when (message) {
            ReviewMessage.OFFLINE -> R.string.review_offline
            ReviewMessage.FAILED -> R.string.review_failed
            ReviewMessage.SAVE_OFFLINE -> R.string.review_save_offline
            ReviewMessage.SAVE_FAILED -> R.string.review_save_failed
            ReviewMessage.CONFLICT -> R.string.review_conflict
            ReviewMessage.REFLECTION_FAILED -> R.string.review_reflection_failed
            ReviewMessage.CAPTURE_FAILED -> R.string.review_capture_failed
            ReviewMessage.ACTION_FAILED -> R.string.review_action_failed
            ReviewMessage.ITEM_CHANGED -> R.string.review_item_changed
        }
    val offline = message == ReviewMessage.OFFLINE || message == ReviewMessage.SAVE_OFFLINE
    val color =
        if (offline || message == ReviewMessage.CONFLICT || message == ReviewMessage.ITEM_CHANGED) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        }
    Surface(color = color, modifier = Modifier.fillMaxWidth().testTag(ReviewTags.MESSAGE)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (offline) Icons.Outlined.CloudOff else Icons.Outlined.Info, contentDescription = null)
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            )
            if (loadFailure) {
                TextButton(onClick = callbacks.retry, enabled = !loading, modifier = Modifier.testTag(ReviewTags.RETRY)) {
                    Text(stringResource(R.string.review_retry))
                }
            } else {
                TextButton(onClick = callbacks.dismissMessage, modifier = Modifier.testTag(ReviewTags.DISMISS)) {
                    Text(stringResource(R.string.review_dismiss))
                }
            }
        }
    }
}
