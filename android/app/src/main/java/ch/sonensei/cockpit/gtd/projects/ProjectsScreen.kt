package ch.sonensei.cockpit.gtd.projects

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.lists.ActionSnackbar
import ch.sonensei.cockpit.gtd.lists.ActionSnackbarEffect
import ch.sonensei.cockpit.gtd.lists.Centered
import ch.sonensei.cockpit.gtd.lists.LiveListTags
import ch.sonensei.cockpit.gtd.lists.LiveTaskRow
import ch.sonensei.cockpit.gtd.lists.LoadError
import ch.sonensei.cockpit.gtd.lists.StaleBanner
import ch.sonensei.cockpit.gtd.next.OfflineBanner
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/** The Projects tab; outside a Hilt activity (navigation tests, previews) it shows the placeholder. */
@Composable
fun ProjectsScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        ProjectsRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_projects, Icons.Outlined.AccountTree, modifier)
    }
}

@Composable
fun ProjectsRoute(
    modifier: Modifier = Modifier,
    viewModel: ProjectsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProjectsContent(state, ProjectsCallbacks.of(viewModel), modifier)
}

/** What the Projects screen can ask for, so the screen can be tested without a view model. */
data class ProjectsCallbacks(
    val open: (GtdProject) -> Unit = {},
    val close: () -> Unit = {},
    val toggleFinished: () -> Unit = {},
    val retry: () -> Unit = {},
    val complete: (GtdTask) -> Unit = {},
    val makeNextStep: (GtdTask) -> Unit = {},
    val addNextStep: (String) -> Unit = {},
    val choose: () -> Unit = {},
    val skip: () -> Unit = {},
    val dismiss: (ActionSnackbar) -> Unit = {},
) {
    companion object {
        fun of(vm: ProjectsViewModel) =
            ProjectsCallbacks(
                vm::open,
                vm::close,
                vm::toggleFinished,
                vm::retry,
                vm::complete,
                vm::makeNextStep,
                vm::addNextStep,
                vm::choose,
                vm::skip,
                vm::dismiss,
            )
    }
}

object ProjectsTags {
    const val LIST = "projects-list"
    const val EMPTY = "projects-empty"
    const val LOADING = "projects-loading"
    const val SHOW_FINISHED = "projects-show-finished"
    const val DETAIL = "project-detail"
    const val BACK = "project-back"
    const val CHOOSE = "project-choose-next-step"
    const val PROMPT = "next-step-prompt"
    const val PROMPT_NEW = "next-step-new"
    const val PROMPT_ADD = "next-step-add"
    const val PROMPT_SKIP = "next-step-skip"
    const val AREA_UNLOADED = "projects-area-unloaded"

    fun project(id: UUID) = "project-$id"

    fun stalled(id: UUID) = "project-stalled-$id"

    fun area(id: UUID?) = "projects-area-${id ?: "none"}"

    fun nextStepMarker(id: UUID) = "project-next-step-$id"

    fun makeNext(id: UUID) = "project-make-next-$id"

    fun choice(id: UUID) = "next-step-choice-$id"
}

@Composable
fun ProjectsContent(
    state: ProjectsUiState,
    callbacks: ProjectsCallbacks,
    modifier: Modifier = Modifier,
) {
    val snackbarHost = remember { SnackbarHostState() }
    ActionSnackbarEffect(state.message, snackbarHost, callbacks.dismiss)
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.offline) OfflineBanner(state.refreshedAt)
            val detail = state.detail
            if (detail != null) {
                BackHandler(onBack = callbacks.close)
                ProjectDetail(state, detail, callbacks)
            } else {
                ProjectList(state, callbacks)
            }
        }
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter))
    }
    state.detail?.prompt?.let { NextStepDialog(state, it, callbacks) }
}

@Composable
private fun ProjectList(
    state: ProjectsUiState,
    callbacks: ProjectsCallbacks,
) {
    val stalled = state.groups.sumOf { it.stalled }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                state.loading -> stringResource(R.string.tasks_loading)
                stalled > 0 -> pluralStringResource(R.plurals.projects_stalled_count, stalled, stalled)
                else -> stringResource(R.string.projects_none_stalled)
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        if (state.finished > 0) {
            FilterChip(
                selected = state.showFinished,
                onClick = callbacks.toggleFinished,
                label = { Text(stringResource(R.string.projects_show_finished, state.finished)) },
                modifier = Modifier.testTag(ProjectsTags.SHOW_FINISHED),
            )
        }
    }
    HorizontalDivider()
    state.areasError?.let { StaleBanner(it, callbacks.retry, stringResource(R.string.projects_areas_failed)) }
    when {
        state.groups.isNotEmpty() ->
            LazyColumn(Modifier.fillMaxSize().testTag(ProjectsTags.LIST)) {
                state.groups.forEach { group ->
                    item(key = "area-${group.area?.id ?: if (group.areaUnloaded) "unloaded" else "none"}") { AreaHeader(group) }
                    items(group.projects, key = { it.id }) { project -> ProjectRow(project, callbacks.open) }
                }
            }
        state.loading -> Centered(stringResource(R.string.tasks_loading), ProjectsTags.LOADING)
        else -> Centered(stringResource(R.string.projects_empty), ProjectsTags.EMPTY)
    }
}

