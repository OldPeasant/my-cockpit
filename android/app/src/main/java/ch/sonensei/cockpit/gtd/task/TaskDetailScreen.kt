package ch.sonensei.cockpit.gtd.task

import android.content.res.Resources
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.core.links.ItemRef
import ch.sonensei.cockpit.core.links.LinkedItemsSection
import ch.sonensei.cockpit.core.settings.userCreativityLabel
import ch.sonensei.cockpit.core.settings.userEnergyLabel
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.next.TaskListTags
import ch.sonensei.cockpit.notes.MarkdownView
import ch.sonensei.cockpit.search.SearchTarget
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import coil3.ImageLoader
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID
import kotlin.math.roundToInt

/** Where the detail screen sends the user: back, another task, an item with an Android screen, or a list screen. */
data class TaskDetailNavigation(
    val close: () -> Unit = {},
    val openTask: (UUID) -> Unit = {},
    val openItem: (type: String, id: UUID) -> Unit = { _, _ -> },
    val openScreen: (CockpitDestination) -> Unit = {},
)

/** The task detail screen of task [id]. Without Hilt (the shell's navigation tests) it shows nothing. */
@Composable
fun TaskDetailScreen(
    id: UUID,
    navigation: TaskDetailNavigation,
    modifier: Modifier = Modifier,
) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) TaskDetailRoute(id, navigation, modifier)
}

