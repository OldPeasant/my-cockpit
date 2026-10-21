package ch.sonensei.cockpit.calendar.edit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.calendar.agenda.CalendarRoute
import ch.sonensei.cockpit.calendar.agenda.CalendarView
import ch.sonensei.cockpit.calendar.agenda.CalendarViewModel

/**
 * The calendar views with the event editor: the add button opens a new event (on the day the day view shows, else
 * today), a tapped occurrence opens its event. The views reload when they come back, so a saved change shows.
 */
@Composable
fun CalendarWithEditor(
    modifier: Modifier = Modifier,
    calendarViewModel: CalendarViewModel = hiltViewModel(),
    editor: EventEditorViewModel = hiltViewModel(),
) {
    val editorState by editor.uiState.collectAsStateWithLifecycle()
    if (editorState.open) {
        EventEditorContent(
            state = editorState,
            callbacks =
                EventEditorCallbacks(
                    update = editor::update,
                    save = editor::save,
                    close = editor::close,
                    chooseScope = editor::chooseScope,
                    dismissScope = editor::dismissScope,
                    reload = editor::reload,
                ),
            modifier = modifier,
        )
        return
    }
    Box(modifier.fillMaxSize()) {
        CalendarRoute(
            onOpen = { editor.edit(it, calendarViewModel.uiState.value.calendars) },
            viewModel = calendarViewModel,
        )
        FloatingActionButton(
            onClick = {
                val calendar = calendarViewModel.uiState.value
                val date = if (calendar.view == CalendarView.DAY) calendar.anchor else calendar.today
                editor.create(date, calendar.calendars)
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag(EventEditorTags.ADD),
        ) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.calendar_edit_add)) }
    }
}