@Composable
private fun AreaHeader(group: ProjectGroup) {
    Text(
        group.area?.title ?: stringResource(if (group.areaUnloaded) R.string.projects_area_unloaded else R.string.projects_no_area),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
                .testTag(if (group.areaUnloaded) ProjectsTags.AREA_UNLOADED else ProjectsTags.area(group.area?.id))
                .semantics { heading() },
    )
}

/** One project; a stalled one carries an accent bar and a "No next step" badge, the one loud thing on this screen. */
@Composable
private fun ProjectRow(
    project: GtdProject,
    onOpen: (GtdProject) -> Unit,
) {
    val status = statusLabel(project.status)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.projects_open)) {
                onOpen(project)
            }.testTag(ProjectsTags.project(project.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(56.dp)
                .background(if (project.stalled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surface),
        )
        ListItem(
            modifier = Modifier.weight(1f),
            headlineContent = { Text(project.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent =
                if (project.stalled || status != null || project.dueDate != null) {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (project.stalled) StalledBadge(project.id)
                            listOfNotNull(
                                status,
                                project.dueDate?.let {
                                    stringResource(R.string.tasks_due_on, DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(it))
                                },
                            ).forEach { Text(it, modifier = Modifier.padding(start = if (project.stalled) 8.dp else 0.dp, end = 12.dp)) }
                        }
                    }
                } else {
                    null
                },
        )
    }
}

@Composable
private fun StalledBadge(id: UUID) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.testTag(ProjectsTags.stalled(id)),
    ) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(R.string.projects_stalled),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** The label of a status other than active (which needs none). */
@Composable
private fun statusLabel(status: ProjectStatus): String? =
    when (status) {
        ProjectStatus.ACTIVE -> null
        ProjectStatus.ON_HOLD -> stringResource(R.string.projects_status_on_hold)
        ProjectStatus.SOMEDAY -> stringResource(R.string.projects_status_someday)
        ProjectStatus.COMPLETED -> stringResource(R.string.projects_status_completed)
        ProjectStatus.DROPPED -> stringResource(R.string.projects_status_dropped)
    }

