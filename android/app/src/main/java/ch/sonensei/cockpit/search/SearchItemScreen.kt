package ch.sonensei.cockpit.search

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ch.sonensei.cockpit.contacts.ContactDetailRoute
import ch.sonensei.cockpit.gtd.task.TaskDetailNavigation
import ch.sonensei.cockpit.gtd.task.TaskDetailScreen
import ch.sonensei.cockpit.mail.compose.ComposeMode
import ch.sonensei.cockpit.mail.compose.MailComposeRoute
import ch.sonensei.cockpit.mail.read.MailReadRoute
import ch.sonensei.cockpit.notes.NoteEditorRoute
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.util.UUID

/**
 * An item opened from the search results in its Android detail view (one of [SearchItemTypes.inApp]): the note editor,
 * a mail (with reply and forward), a contact, or a task (the shell opens tasks on their own route, this is a fallback). [onClose] leaves it, back to the results.
 */
@Composable
fun SearchItemScreen(
    type: String,
    id: UUID,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Without Hilt (shell tests) there are no view models to show the item with.
    if (LocalActivity.current !is GeneratedComponentManagerHolder) return
    when (type) {
        "NOTE" ->
            // The editor saves what is left when it closes; leaving this screen clears it, which saves too.
            NoteEditorRoute(id = id, key = id.toString(), onClose = { onClose() }, modifier = modifier)
        "EMAIL" -> SearchMail(id, onClose, modifier)
        "CONTACT" -> SearchContact(id, onClose, modifier)
        "TASK" -> TaskDetailScreen(id, TaskDetailNavigation(close = onClose), modifier)
        else -> LaunchedEffect(type) { onClose() }
    }
}

@Composable
private fun SearchMail(
    id: UUID,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    var composing by rememberSaveable { mutableStateOf<String?>(null) }
    val compose = composing?.let(ComposeMode::parse)
    if (compose != null) {
        MailComposeRoute(mode = compose, onClose = { composing = null }, modifier = modifier)
    } else {
        MailReadRoute(
            id = id,
            onClose = onClose,
            onRemoved = { onClose() },
            onCompose = { composing = it.encode() },
            modifier = modifier,
        )
    }
}

/** A contact with its links panel; related and linked items open in place (see [ContactDetailRoute]). */
@Composable
private fun SearchContact(
    id: UUID,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    ContactDetailRoute(id, onClose = onClose, modifier = modifier)
}