@Composable
fun TaskDetailRoute(
    id: UUID,
    navigation: TaskDetailNavigation,
    modifier: Modifier = Modifier,
    viewModel: TaskDetailViewModel = hiltViewModel(),
) {
    LaunchedEffect(id) { viewModel.open(id) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TaskDetailContent(
        state = state,
        callbacks =
            TaskDetailCallbacks.of(viewModel).copy(
                close = navigation.close,
                // The task is in the Trash now: leave its screen, then open the item it became.
                openConverted = { item ->
                    navigation.close()
                    when (item.type) {
                        ConvertedType.NOTE -> navigation.openItem("NOTE", item.id)
                        ConvertedType.EVENT -> navigation.openScreen(CockpitDestination.Calendar)
                    }
                },
            ),
        imageLoader = viewModel.imageLoader,
        modifier = modifier,
        remindersPanel = { detail, editable ->
            TaskRemindersSection(id, detail.task.reminderAnchors(), editable)
        },
        linksPanel = {
            LinkedItemsSection(ItemRef("TASK", id), open = { target ->
                when (target) {
                    is SearchTarget.InApp ->
                        if (target.type ==
                            "TASK"
                        ) {
                            navigation.openTask(target.id)
                        } else {
                            navigation.openItem(target.type, target.id)
                        }
                    is SearchTarget.Screen -> navigation.openScreen(target.destination)
                    is SearchTarget.Web -> Unit
                }
            })
        },
    )
}

/** What the screen can ask for, so it can be tested without a view model. */
data class TaskDetailCallbacks(
    val close: () -> Unit = {},
    val retry: () -> Unit = {},
    val startEditing: () -> Unit = {},
    val edit: ((TaskDraft) -> TaskDraft) -> Unit = {},
    val cancelEditing: () -> Unit = {},
    /** "New project…" of the picker, with the title entered. */
    val newProject: (String) -> Unit = {},
    val save: () -> Unit = {},
    val complete: () -> Unit = {},
    val reopen: () -> Unit = {},
    val move: (MoveTarget, String?) -> Unit = { _, _ -> },
    val delete: () -> Unit = {},
    val undoDelete: () -> Unit = {},
    val toggleItem: (ChecklistItem) -> Unit = {},
    val renameItem: (ChecklistItem, String) -> Unit = { _, _ -> },
    val addItem: (String) -> Unit = {},
    val deleteItem: (ChecklistItem) -> Unit = {},
    /** Moves the item to the index of the checklist. */
    val moveItem: (ChecklistItem, Int) -> Unit = { _, _ -> },
    val dismiss: (DetailMessage) -> Unit = {},
    val toReference: () -> Unit = {},
    val openToCalendar: () -> Unit = {},
    val editPlan: ((EventPlan) -> EventPlan) -> Unit = {},
    val retryCalendars: () -> Unit = {},
    val cancelToCalendar: () -> Unit = {},
    val confirmToCalendar: () -> Unit = {},
    /** Opens the note or event the task was converted into. */
    val openConverted: (ConvertedItem) -> Unit = {},
) {
    companion object {
        fun of(vm: TaskDetailViewModel) =
            TaskDetailCallbacks(
                retry = vm::reload,
                startEditing = vm::startEditing,
                edit = vm::edit,
                cancelEditing = vm::cancelEditing,
                newProject = vm::newProject,
                save = vm::save,
                complete = vm::complete,
                reopen = vm::reopen,
                move = vm::move,
                delete = vm::delete,
                undoDelete = vm::undoDelete,
                toggleItem = vm::toggleChecklistItem,
                renameItem = vm::renameChecklistItem,
                addItem = vm::addChecklistItem,
                deleteItem = vm::deleteChecklistItem,
                moveItem = vm::moveChecklistItem,
                dismiss = vm::dismiss,
                toReference = vm::toReference,
                openToCalendar = vm::openToCalendar,
                editPlan = vm::editPlan,
                retryCalendars = vm::retryCalendars,
                cancelToCalendar = vm::cancelToCalendar,
                confirmToCalendar = vm::confirmToCalendar,
            )
    }
}

object TaskDetailTags {
    const val SCREEN = "task-detail"
    const val LOADING = "task-detail-loading"
    const val ERROR = "task-detail-error"
    const val NOT_FOUND = "task-detail-not-found"
    const val RETRY = "task-detail-retry"
    const val TITLE = "task-detail-title"
    const val NOTES = "task-detail-notes"
    const val STATUS = "task-detail-status"
    const val EDIT = "task-detail-edit"
    const val COMPLETE = "task-detail-complete"
    const val REOPEN = "task-detail-reopen"
    const val MORE = "task-detail-more"
    const val DELETE = "task-detail-delete"
    const val TO_REFERENCE = "task-detail-to-reference"
    const val REFERENCE_CONFIRM = "task-detail-reference-confirm"
    const val TO_CALENDAR = "task-detail-to-calendar"
    const val WAITING_INPUT = "task-detail-waiting-input"
    const val WAITING_CONFIRM = "task-detail-waiting-confirm"
    const val FORM = "task-detail-form"
    const val TITLE_INPUT = "task-detail-title-input"
    const val NOTES_INPUT = "task-detail-notes-input"
    const val ESTIMATE_INPUT = "task-detail-estimate-input"
    const val DUE_DATE = "task-detail-due-date"
    const val DUE_DATE_CLEAR = "task-detail-due-date-clear"
    const val DUE_TIME = "task-detail-due-time"
    const val DUE_TIME_CLEAR = "task-detail-due-time-clear"
    const val PROJECT = "task-detail-project"
    const val NO_PROJECT = "task-detail-project-none"
    const val NEW_PROJECT = "task-detail-project-new"
    const val NEW_PROJECT_INPUT = "task-detail-project-new-input"
    const val NEW_PROJECT_CONFIRM = "task-detail-project-new-confirm"
    const val SAVE = "task-detail-save"
    const val CANCEL = "task-detail-cancel"
    const val ADD_ITEM_INPUT = "task-detail-add-item-input"
    const val ADD_ITEM = "task-detail-add-item"
    const val RENAME_INPUT = "task-detail-rename-input"
    const val RENAME_CONFIRM = "task-detail-rename-confirm"

    fun property(name: String) = "task-detail-property-$name"

    fun move(target: MoveTarget) = "task-detail-move-${target.name.lowercase()}"

    fun error(field: TaskField) = "task-detail-error-${field.name.lowercase()}"

    fun context(id: UUID) = "task-detail-context-$id"

    fun projectOption(id: UUID) = "task-detail-project-$id"

    fun energy(energy: Energy?) = "task-detail-energy-${energy?.name?.lowercase() ?: "none"}"

    fun creativity(creativity: Creativity?) = "task-detail-creativity-${creativity?.name?.lowercase() ?: "none"}"

    fun item(id: UUID) = "task-detail-item-$id"

    fun itemCheck(id: UUID) = "task-detail-item-check-$id"

    fun itemDelete(id: UUID) = "task-detail-item-delete-$id"
}

@Composable
fun TaskDetailContent(
    state: TaskDetailUiState,
    callbacks: TaskDetailCallbacks,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader? = null,
    /** The linked-items panel below the checklist (the route passes [LinkedItemsSection]). */
    linksPanel: @Composable () -> Unit = {},
    /** The reminders panel above the links, given the task and whether it can be changed now. */
    remindersPanel: @Composable (TaskDetail, Boolean) -> Unit = { _, _ -> },
) {
    val snackbarHost = remember { SnackbarHostState() }
    MessageEffect(state.message, snackbarHost, callbacks)
    Box(modifier.fillMaxSize().testTag(TaskDetailTags.SCREEN)) {
        Column(Modifier.fillMaxSize()) {
            if (state.offline) DetailOfflineBanner(state.cachedAt, callbacks.retry)
            val detail = state.detail
            when {
                state.notFound && detail == null -> Centered(stringResource(R.string.task_detail_not_found), TaskDetailTags.NOT_FOUND)
                detail != null -> {
                    val draft = state.draft
                    if (draft != null) {
                        EditForm(state, draft, callbacks)
                    } else {
                        Details(state, detail, callbacks, imageLoader, linksPanel, remindersPanel)
                    }
                }
                state.loadFailed -> LoadFailed(state.offline, callbacks.retry)
                else -> Centered(stringResource(R.string.task_detail_loading), TaskDetailTags.LOADING)
            }
        }
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter))
    }
    state.calendarDialog?.let { dialog ->
        ToCalendarDialog(
            plan = dialog.plan,
            choice = dialog.choice,
            update = callbacks.editPlan,
            onConfirm = callbacks.confirmToCalendar,
            onDismiss = callbacks.cancelToCalendar,
            onRetry = callbacks.retryCalendars,
            enabled = !state.busy && !state.offline,
        )
    }
}

