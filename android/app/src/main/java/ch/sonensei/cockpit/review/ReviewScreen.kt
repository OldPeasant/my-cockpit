package ch.sonensei.cockpit.review

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
import androidx.compose.material.icons.outlined.PauseCircle
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
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder

/**
 * The weekly review screen. Its view model comes from Hilt, so outside a Hilt activity (the shell's navigation tests,
 * previews) it shows the placeholder instead of failing.
 */
@Composable
fun ReviewScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        ReviewRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_review, Icons.Outlined.EventRepeat, modifier)
    }
}

@Composable
fun ReviewRoute(modifier: Modifier = Modifier) {
    val viewModel: ReviewViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Reloaded each time the screen is shown, so progress made on the web meanwhile shows up.
    LaunchedEffect(Unit) { viewModel.refresh() }
    ReviewContent(state, onRetry = viewModel::refresh, modifier = modifier)
}

/** Where a step stands in the open review. */
enum class StepState { DONE, CURRENT, AHEAD }

internal fun ReviewStep.stateIn(current: OpenReview?): StepState =
    when {
        current == null || this > current.step -> StepState.AHEAD
        this == current.step -> StepState.CURRENT
        else -> StepState.DONE
    }

internal object ReviewTags {
    const val CONTENT = "review-content"
    const val MESSAGE = "review-message"
    const val RETRY = "review-retry"
    const val LAST = "review-last"
    const val PROGRESS = "review-progress"
    const val NO_STALLED = "review-no-stalled"

    fun step(step: ReviewStep) = "review-step:${step.name}"

    fun project(project: ReviewProject) = "review-project:${project.id}"
}

private val ReviewStep.label: Int
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

/** The review's progress and stalled projects; nothing here changes the review. */
@Composable
fun ReviewContent(
    state: ReviewUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        state.message?.let { Banner(it, state.loading, onRetry) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.padding(top = 4.dp))
        val overview = state.overview ?: return@Column
        LazyColumn(Modifier.fillMaxSize().testTag(ReviewTags.CONTENT), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Summary(overview) }
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
private fun Summary(overview: ReviewOverview) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

@Composable
private fun Banner(
    message: ReviewMessage,
    loading: Boolean,
    onRetry: () -> Unit,
) {
    val (text, color) =
        when (message) {
            ReviewMessage.OFFLINE -> R.string.review_offline to MaterialTheme.colorScheme.secondaryContainer
            ReviewMessage.FAILED -> R.string.review_failed to MaterialTheme.colorScheme.errorContainer
        }
    Surface(color = color, modifier = Modifier.fillMaxWidth().testTag(ReviewTags.MESSAGE)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            )
            TextButton(onClick = onRetry, enabled = !loading, modifier = Modifier.testTag(ReviewTags.RETRY)) {
                Text(stringResource(R.string.review_retry))
            }
        }
    }
}
