package ch.sonensei.cockpit.gtd.projects

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.unit.IntOffset
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

    /**
     * The reorderable item of a task (row and divider): the long-press drag is on it. The move actions are on the row
     * inside it (`TaskListTags.row`), the node a screen reader focuses.
     */
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
    // The dropped order until the state's order changes (to it, once the view model has it).
    var dropped by remember(ids) { mutableStateOf(ids) }
    val listState = rememberLazyListState()
    val drag = remember { DragState() }
    val order = drag.order ?: dropped
    val canReorder = !state.readOnly && ids.size > 1
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
            // The same modifiers whether dragged or not: changing the chain when the row is picked up would restart the
            // gesture underneath and drop it.
            Column(
                Modifier
                    // The dragged row follows the finger, not the list's animation.
                    .animateItem(placementSpec = if (dragged) null else ItemPlacement)
                    .zIndex(if (dragged) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (dragged) drag.translation(listState, task.id) else 0f
                        shadowElevation = if (dragged) DRAGGED_ELEVATION.toPx() else 0f
                    }.background(MaterialTheme.colorScheme.surface)
                    .testTag(ProjectTaskTags.item(task.id))
                    .then(
                        if (canReorder) {
                            Modifier.longPressDrag(
                                keys = arrayOf(task.id, ids),
                                // Restarted (keys) when the tasks change, so these see the current ones.
                                onStart = { drag.start(listState, task.id, dropped) },
                                onDrag = { dy -> drag.by(dy, listState) },
                                onDrop = {
                                    val result = drag.stop() ?: return@longPressDrag
                                    dropped = result
                                    if (result != ids) reorder(result)
                                },
                                // Cancelled (the tasks changed, the list went read-only, the system took the gesture):
                                // nothing is saved and the list keeps showing the state's order.
                                onCancel = { drag.stop() },
                            )
                        } else {
                            Modifier
                        },
                    ),
            ) {
                val actions =
                    if (canReorder) {
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
                    } else {
                        emptyList()
                    }
                ProjectTaskRow(state, detail, task, callbacks, actions)
                HorizontalDivider()
            }
        }
    }
}

private val DRAGGED_ELEVATION = 8.dp

/** How the other rows move out of the way (the lazy list's default). */
private val ItemPlacement = spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)

/**
 * The row being dragged: where it was laid out when picked up, how far the finger moved since, and the order it has
 * made so far. Held here, not in composition state, so a release right after a move (before the next frame) sees it.
 */
private class DragState {
    var taskId by mutableStateOf<UUID?>(null)
        private set

    /** The order while dragging, `null` otherwise. */
    var order by mutableStateOf<List<UUID>?>(null)
        private set
    private var startOffset by mutableFloatStateOf(0f)
    private var moved by mutableFloatStateOf(0f)

    fun start(
        list: LazyListState,
        id: UUID,
        current: List<UUID>,
    ) {
        startOffset = list.offsetOf(id)?.toFloat() ?: 0f
        moved = 0f
        order = current
        taskId = id
    }

    /** Ends the drag; returns the order it made. */
    fun stop(): List<UUID>? {
        val result = order
        taskId = null
        order = null
        moved = 0f
        return result
    }

    /** How far to draw the row from where it is laid out now, so it stays under the finger. */
    fun translation(
        list: LazyListState,
        id: UUID,
    ): Float = startOffset + moved - (list.offsetOf(id)?.toFloat() ?: (startOffset + moved))

    /** Moves the finger by [dy]; when the row's middle is now over another row, the row takes that one's place. */
    fun by(
        dy: Float,
        list: LazyListState,
    ) {
        moved += dy
        val id = taskId ?: return
        val current = order ?: return
        val items = list.layoutInfo.visibleItemsInfo
        val dragged = items.firstOrNull { it.key == id } ?: return
        val from = current.indexOf(id)
        // The list has not been laid out in the last order yet: its positions are stale, wait for the next frame.
        if (dragged.index != from) return
        val middle = (startOffset + moved + dragged.size / 2f).toInt()
        val target = items.firstOrNull { it.key != id && middle in it.offset until it.offset + it.size } ?: return
        val to = current.indexOf(target.key)
        if (from < 0 || to < 0) return
        // A lazy list keeps its first visible item in place: moving that one would scroll the list along with it.
        if (from == list.firstVisibleItemIndex || to == list.firstVisibleItemIndex) {
            list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        }
        order = current.toMutableList().apply { add(to, removeAt(from)) }
    }

    private fun LazyListState.offsetOf(id: UUID): Int? = layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset
}

/**
 * A long press picks the element up, then every move until the finger lifts goes to [onDrag], and lifting it to
 * [onDrop]. A gesture that ends otherwise (restarted by [keys], the modifier removed, the pointer gone, or cancelled by
 * the system) goes to [onCancel] instead. Once picked up the gesture takes all events first, so the row underneath
 * neither opens nor toggles on release.
 */
private fun Modifier.longPressDrag(
    keys: Array<Any>,
    onStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
): Modifier =
    pointerInput(*keys) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pressed = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            onStart()
            var lifted = false
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == pressed.id } ?: break
                    if (change.changedToUpIgnoreConsumed()) {
                        // A gesture the system cancelled arrives as an up that is already consumed: not a drop.
                        lifted = !change.isConsumed
                        change.consume()
                        break
                    }
                    onDrag(change.positionChange().y)
                    change.consume()
                }
            } finally {
                if (lifted) onDrop() else onCancel()
            }
        }
    }

@Composable
private fun ProjectTaskRow(
    state: ProjectsUiState,
    detail: ProjectDetailState,
    task: GtdTask,
    callbacks: ProjectsCallbacks,
    actions: List<CustomAccessibilityAction>,
) {
    val isNext = task.id == detail.project.nextTaskId
    val busy = task.id in state.busy
    LiveTaskRow(
        task = task,
        readOnly = state.readOnly,
        busy = busy,
        onComplete = callbacks.complete,
        // On the row's clickable node, which a screen reader focuses, so its actions menu offers them.
        modifier = Modifier.semantics { if (actions.isNotEmpty()) customActions = actions },
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