/** The app's offline banner (as on the task lists): the copy shown may be outdated, and nothing can be changed. */
@Composable
private fun DetailOfflineBanner(
    cachedAt: Instant?,
    retry: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag(TaskListTags.OFFLINE)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
            val text =
                cachedAt?.let {
                    val time = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(it.atZone(ZoneId.systemDefault()))
                    stringResource(R.string.task_detail_offline_from, time)
                } ?: stringResource(R.string.task_detail_offline)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 12.dp))
            TextButton(
                onClick = retry,
                modifier = Modifier.testTag(TaskDetailTags.RETRY),
            ) { Text(stringResource(R.string.task_detail_retry)) }
        }
    }
}

@Composable
private fun LoadFailed(
    offline: Boolean,
    retry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp).testTag(TaskDetailTags.ERROR),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(if (offline) R.string.task_detail_error_offline else R.string.task_detail_error_failed),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (!offline) {
            TextButton(
                onClick = retry,
                modifier = Modifier.testTag(TaskDetailTags.RETRY),
            ) { Text(stringResource(R.string.task_detail_retry)) }
        }
    }
}

@Composable
private fun Centered(
    text: String,
    tag: String,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp).testTag(tag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Details(
    state: TaskDetailUiState,
    detail: TaskDetail,
    callbacks: TaskDetailCallbacks,
    imageLoader: ImageLoader?,
    linksPanel: @Composable () -> Unit,
    remindersPanel: @Composable (TaskDetail, Boolean) -> Unit,
) {
    val task = detail.task
    val enabled = !state.readOnly && !state.busy
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                statusText(task, detail),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(TaskDetailTags.STATUS),
            )
            Text(
                task.title,
                style = MaterialTheme.typography.headlineSmall,
                textDecoration = if (detail.completed) TextDecoration.LineThrough else null,
                modifier = Modifier.testTag(TaskDetailTags.TITLE).semantics { heading() },
            )
            Actions(state, detail, callbacks, enabled)
        }
        HorizontalDivider()
        Properties(state, detail)
        val notes = task.notesMd?.takeIf { it.isNotBlank() }
        if (notes != null) {
            SectionTitle(stringResource(R.string.task_detail_notes))
            MarkdownView(notes, imageLoader, Modifier.padding(horizontal = 16.dp).testTag(TaskDetailTags.NOTES))
        }
        Checklist(detail, callbacks, enabled)
        remindersPanel(detail, !state.readOnly)
        linksPanel()
    }
}

@Composable
private fun statusText(
    task: GtdTask,
    detail: TaskDetail,
): String =
    if (detail.completed) {
        val at = detail.completedAt?.atZone(ZoneId.systemDefault())?.toLocalDate()
        at?.let { stringResource(R.string.task_detail_status_done_on, it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))) }
            ?: stringResource(R.string.task_detail_status_done)
    } else {
        stringResource(listLabel(task.list))
    }

@Composable
private fun Actions(
    state: TaskDetailUiState,
    detail: TaskDetail,
    callbacks: TaskDetailCallbacks,
    enabled: Boolean,
) {
    var menu by remember { mutableStateOf(false) }
    var askWaiting by rememberSaveable { mutableStateOf(false) }
    var askReference by rememberSaveable { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (detail.completed) {
            FilledTonalButton(onClick = callbacks.reopen, enabled = enabled, modifier = Modifier.testTag(TaskDetailTags.REOPEN)) {
                Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.task_detail_reopen))
            }
        } else {
            Button(onClick = callbacks.complete, enabled = enabled, modifier = Modifier.testTag(TaskDetailTags.COMPLETE)) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.task_detail_complete))
            }
            OutlinedButton(onClick = callbacks.startEditing, enabled = enabled, modifier = Modifier.testTag(TaskDetailTags.EDIT)) {
                Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.task_detail_edit))
            }
        }
        Box {
            IconButton(onClick = { menu = true }, enabled = enabled, modifier = Modifier.testTag(TaskDetailTags.MORE)) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.task_detail_more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!detail.completed) {
                    MoveTarget.entries.filter { canMove(detail.task, it) }.forEach { target ->
                        DropdownMenuItem(
                            text = { Text(stringResource(moveLabel(target))) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
                            onClick = {
                                menu = false
                                if (target == MoveTarget.WAITING) askWaiting = true else callbacks.move(target, null)
                            },
                            modifier = Modifier.testTag(TaskDetailTags.move(target)),
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.task_detail_convert_reference)) },
                        leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                        onClick = {
                            menu = false
                            askReference = true
                        },
                        modifier = Modifier.testTag(TaskDetailTags.TO_REFERENCE),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.task_detail_convert_calendar)) },
                        leadingIcon = { Icon(Icons.Outlined.Event, contentDescription = null) },
                        onClick = {
                            menu = false
                            callbacks.openToCalendar()
                        },
                        modifier = Modifier.testTag(TaskDetailTags.TO_CALENDAR),
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.task_detail_delete)) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = {
                        menu = false
                        callbacks.delete()
                    },
                    modifier = Modifier.testTag(TaskDetailTags.DELETE),
                )
            }
        }
    }
    if (askWaiting && !state.readOnly) {
        WaitingDialog(
            initial = detail.task.waitingForText.orEmpty(),
            confirm = { person ->
                askWaiting = false
                callbacks.move(MoveTarget.WAITING, person)
            },
            dismiss = { askWaiting = false },
        )
    }
    if (askReference && !state.readOnly) {
        // As on the web: the task goes to the Trash and the note cannot be turned back into it here.
        AlertDialog(
            onDismissRequest = { askReference = false },
            text = { Text(stringResource(R.string.task_detail_convert_reference_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askReference = false
                        callbacks.toReference()
                    },
                    modifier = Modifier.testTag(TaskDetailTags.REFERENCE_CONFIRM),
                ) { Text(stringResource(R.string.task_detail_convert_reference)) }
            },
            dismissButton = { TextButton(onClick = { askReference = false }) { Text(stringResource(R.string.task_detail_cancel)) } },
        )
    }
}

