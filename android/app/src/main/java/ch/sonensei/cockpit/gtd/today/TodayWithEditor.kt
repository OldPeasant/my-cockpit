package ch.sonensei.cockpit.gtd.today

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.calendar.edit.BirthdayContact
import ch.sonensei.cockpit.calendar.edit.EventEditorCallbacks
import ch.sonensei.cockpit.calendar.edit.EventEditorContent
import ch.sonensei.cockpit.calendar.edit.EventEditorViewModel
import java.util.UUID

/**
 * Today with its events and habits: a tapped event opens the calendar's event editor (a birthday opens its contact, [contact]),
 * and the events and habits load again whenever the editor closes, so a saved change shows.
 */
@Composable
fun TodayWithEditor(
    openNext: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = hiltViewModel(),
    extras: TodayExtrasViewModel = hiltViewModel(),
    editor: EventEditorViewModel = hiltViewModel(),
    contact: @Composable (id: UUID, back: () -> Unit) -> Unit = { id, back -> BirthdayContact(id, back, modifier) },
) {
    val editorState by editor.uiState.collectAsStateWithLifecycle()
    val extrasState by extras.uiState.collectAsStateWithLifecycle()
    var contactId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(editorState.open) { if (!editorState.open) extras.load() }
    contactId?.let {
        contact(UUID.fromString(it)) { contactId = null }
        return
    }
    if (editorState.open) {
        EventEditorContent(state = editorState, callbacks = editorCallbacks(editor), modifier = modifier)
        return
    }
    TodayRoute(
        modifier = modifier,
        viewModel = viewModel,
        extras = extrasState,
        extrasCallbacks =
            TodayExtrasCallbacks(
                openEvent = { occurrence ->
                    val birthday = occurrence.contactId(extrasState.calendars)
                    if (birthday != null) contactId = birthday.toString() else editor.edit(occurrence, extrasState.calendars)
                },
                checkHabit = extras::check,
            ),
        openNext = openNext,
    )
}

private fun editorCallbacks(editor: EventEditorViewModel) = EventEditorCallbacks.of(editor)
