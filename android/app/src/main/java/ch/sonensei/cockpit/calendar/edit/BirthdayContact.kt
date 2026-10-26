package ch.sonensei.cockpit.calendar.edit

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.contacts.ContactDetailCallbacks
import ch.sonensei.cockpit.contacts.ContactDetailContent
import ch.sonensei.cockpit.contacts.ContactDetailViewModel
import ch.sonensei.cockpit.contacts.ContactIntents
import java.util.UUID

/**
 * The contact a tapped birthday comes from (a birthday has no event to open), shown in the calendar tab; [back] and
 * the system back return to the calendar.
 */
@Composable
fun BirthdayContact(
    contactId: UUID,
    back: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(contactId) { viewModel.open(contactId) }
    BackHandler(onBack = back)
    val context = LocalContext.current
    val noApp = stringResource(R.string.contacts_no_app)
    val launch = { intent: Intent ->
        if (!ContactIntents.launch(context, intent)) Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show()
    }
    ContactDetailContent(
        state = state,
        callbacks =
            ContactDetailCallbacks(
                back = back,
                call = { launch(ContactIntents.dial(it)) },
                mail = { launch(ContactIntents.mail(it)) },
                retry = viewModel::refresh,
            ),
        modifier = modifier,
    )
}