private fun moveLabel(target: MoveTarget) =
    when (target) {
        MoveTarget.NEXT -> R.string.task_detail_move_next
        MoveTarget.SOMEDAY -> R.string.task_detail_move_someday
        MoveTarget.WAITING -> R.string.task_detail_move_waiting
    }

@Composable
private fun WaitingDialog(
    initial: String,
    confirm: (String) -> Unit,
    dismiss: () -> Unit,
) {
    var person by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.task_detail_waiting_title)) },
        text = {
            OutlinedTextField(
                value = person,
                onValueChange = { person = it },
                label = { Text(stringResource(R.string.task_detail_waiting_person)) },
                singleLine = true,
                modifier = Modifier.testTag(TaskDetailTags.WAITING_INPUT),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { confirm(person.trim()) },
                enabled = person.isNotBlank(),
                modifier = Modifier.testTag(TaskDetailTags.WAITING_CONFIRM),
            ) { Text(stringResource(R.string.task_detail_move_waiting)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.task_detail_cancel)) } },
    )
}

@Composable
private fun Properties(
    state: TaskDetailUiState,
    detail: TaskDetail,
) {
    val task = detail.task
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val times = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    val none = stringResource(R.string.task_detail_none)
    val project =
        task.projectId?.let { id ->
            state.projects.firstOrNull { it.id == id }?.title
                ?: stringResource(R.string.task_detail_project_unknown)
        }
    val contexts = task.contextIds.mapNotNull { id -> state.contexts.firstOrNull { it.id == id }?.name }
    val due =
        task.dueDate?.let { date ->
            val time = task.dueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            listOfNotNull(date.format(dates), time?.format(times)).joinToString(" ")
        }
    Column(Modifier.padding(vertical = 8.dp)) {
        Property("project", stringResource(R.string.task_detail_project), project ?: none)
        Property("contexts", stringResource(R.string.task_detail_contexts), contexts.joinToString(" ").ifEmpty { none })
        Property("due", stringResource(R.string.task_detail_due), due ?: none)
        task.scheduledDate?.let { Property("scheduled", stringResource(R.string.task_detail_scheduled), it.format(dates)) }
        detail.ticklerDate?.let { Property("tickler", stringResource(R.string.task_detail_tickler), it.format(dates)) }
        Property(
            "estimate",
            stringResource(R.string.task_detail_estimate),
            task.timeEstimateMin?.let { stringResource(R.string.task_detail_minutes, it) } ?: none,
        )
        Property(
            "energy",
            stringResource(R.string.task_detail_energy),
            task.energy?.let { userEnergyLabel(it.name, energyLabel(it)) } ?: none,
        )
        Property(
            "creativity",
            stringResource(R.string.task_detail_creativity),
            task.creativity?.let { userCreativityLabel(it.name, creativityLabel(it)) } ?: none,
        )
        if (task.list == TaskList.WAITING) {
            val since = task.waitingSince?.let { stringResource(R.string.task_detail_waiting_since, it.format(dates)) }
            Property(
                "waiting",
                stringResource(R.string.task_detail_waiting_for),
                listOfNotNull(task.waitingForText?.takeIf { it.isNotBlank() } ?: none, since).joinToString(" · "),
            )
        }
        detail.recurrenceRule?.takeIf { it.isNotBlank() }?.let {
            Property("repeat", stringResource(R.string.task_detail_repeat), repeatText(it, detail.recurrenceMode))
        }
    }
}

