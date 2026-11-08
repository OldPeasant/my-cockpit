package ch.sonensei.cockpit.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/** Step 4: each waiting-for item, kept (still waiting), followed up (a new next action) or done. */
@Composable
internal fun WaitingStep(
    state: ReviewUiState,
    callbacks: ReviewCallbacks,
) {
    ItemsStep(state.waiting, state, callbacks, ReviewStep.WAITING_FOR, R.string.review_waiting_empty, { it.id }) { task ->
        val since = task.waitingSince?.let { stringResource(R.string.review_waiting_since, DATE.format(it)) }
        val who = task.waitingForText?.trim()?.takeIf { it.isNotEmpty() }
        // Same title as the Waiting list's follow-up and the web wizard's.
        val followUpTitle =
            if (who == null) {
                stringResource(R.string.waiting_follow_up_title, task.title)
            } else {
                stringResource(R.string.waiting_follow_up_title_who, who, task.title)
            }
        ItemRow(
            task.id,
            task.title,
            listOfNotNull(who?.let { stringResource(R.string.review_waiting_for_whom, it) }, since).joinToString(" · "),
            busy = task.id in state.busy,
        ) { enabled ->
            Choice(R.string.review_keep_waiting, ReviewTags.KEEP, enabled) { callbacks.keepWaiting(task) }
            Choice(R.string.waiting_follow_up, ReviewTags.FOLLOW_UP, enabled) { callbacks.followUp(task, followUpTitle) }
            Choice(R.string.review_waiting_done, ReviewTags.DONE, enabled) { callbacks.completeWaiting(task) }
        }
    }
}

/**
 * Step 5: each active project, stalled ones first; a stalled one gets its next step typed in. The status can change
 * and "looks good" confirms it; the title opens the project's detail.
 */
