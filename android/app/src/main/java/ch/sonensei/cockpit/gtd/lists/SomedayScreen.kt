package ch.sonensei.cockpit.gtd.lists

import androidx.activity.compose.LocalActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder

/** The Someday tab; outside a Hilt activity (navigation tests, previews) it shows the placeholder. */
@Composable
fun SomedayScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        SomedayRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_someday, Icons.Outlined.Lightbulb, modifier)
    }
}

@Composable
fun SomedayRoute(
    modifier: Modifier = Modifier,
    viewModel: SomedayViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SomedayContent(state, LiveListCallbacks.of(viewModel), viewModel::activate, modifier)
}

/** Someday/Maybe: ideas parked for later; "Activate" makes one a next action. */
@Composable
fun SomedayContent(
    state: TaskListUiState,
    callbacks: LiveListCallbacks,
    activate: (GtdTask) -> Unit,
    modifier: Modifier = Modifier,
) {
    LiveTaskListContent(state, callbacks, stringResource(R.string.someday_empty), modifier) { task ->
        val busy = task.id in state.busy
        LiveTaskRow(
            task = task,
            readOnly = state.readOnly,
            busy = busy,
            onComplete = callbacks.complete,
            action = {
                RowAction(
                    task = task,
                    label = stringResource(R.string.someday_activate),
                    description = stringResource(R.string.someday_activate_label, task.title),
                    enabled = !state.readOnly && !busy,
                    onClick = { activate(task) },
                )
            },
        )
    }
}