@Composable
private fun Property(
    name: String,
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag(TaskDetailTags.property(name))) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun Checklist(
    detail: TaskDetail,
    callbacks: TaskDetailCallbacks,
    enabled: Boolean,
) {
    var renaming by remember { mutableStateOf<ChecklistItem?>(null) }
    var newItem by rememberSaveable { mutableStateOf("") }
    SectionTitle(stringResource(R.string.task_detail_checklist))
    var dragging by remember { mutableStateOf<UUID?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val moveUp = stringResource(R.string.task_detail_item_move_up)
    val moveDown = stringResource(R.string.task_detail_item_move_down)
    val last = detail.checklist.lastIndex
    detail.checklist.forEachIndexed { index, item ->
        var rowHeight by remember(item.id) { mutableIntStateOf(1) }
        val lifted = dragging == item.id
        Row(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { rowHeight = it.height.coerceAtLeast(1) }
                .zIndex(if (lifted) 1f else 0f)
                .graphicsLayer { translationY = if (lifted) dragOffset else 0f }
                .background(if (lifted) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                .pointerInput(item.id, index, last, enabled) {
                    if (!enabled) return@pointerInput
                    // Long-press, then drag: the item lands where it is let go, by whole rows.
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            dragging = item.id
                            dragOffset = 0f
                        },
                        onDragEnd = {
                            val steps = (dragOffset / rowHeight).roundToInt()
                            dragging = null
                            dragOffset = 0f
                            if (steps != 0) callbacks.moveItem(item, (index + steps).coerceIn(0, last))
                        },
                        onDragCancel = {
                            dragging = null
                            dragOffset = 0f
                        },
                    ) { change, amount ->
                        change.consume()
                        dragOffset += amount.y
                    }
                }.semantics {
                    if (enabled) {
                        customActions =
                            listOfNotNull(
                                CustomAccessibilityAction(
                                    moveUp,
                                ) { true.also { callbacks.moveItem(item, index - 1) } }.takeIf { index > 0 },
                                CustomAccessibilityAction(moveDown) { true.also { callbacks.moveItem(item, index + 1) } }.takeIf {
                                    index <
                                        last
                                },
                            )
                    }
                }.padding(end = 4.dp)
                .testTag(TaskDetailTags.item(item.id)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (enabled && last > 0) {
                Icon(
                    Icons.Outlined.DragIndicator,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).size(20.dp),
                )
            }
            val toggleLabel = stringResource(R.string.task_detail_item_toggle, item.text)
            Checkbox(
                checked = item.done,
                onCheckedChange = { callbacks.toggleItem(item) },
                enabled = enabled,
                modifier = Modifier.testTag(TaskDetailTags.itemCheck(item.id)).semantics { contentDescription = toggleLabel },
            )
            Text(
                item.text,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable(enabled = enabled, onClickLabel = stringResource(R.string.task_detail_item_rename)) { renaming = item }
                        .padding(vertical = 8.dp),
            )
            IconButton(
                onClick = { callbacks.deleteItem(item) },
                enabled = enabled,
                modifier = Modifier.testTag(TaskDetailTags.itemDelete(item.id)),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.task_detail_item_delete, item.text))
            }
        }
    }
    if (detail.checklist.isEmpty() && !enabled) {
        Text(
            stringResource(R.string.task_detail_checklist_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    if (enabled) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newItem,
                onValueChange = { newItem = it },
                placeholder = { Text(stringResource(R.string.task_detail_item_new)) },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag(TaskDetailTags.ADD_ITEM_INPUT),
            )
            TextButton(
                onClick = {
                    callbacks.addItem(newItem)
                    newItem = ""
                },
                enabled = newItem.isNotBlank(),
                modifier = Modifier.testTag(TaskDetailTags.ADD_ITEM),
            ) { Text(stringResource(R.string.task_detail_item_add)) }
        }
    }
    renaming?.let { item ->
        RenameDialog(
            item = item,
            confirm = { text ->
                renaming = null
                callbacks.renameItem(item, text)
            },
            dismiss = { renaming = null },
        )
    }
}

@Composable
private fun RenameDialog(
    item: ChecklistItem,
    confirm: (String) -> Unit,
    dismiss: () -> Unit,
) {
    var text by rememberSaveable(item.id) { mutableStateOf(item.text) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.task_detail_item_rename)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.testTag(TaskDetailTags.RENAME_INPUT),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { confirm(text) },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag(TaskDetailTags.RENAME_CONFIRM),
            ) {
                Text(stringResource(R.string.task_detail_save))
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.task_detail_cancel)) } },
    )
}

@Composable
private fun EditForm(
    state: TaskDetailUiState,
    draft: TaskDraft,
    callbacks: TaskDetailCallbacks,
) {
    val enabled = !state.busy
    Column(Modifier.fillMaxSize().testTag(TaskDetailTags.FORM)) {
        // Cancel and Save stay in reach while the long form scrolls below them.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.task_detail_edit),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(onClick = callbacks.cancelEditing, enabled = enabled, modifier = Modifier.testTag(TaskDetailTags.CANCEL)) {
                Text(stringResource(R.string.task_detail_cancel))
            }
            Button(onClick = callbacks.save, enabled = enabled, modifier = Modifier.testTag(TaskDetailTags.SAVE)) {
                Text(stringResource(R.string.task_detail_save))
            }
        }
        HorizontalDivider()
        EditFields(state, draft, callbacks, enabled)
    }
}