@Composable
internal fun ProjectsStep(
    state: ReviewUiState,
    callbacks: ReviewCallbacks,
) {
    ItemsStep(state.projectList, state, callbacks, ReviewStep.PROJECTS, R.string.review_projects_empty, { it.id }) { project ->
        val busy = project.id in state.busy
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(ReviewTags.item(project.id)),
        ) {
            Text(
                project.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .clickable(onClickLabel = stringResource(R.string.review_open_project)) { callbacks.openProject(project) }
                        .padding(vertical = 4.dp)
                        .testTag(ReviewTags.OPEN_PROJECT),
            )
            Text(
                stringResource(if (project.stalled) R.string.review_stalled_hint else R.string.review_has_next_step),
                style = MaterialTheme.typography.bodySmall,
                color = if (project.stalled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (project.stalled) NextStepField(project, state, callbacks, busy)
            ChoiceRow {
                StatusChoice(project, enabled = !busy) { callbacks.setProjectStatus(project, it) }
                Choice(R.string.review_looks_good, ReviewTags.KEEP, !busy) { callbacks.keepProject(project) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

/** Step 6: next actions untouched for more than 30 days, kept, moved to someday/maybe or deleted. */
@Composable
internal fun NextActionsStep(
    state: ReviewUiState,
    callbacks: ReviewCallbacks,
) {
    ItemsStep(state.staleNext, state, callbacks, ReviewStep.NEXT_ACTIONS, R.string.review_next_empty, { it.id }) { task ->
        val changed = task.updatedAt?.let { stringResource(R.string.review_last_changed, DATE.format(it.atZone(ZoneId.systemDefault()))) }
        ItemRow(task.id, task.title, changed.orEmpty(), busy = task.id in state.busy) { enabled ->
            Choice(R.string.review_keep, ReviewTags.KEEP, enabled) { callbacks.keepNextAction(task) }
            Choice(R.string.review_to_someday, ReviewTags.TO_SOMEDAY, enabled) { callbacks.moveToSomeday(task) }
            Choice(R.string.review_delete, ReviewTags.DELETE, enabled) { callbacks.deleteNextAction(task) }
        }
    }
}

/** Step 7: the someday/maybe tasks and projects, activated, kept or deleted. */
@Composable
internal fun SomedayStep(
    state: ReviewUiState,
    callbacks: ReviewCallbacks,
) {
    ItemsStep(state.someday, state, callbacks, ReviewStep.SOMEDAY, R.string.review_someday_empty, { it.id }) { item ->
        val kind = stringResource(if (item.project) R.string.review_kind_project else R.string.review_kind_task)
        ItemRow(item.id, item.title, kind, busy = item.id in state.busy) { enabled ->
            Choice(R.string.review_activate, ReviewTags.ACTIVATE, enabled) { callbacks.activate(item) }
            Choice(R.string.review_keep, ReviewTags.KEEP, enabled) { callbacks.keepSomeday(item) }
            Choice(R.string.review_delete, ReviewTags.DELETE, enabled) { callbacks.deleteSomeday(item) }
        }
    }
}

/** The last decision, the items still to decide on (or why there are none) and a retry when the load failed. */
@Composable
private fun <T> ItemsStep(
    data: StepData<List<T>>,
    state: ReviewUiState,
    callbacks: ReviewCallbacks,
    step: ReviewStep,
    empty: Int,
    key: (T) -> UUID,
    row: @Composable (T) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "decision") { DecisionLine(state.decision) }
        val list = data.value
        when {
            list == null && data.failed -> item(key = "failed") { StepFailed(callbacks, step) }
            list == null -> if (data.loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
            list.isEmpty() ->
                item(key = "empty") {
                    Text(
                        stringResource(empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp).testTag(ReviewTags.ITEMS_EMPTY),
                    )
                }
            else -> itemRows(list, key, row)
        }
    }
}

private fun <T> LazyListScope.itemRows(
    list: List<T>,
    key: (T) -> UUID,
    row: @Composable (T) -> Unit,
) = items(list, key = { key(it).toString() }) {
    row(it)
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

/** Announces what happened to the item decided last, so its leaving the list is not a surprise. */
@Composable
private fun DecisionLine(decision: ReviewDecision?) {
    val text = decision?.let { decisionText(it) }.orEmpty()
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = if (text.isEmpty()) 0.dp else 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag(ReviewTags.DECISION),
    )
}

@Composable
private fun decisionText(decision: ReviewDecision): String =
    when (decision.kind) {
        ReviewDecision.Kind.STILL_WAITING -> stringResource(R.string.review_decided_waiting, decision.title)
        ReviewDecision.Kind.FOLLOWED_UP -> stringResource(R.string.review_decided_followed_up, decision.title)
        ReviewDecision.Kind.COMPLETED -> stringResource(R.string.review_decided_completed, decision.title)
        ReviewDecision.Kind.KEPT -> stringResource(R.string.review_decided_kept, decision.title)
        ReviewDecision.Kind.MOVED_TO_SOMEDAY -> stringResource(R.string.review_decided_someday, decision.title)
        ReviewDecision.Kind.DELETED -> stringResource(R.string.review_decided_deleted, decision.title)
        ReviewDecision.Kind.ACTIVATED -> stringResource(R.string.review_decided_activated, decision.title)
        ReviewDecision.Kind.NEXT_STEP_ADDED -> stringResource(R.string.review_decided_next_step, decision.title, decision.project.orEmpty())
        ReviewDecision.Kind.STATUS_CHANGED ->
            stringResource(R.string.review_decided_status, decision.title, decision.status?.let { statusName(it) }.orEmpty())
    }

/** A task or someday item: its title, a line about it and its choices, disabled while a decision on it is saved. */
@Composable
private fun ItemRow(
    id: UUID,
    title: String,
    meta: String,
    busy: Boolean,
    choices: @Composable (enabled: Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(ReviewTags.item(id)),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ChoiceRow { choices(!busy) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
private fun ChoiceRow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Choice(
    label: Int,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.testTag(tag)) { Text(stringResource(label)) }
}

/** The project's status; picking another one saves it (and the project leaves the list). */
@Composable
private fun StatusChoice(
    project: ReviewProject,
    enabled: Boolean,
    onPick: (ProjectStatus) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(R.string.review_project_status, project.title)
    Box {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = description }.testTag(ReviewTags.STATUS),
        ) {
            Text(statusName(project.status))
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ProjectStatus.entries.forEach { status ->
                DropdownMenuItem(
                    text = { Text(statusName(status)) },
                    enabled = status != project.status,
                    onClick = {
                        open = false
                        onPick(status)
                    },
                    modifier = Modifier.testTag(ReviewTags.status(status)),
                )
            }
        }
    }
}

/** The next step of a stalled project, typed in and added as a next action of the project. */
@Composable
private fun NextStepField(
    project: ReviewProject,
    state: ReviewUiState,
    callbacks: ReviewCallbacks,
    busy: Boolean,
) {
    val draft = state.nextStepDrafts[project.id].orEmpty()
    val description = stringResource(R.string.review_next_step_for, project.title)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { callbacks.setNextStepDraft(project, it) },
            label = { Text(stringResource(R.string.review_next_step)) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { callbacks.addNextStep(project) }),
            modifier = Modifier.weight(1f).semantics { contentDescription = description }.testTag(ReviewTags.NEXT_STEP_FIELD),
        )
        TextButton(
            onClick = { callbacks.addNextStep(project) },
            enabled = !busy && draft.isNotBlank(),
            modifier = Modifier.padding(start = 4.dp).testTag(ReviewTags.NEXT_STEP_ADD),
        ) { Text(stringResource(R.string.review_next_step_add)) }
    }
}

@Composable
private fun statusName(status: ProjectStatus): String =
    stringResource(
        when (status) {
            ProjectStatus.ACTIVE -> R.string.projects_status_active
            ProjectStatus.ON_HOLD -> R.string.projects_status_on_hold
            ProjectStatus.SOMEDAY -> R.string.projects_status_someday
            ProjectStatus.COMPLETED -> R.string.projects_status_completed
            ProjectStatus.DROPPED -> R.string.projects_status_dropped
        },
    )

private val DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
