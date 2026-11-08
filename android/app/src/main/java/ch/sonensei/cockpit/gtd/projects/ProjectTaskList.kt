package ch.sonensei.cockpit.gtd.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.TaskList
import ch.sonensei.cockpit.gtd.lists.LiveListTags
import ch.sonensei.cockpit.gtd.lists.LiveTaskRow
import ch.sonensei.cockpit.ui.LocalFabClearance
import java.util.UUID

object ProjectTaskTags {
    const val ADD = "project-add-task"
    const val ADD_BUTTON = "project-add-task-button"
    const val ADD_MAKE_NEXT = "project-add-task-make-next"

    /** The reorderable item of a task (row and divider): long-press drag and the move actions are on it. */
    fun item(id: UUID) = "project-task-$id"
}

/**
 * "Add task" (quick entry): adds a next action to the project and stays open for the next one; the field clears once
 * the task exists. "Make it the next step" is offered while the project has one (a project without one gets the new
 * task as its next step anyway).
 */
@Composable
internal fun AddTaskField(
    state: ProjectsUiState,
    detail: ProjectDetailState,
    callbacks: ProjectsCallbacks,
) {
    val projectId = detail.project.id
    var title by rememberSaveable(projectId) { mutableStateOf("") }
    var makeNext by rememberSaveable(projectId) { mutableStateOf(false) }
    val added = detail.added
    LaunchedEffect(added) {
        // Only the text that was added: what the user typed meanwhile stays.
        if (added != null && title.trim() == added.title) {
            title = ""
            makeNext = false
        }
    }
    val enabled = !state.readOnly
    val canAdd = enabled && title.isNotBlank() && projectId !in state.busy
    val offerNext = !detail.project.stalled
    val submit = { if (canAdd) callbacks.addTask(title, makeNext && offerNext) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.projects_add_task)) },
                placeholder = { Text(stringResource(R.string.projects_add_task_placeholder)) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                // Not the default "Done": the keyboard stays open for the next task.
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f).testTag(ProjectTaskTags.ADD),
            )
            IconButton(onClick = submit, enabled = canAdd, modifier = Modifier.testTag(ProjectTaskTags.ADD_BUTTON)) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.projects_add_task_button))
            }
        }
        if (offerNext) {
            Row(
                Modifier
                    .toggleable(value = makeNext, enabled = enabled, role = Role.Checkbox, onValueChange = { makeNext = it })
                    .padding(end = 16.dp)
                    .testTag(ProjectTaskTags.ADD_MAKE_NEXT),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = makeNext, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp))
                Text(stringResource(R.string.projects_add_task_make_next), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * The project's open tasks in its order. A long press on a row picks it up and dragging moves it; dropping saves the
 * new order. Screen readers get "Move up"/"Move down" actions instead. While read-only the order cannot change.
 */
@Composable
internal fun ProjectTaskList(
    state: ProjectsUiState,
    detail: ProjectDetailState,
    callbacks: ProjectsCallbacks,
) {
    val ids = detail.tasks.map { it.id }
    val byId = detail.tasks.associateBy { it.id }
    // The order while dragging; it starts again from the state's order whenever that changes (the drop saved it).
    var order by remember(ids) { mutableStateOf(ids) }
    val listState = rememberLazyListState()
    val drag = remember { DragState() }
    val canReorder = !state.readOnly && ids.size > 1
    val currentOrder by rememberUpdatedState(order)
    val currentIds by rememberUpdatedState(ids)
    val reorder by rememberUpdatedState(callbacks.reorder)
    val moveUp = stringResource(R.string.projects_task_move_up)
    val moveDown = stringResource(R.string.projects_task_move_down)
    LazyColumn(
        Modifier.fillMaxSize().testTag(LiveListTags.LIST),
        state = listState,
        contentPadding = PaddingValues(bottom = LocalFabClearance.current),
    ) {
        items(order.mapNotNull(byId::get), key = { it.id }) { task ->
            val dragged = drag.taskId == task.id
            val index = order.indexOf(task.id)
            Column(
                Modifier
                    .then(
                        if (dragged) {
                            Modifier
                                .zIndex(1f)
                                .graphicsLayer { translationY = drag.translation(listState, task.id) }
                                .shadow(8.dp)
                        } else {
                            Modifier.animateItem()
                        },
                    ).background(MaterialTheme.colorScheme.surface)
                    .testTag(ProjectTaskTags.item(task.id))
                    .then(
                        if (canReorder) {
                            Modifier.longPressDrag(
                                keys = arrayOf(task.id, ids),
                                onStart = { drag.start(listState, task.id) },
                                onDrag = { dy -> drag.by(dy, listState, currentOrder)?.let { order = it } },
                                onEnd = {
                                    drag.stop()
                                    if (currentOrder != currentIds) reorder(currentOrder)
                                },
                            )
                        } else {
                            Modifier
                        },
                    ).semantics {
                        if (canReorder) {
                            customActions =
                                listOfNotNull(
                                    CustomAccessibilityAction(moveUp) {
                                        callbacks.move(task, -1)
                                        true
                                    }.takeIf { index > 0 },
                                    CustomAccessibilityAction(moveDown) {
                                        callbacks.move(task, 1)
                                        true
                                    }.takeIf { index < order.lastIndex },
                                )
                        }
                    },
            ) {
                ProjectTaskRow(state, detail, task, callbacks)
                HorizontalDivider()
            }
        }
    }
}

/** The row being dragged: where it was laid out when picked up and how far the finger moved since. */
private class DragState {
    var taskId by mutableStateOf<UUID?>(null)
        private set
    private var startOffset by mutableFloatStateOf(0f)
    private var moved by mutableFloatStateOf(0f)

    fun start(
        list: LazyListState,
        id: UUID,
    ) {
        startOffset = list.offsetOf(id)?.toFloat() ?: 0f
        moved = 0f
        taskId = id
    }

    fun stop() {
        taskId = null
        moved = 0f
    }

    /** How far to draw the row from where it is laid out now, so it stays under the finger. */
    fun translation(
        list: LazyListState,
        id: UUID,
    ): Float = startOffset + moved - (list.offsetOf(id)?.toFloat() ?: (startOffset + moved))

    /**
     * Moves the finger by [dy]; returns the new order when the row's middle is now over another row, which it then
     * takes the place of.
     */
    fun by(
        dy: Float,
        list: LazyListState,
        order: List<UUID>,
    ): List<UUID>? {
        moved += dy
        val id = taskId ?: return null
        val items = list.layoutInfo.visibleItemsInfo
        val current = items.firstOrNull { it.key == id } ?: return null
        val middle = (startOffset + moved + current.size / 2f).toInt()
        val target = items.firstOrNull { it.key != id && middle in it.offset until it.offset + it.size } ?: return null
        val from = order.indexOf(id)
        val to = order.indexOf(target.key)
        if (from < 0 || to < 0) return null
        // A lazy list keeps its first visible item in place: moving that one would scroll the list along with it.
        if (from == list.firstVisibleItemIndex || to == list.firstVisibleItemIndex) {
            list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        }
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }

    private fun LazyListState.offsetOf(id: UUID): Int? = layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset
}

/**
 * A long press picks the element up, then every move until the finger lifts goes to [onDrag]. Once picked up the
 * gesture takes all events first, so the row underneath neither opens nor toggles on release.
 */
private fun Modifier.longPressDrag(
    keys: Array<Any>,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
): Modifier =
    pointerInput(*keys) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pressed = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            onStart()
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == pressed.id } ?: break
                    if (change.changedToUpIgnoreConsumed()) {
                        change.consume()
                        break
                    }
                    onDrag(change.positionChange().y)
                    change.consume()
                }
            } finally {
                onEnd()
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