@Composable
private fun EditFields(
    state: TaskDetailUiState,
    draft: TaskDraft,
    callbacks: TaskDetailCallbacks,
    enabled: Boolean,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = draft.title,
            onValueChange = { value -> callbacks.edit { it.copy(title = value) } },
            label = { Text(stringResource(R.string.task_detail_title_label)) },
            isError = TaskField.TITLE in state.problems,
            supportingText = state.problems[TaskField.TITLE]?.let { { ProblemText(TaskField.TITLE, it) } },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(TaskDetailTags.TITLE_INPUT),
        )
        OutlinedTextField(
            value = draft.notes,
            onValueChange = { value -> callbacks.edit { it.copy(notes = value) } },
            label = { Text(stringResource(R.string.task_detail_notes)) },
            isError = TaskField.NOTES in state.problems,
            supportingText =
                state.problems[TaskField.NOTES]?.let { { ProblemText(TaskField.NOTES, it) } } ?: {
                    Text(stringResource(R.string.task_detail_notes_hint))
                },
            enabled = enabled,
            minLines = 3,
            modifier = Modifier.fillMaxWidth().testTag(TaskDetailTags.NOTES_INPUT),
        )
        ListFields(state, draft, callbacks, enabled)
        ProjectPicker(state, draft, callbacks, enabled)
        FormGroup(stringResource(R.string.task_detail_contexts), state.problems[TaskField.CONTEXTS]?.let { TaskField.CONTEXTS to it }) {
            if (state.contexts.isEmpty()) {
                Text(stringResource(R.string.task_detail_contexts_none), style = MaterialTheme.typography.bodyMedium)
            }
            state.contexts.forEach { context ->
                FilterChip(
                    selected = context.id in draft.contextIds,
                    onClick = {
                        callbacks.edit { d ->
                            d.copy(
                                contextIds =
                                    if (context.id in
                                        d.contextIds
                                    ) {
                                        d.contextIds - context.id
                                    } else {
                                        d.contextIds + context.id
                                    },
                            )
                        }
                    },
                    label = { Text(context.name) },
                    enabled = enabled,
                    modifier = Modifier.testTag(TaskDetailTags.context(context.id)),
                )
            }
        }
        DueFields(state, draft, callbacks, enabled)
        OutlinedTextField(
            value = draft.estimate,
            onValueChange = { value -> callbacks.edit { it.copy(estimate = value.filter(Char::isDigit).take(ESTIMATE_DIGITS)) } },
            label = { Text(stringResource(R.string.task_detail_estimate_label)) },
            isError = TaskField.ESTIMATE in state.problems,
            supportingText = state.problems[TaskField.ESTIMATE]?.let { { ProblemText(TaskField.ESTIMATE, it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(TaskDetailTags.ESTIMATE_INPUT),
        )
        FormGroup(stringResource(R.string.task_detail_energy), state.problems[TaskField.ENERGY]?.let { TaskField.ENERGY to it }) {
            (listOf(null) + Energy.entries).forEach { energy ->
                FilterChip(
                    selected = draft.energy == energy,
                    onClick = { callbacks.edit { it.copy(energy = energy) } },
                    label = {
                        Text(
                            energy?.let { userEnergyLabel(it.name, energyLabel(it)) } ?: stringResource(R.string.task_detail_none),
                        )
                    },
                    enabled = enabled,
                    modifier = Modifier.testTag(TaskDetailTags.energy(energy)),
                )
            }
        }
        FormGroup(
            stringResource(R.string.task_detail_creativity),
            state.problems[TaskField.CREATIVITY]?.let { TaskField.CREATIVITY to it },
        ) {
            (listOf(null) + Creativity.entries).forEach { creativity ->
                FilterChip(
                    selected = draft.creativity == creativity,
                    onClick = { callbacks.edit { it.copy(creativity = creativity) } },
                    label = {
                        Text(
                            creativity?.let { userCreativityLabel(it.name, creativityLabel(it)) }
                                ?: stringResource(R.string.task_detail_none),
                        )
                    },
                    enabled = enabled,
                    modifier = Modifier.testTag(TaskDetailTags.creativity(creativity)),
                )
            }
        }
        RepeatFields(state, draft, callbacks, enabled)
    }
}

@Composable
private fun ProjectPicker(
    state: TaskDetailUiState,
    draft: TaskDraft,
    callbacks: TaskDetailCallbacks,
    enabled: Boolean,
) {
    var open by remember { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    val current =
        draft.projectId?.let { id ->
            state.projects.firstOrNull { it.id == id }?.title
                ?: stringResource(R.string.task_detail_project_unknown)
        }
    FormGroup(stringResource(R.string.task_detail_project), state.problems[TaskField.PROJECT]?.let { TaskField.PROJECT to it }) {
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag(TaskDetailTags.PROJECT)) {
                Text(current ?: stringResource(R.string.task_detail_project_none))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.task_detail_project_none)) },
                    onClick = {
                        open = false
                        callbacks.edit { it.copy(projectId = null) }
                    },
                    modifier = Modifier.testTag(TaskDetailTags.NO_PROJECT),
                )
                state.projects.forEach { project ->
                    DropdownMenuItem(
                        text = { Text(project.title) },
                        onClick = {
                            open = false
                            callbacks.edit { it.copy(projectId = project.id) }
                        },
                        modifier = Modifier.testTag(TaskDetailTags.projectOption(project.id)),
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.task_detail_project_new)) },
                    onClick = {
                        open = false
                        naming = true
                    },
                    modifier = Modifier.testTag(TaskDetailTags.NEW_PROJECT),
                )
            }
        }
    }
    if (naming) {
        NewProjectDialog(
            confirm = { title ->
                naming = false
                callbacks.newProject(title)
            },
            dismiss = { naming = false },
        )
    }
}

