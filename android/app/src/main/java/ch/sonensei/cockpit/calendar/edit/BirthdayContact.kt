package ch.sonensei.cockpit.calendar.edit

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import ch.sonensei.cockpit.contacts.ContactDetailRoute
import ch.sonensei.cockpit.contacts.ContactDetailViewModel
import java.util.UUID

/**
 * The contact a tapped birthday comes from (a birthday has no event to open), shown in the calendar tab; [back] and
 * the system back return to the calendar. It has the links panel, and related and linked items open in place.
 */
@Composable
fun BirthdayContact(
    contactId: UUID,
    back: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactDetailViewModel = hiltViewModel(),
) {
    ContactDetailRoute(contactId, onClose = back, modifier = modifier, viewModel = viewModel)
}