@Composable
private fun ProjectDetail(
    state: ProjectsUiState,
    detail: ProjectDetailState,
    callbacks: ProjectsCallbacks,
) {
    val project = detail.project
    Column(Modifier.fillMaxSize().testTag(ProjectsTags.DETAIL)) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = callbacks.close, modifier = Modifier.testTag(ProjectsTags.BACK)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.projects_back))
            }
            Column(Modifier.weight(1f)) {
                Text(project.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                val meta = listOfNotNull(detail.area?.title, statusLabel(project.status)).joinToString(", ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        project.descriptionMd?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = DESCRIPTION_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        NextStepLine(state, detail, callbacks)
        HorizontalDivider()
        // The tasks shown are from an earlier load: say why changes are off, and offer to load them again.
        if (detail.loaded && detail.error != null) StaleBanner(detail.error, callbacks.retry)
        when {
            detail.tasks.isNotEmpty() ->
                LazyColumn(Modifier.fillMaxSize().testTag(LiveListTags.LIST)) {
                    items(detail.tasks, key = { it.id }) { task ->
                        ProjectTaskRow(state, detail, task, callbacks)
                        HorizontalDivider()
                    }
                }
            detail.loading -> Centered(stringResource(R.string.tasks_loading), LiveListTags.LOADING)
            detail.error != null && !detail.loaded ->
                Column(
                    Modifier.fillMaxSize().padding(32.dp).testTag(LiveListTags.ERROR),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(
                            if (detail.error ==
                                LoadError.OFFLINE
                            ) {
                                R.string.lists_error_offline
                            } else {
                                R.string.lists_error_failed
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(onClick = callbacks.retry, modifier = Modifier.testTag(LiveListTags.RETRY)) {
                        Text(stringResource(R.string.lists_retry))
                    }
                }
            else -> Centered(stringResource(R.string.projects_no_tasks), LiveListTags.EMPTY)
        }
    }
}

/** The project's next step, or, for a stalled project, why it is stalled and a way to choose one. */
@Composable
private fun NextStepLine(
    state: ProjectsUiState,
    detail: ProjectDetailState,
    callbacks: ProjectsCallbacks,
) {
    val next = detail.nextStep
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            next != null -> {
                Icon(
                    Icons.Outlined.Flag,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    stringResource(R.string.projects_next_step_is, next.title),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
            }
            detail.project.stalled -> {
                StalledBadge(detail.project.id)
                Text(
                    stringResource(R.string.projects_stalled_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp).weight(1f),
                )
                TextButton(onClick = callbacks.choose, enabled = !state.readOnly, modifier = Modifier.testTag(ProjectsTags.CHOOSE)) {
                    Text(stringResource(R.string.projects_choose_next_step))
                }
            }
        }
    }
}

@Composable
private fun ProjectTaskRow(
    state: ProjectsUiState,
    detail: ProjectDetailState,
    task: GtdTask,
    callbacks: ProjectsCallbacks,
) {
    val isNext = task.id == detail.project.nextTaskId
    val busy = task.id in state.busy
    LiveTaskRow(
        task = task,
        readOnly = state.readOnly,
        busy = busy,
        onComplete = callbacks.complete,
        overline =
            if (isNext) {
                {
                    Text(
                        stringResource(R.string.projects_next_step),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag(ProjectsTags.nextStepMarker(task.id)),
                    )
                }
            } else {
                null
            },
        supporting = listLabel(task.list)?.let { label -> { Text(label) } },
        action =
            if (isNext) {
                null
            } else {
                {
                    val label = stringResource(R.string.projects_make_next_step_label, task.title)
                    IconButton(
                        onClick = { callbacks.makeNextStep(task) },
                        enabled = !state.readOnly && !busy,
                        modifier = Modifier.testTag(ProjectsTags.makeNext(task.id)).semantics { contentDescription = label },
                    ) {
                        Icon(Icons.Outlined.Flag, contentDescription = null)
                    }
                }
            },
    )
}

/** Which list a project task is on, when it is not a plain next action. */
@Composable
private fun listLabel(list: TaskList): String? =
    when (list) {
        TaskList.NEXT -> null
        TaskList.INBOX -> stringResource(R.string.projects_list_inbox)
        TaskList.WAITING -> stringResource(R.string.nav_waiting)
        TaskList.SOMEDAY -> stringResource(R.string.nav_someday)
        TaskList.SCHEDULED -> stringResource(R.string.projects_list_scheduled)
    }

/** "Choose next step" (SPEC.md §5.2): pick an open task of the project, add a new one, or skip (stays stalled). */
@Composable
private fun NextStepDialog(
    state: ProjectsUiState,
    prompt: NextStepPrompt,
    callbacks: ProjectsCallbacks,
) {
    val detail = state.detail ?: return
    var title by rememberSaveable { mutableStateOf("") }
    val adding = detail.project.id in state.busy
    AlertDialog(
        onDismissRequest = callbacks.skip,
        modifier = Modifier.testTag(ProjectsTags.PROMPT),
        title = { Text(stringResource(R.string.projects_choose_next_step)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                prompt.done?.let { Text(stringResource(R.string.projects_prompt_done, it)) }
                detail.tasks.forEach { task ->
                    TextButton(
                        onClick = { callbacks.makeNextStep(task) },
                        enabled = !state.readOnly && task.id !in state.busy,
                        modifier = Modifier.fillMaxWidth().testTag(ProjectsTags.choice(task.id)),
                    ) {
                        Text(task.title, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (detail.tasks.isEmpty()) Text(stringResource(R.string.projects_prompt_no_tasks))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.projects_prompt_new)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(ProjectsTags.PROMPT_NEW),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { callbacks.addNextStep(title) },
                enabled = title.isNotBlank() && !adding && !state.readOnly,
                modifier = Modifier.testTag(ProjectsTags.PROMPT_ADD),
            ) { Text(stringResource(R.string.projects_prompt_add)) }
        },
        dismissButton = {
            TextButton(onClick = callbacks.skip, modifier = Modifier.testTag(ProjectsTags.PROMPT_SKIP)) {
                Text(stringResource(R.string.projects_prompt_skip))
            }
        },
    )
}

private const val DESCRIPTION_LINES = 4