/** Asks for the title of a new project; nothing else (it is created Active, without area). */
@Composable
private fun NewProjectDialog(
    confirm: (String) -> Unit,
    dismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.task_detail_project_new_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.task_detail_project_new_label)) },
                singleLine = true,
                modifier = Modifier.testTag(TaskDetailTags.NEW_PROJECT_INPUT),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { confirm(title.trim()) },
                enabled = title.isNotBlank(),
                modifier = Modifier.testTag(TaskDetailTags.NEW_PROJECT_CONFIRM),
            ) { Text(stringResource(R.string.task_detail_project_new_create)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.task_detail_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueFields(
    state: TaskDetailUiState,
    draft: TaskDraft,
    callbacks: TaskDetailCallbacks,
    enabled: Boolean,
) {
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    val problem = (
        state.problems[TaskField.DUE_DATE]?.let { TaskField.DUE_DATE to it }
            ?: state.problems[TaskField.DUE_TIME]?.let { TaskField.DUE_TIME to it }
    )
    FormGroup(stringResource(R.string.task_detail_due), problem) {
        OutlinedButton(onClick = { pickingDate = true }, enabled = enabled, modifier = Modifier.testTag(TaskDetailTags.DUE_DATE)) {
            Text(
                draft.dueDate?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                    ?: stringResource(R.string.task_detail_due_pick),
            )
        }
        if (draft.dueDate != null) {
            OutlinedButton(onClick = { pickingTime = true }, enabled = enabled, modifier = Modifier.testTag(TaskDetailTags.DUE_TIME)) {
                Text(
                    draft.dueTime?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
                        ?: stringResource(R.string.task_detail_due_time_pick),
                )
            }
            if (draft.dueTime != null) {
                TextButton(onClick = {
                    callbacks.edit { it.copy(dueTime = null) }
                }, enabled = enabled, modifier = Modifier.testTag(TaskDetailTags.DUE_TIME_CLEAR)) {
                    Text(stringResource(R.string.task_detail_due_time_clear))
                }
            }
            TextButton(
                onClick = { callbacks.edit { it.copy(dueDate = null, dueTime = null) } },
                enabled = enabled,
                modifier = Modifier.testTag(TaskDetailTags.DUE_DATE_CLEAR),
            ) { Text(stringResource(R.string.task_detail_due_clear)) }
        }
    }
    if (pickingDate) {
        // The picker works in UTC midnights.
        val initial = (draft.dueDate ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        callbacks.edit { it.copy(dueDate = date) }
                    }
                    pickingDate = false
                }) { Text(stringResource(R.string.task_detail_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.task_detail_cancel)) } },
        ) { DatePicker(pickerState) }
    }
    if (pickingTime) {
        val time = draft.dueTime ?: LocalTime.of(DEFAULT_HOUR, 0)
        val pickerState = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(onClick = {
                    callbacks.edit { it.copy(dueTime = LocalTime.of(pickerState.hour, pickerState.minute)) }
                    pickingTime = false
                }) { Text(stringResource(R.string.task_detail_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text(stringResource(R.string.task_detail_cancel)) } },
            text = { TimePicker(pickerState) },
        )
    }
}

@Composable
internal fun FormGroup(
    title: String,
    problem: Pair<TaskField, FieldProblem>?,
    content: @Composable () -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) { content() }
        problem?.let { (field, it) -> ProblemText(field, it) }
    }
}

@Composable
internal fun ProblemText(
    field: TaskField,
    problem: FieldProblem,
) {
    Text(
        when (problem) {
            FieldProblem.Required -> stringResource(requiredMessage(field))
            FieldProblem.TooLong -> pluralStringResource(R.plurals.task_detail_error_too_long, TaskDraft.MAX_TITLE, TaskDraft.MAX_TITLE)
            FieldProblem.NotANumber -> stringResource(R.string.task_detail_error_estimate, TaskDraft.MAX_ESTIMATE)
            FieldProblem.NotFuture -> stringResource(R.string.task_detail_error_future)
            FieldProblem.Invalid -> stringResource(R.string.task_detail_error_invalid)
            is FieldProblem.Server -> problem.message.ifBlank { stringResource(R.string.task_detail_error_invalid) }
        },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag(TaskDetailTags.error(field)),
    )
}

/** The "this field is required" message for [field]: each required field names what is missing. */
internal fun requiredMessage(field: TaskField): Int =
    when (field) {
        TaskField.SCHEDULED -> R.string.task_detail_error_scheduled_required
        TaskField.WAITING_FOR -> R.string.task_detail_error_waiting_for_required
        TaskField.RECURRENCE -> R.string.task_detail_error_repeat_rule_required
        else -> R.string.task_detail_error_required
    }

/** Shows [message] on [host]; after a delete it offers Undo and closes the screen once the offer is gone. */
@Composable
private fun MessageEffect(
    message: DetailMessage?,
    host: SnackbarHostState,
    callbacks: TaskDetailCallbacks,
) {
    val resources = LocalResources.current
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        val text =
            when (message) {
                DetailMessage.Offline -> resources.getString(R.string.task_detail_message_offline)
                DetailMessage.Conflict -> resources.getString(R.string.task_detail_message_conflict)
                DetailMessage.NotFound -> resources.getString(R.string.task_detail_message_not_found)
                is DetailMessage.Failed ->
                    message.detail?.takeIf { it.isNotBlank() }?.let { resources.getString(R.string.task_detail_message_invalid, it) }
                        ?: resources.getString(R.string.task_detail_message_failed)
                DetailMessage.Saved -> resources.getString(R.string.task_detail_message_saved)
                is DetailMessage.ProjectNotCreated ->
                    message.detail?.takeIf { it.isNotBlank() }?.let {
                        resources.getString(
                            R.string.task_detail_message_project_invalid,
                            it,
                        )
                    }
                        ?: resources.getString(R.string.task_detail_message_project_failed)
                DetailMessage.Completed -> resources.getString(R.string.task_detail_message_completed)
                DetailMessage.Reopened -> resources.getString(R.string.task_detail_message_reopened)
                is DetailMessage.Moved ->
                    resources.getString(
                        R.string.task_detail_message_moved,
                        resources.getString(moveDestination(message.target)),
                    )
                DetailMessage.Deleted -> resources.getString(R.string.task_detail_message_deleted)
                DetailMessage.Restored -> resources.getString(R.string.task_detail_message_restored)
                is DetailMessage.Converted -> convertedText(resources, message.item)
            }
        if (message is DetailMessage.Converted) {
            val result =
                host.showSnackbar(
                    text,
                    actionLabel = resources.getString(R.string.task_detail_convert_open),
                    duration = SnackbarDuration.Long,
                )
            callbacks.dismiss(message)
            if (result == SnackbarResult.ActionPerformed) callbacks.openConverted(message.item) else callbacks.close()
        } else if (message == DetailMessage.Deleted) {
            val result =
                host.showSnackbar(
                    text,
                    actionLabel = resources.getString(R.string.task_detail_undo),
                    duration = SnackbarDuration.Long,
                )
            callbacks.dismiss(message)
            if (result == SnackbarResult.ActionPerformed) callbacks.undoDelete() else callbacks.close()
        } else {
            host.showSnackbar(text, duration = SnackbarDuration.Short)
            callbacks.dismiss(message)
        }
    }
}

/** Where a converted task went, for the snackbar: the note, or the event with its day. */
fun convertedText(
    resources: Resources,
    item: ConvertedItem,
): String =
    when (item.type) {
        ConvertedType.NOTE -> resources.getString(R.string.task_detail_convert_moved_note, item.title)
        ConvertedType.EVENT ->
            item.day?.let {
                resources.getString(
                    R.string.task_detail_convert_moved_event_on,
                    item.title,
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(it),
                )
            } ?: resources.getString(R.string.task_detail_convert_moved_event, item.title)
    }

private fun moveDestination(target: MoveTarget) =
    when (target) {
        MoveTarget.NEXT -> R.string.task_detail_list_next
        MoveTarget.SOMEDAY -> R.string.task_detail_list_someday
        MoveTarget.WAITING -> R.string.task_detail_list_waiting
    }

internal fun energyLabel(energy: Energy) =
    when (energy) {
        Energy.LOW -> R.string.next_energy_low
        Energy.MEDIUM -> R.string.next_energy_medium
        Energy.HIGH -> R.string.next_energy_high
    }

internal fun creativityLabel(creativity: Creativity) =
    when (creativity) {
        Creativity.ROUTINE -> R.string.next_creativity_routine
        Creativity.MIXED -> R.string.next_creativity_mixed
        Creativity.CREATIVE -> R.string.next_creativity_creative
    }

private const val ESTIMATE_DIGITS = 5
private const val DEFAULT_HOUR = 9
