package ch.sonensei.cockpit.gtd.lists

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.contacts.ContactIntents
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The Waiting tab; outside a Hilt activity (navigation tests, previews) it shows the placeholder. */
@Composable
fun WaitingScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        WaitingRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_waiting, Icons.Outlined.HourglassEmpty, modifier)
    }
}

@Composable
fun WaitingRoute(
    modifier: Modifier = Modifier,
    viewModel: WaitingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val noMailApp = stringResource(R.string.waiting_no_mail_app)
    val scope = rememberCoroutineScope()
    // Tasks whose draft still waits for the contact lookup: further taps on them open no second mail.
    val drafting = remember { mutableSetOf<UUID>() }
    WaitingContent(
        state,
        LiveListCallbacks.of(viewModel),
        viewModel::followUp,
        modifier,
        draftMail = { task ->
            if (drafting.add(task.id)) {
                scope.launch {
                    try {
                        if (!ContactIntents.launch(context, followUpMailIntent(task, viewModel.mailRecipient(task)))) {
                            Toast.makeText(context, noMailApp, Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        drafting.remove(task.id)
                    }
                }
            }
        },
    )
}

/** Waiting For: longest waiting first, how long, and "Follow up" (creates a next action to chase it). */
@Composable
fun WaitingContent(
    state: TaskListUiState,
    callbacks: LiveListCallbacks,
    followUp: (GtdTask, String) -> Unit,
    modifier: Modifier = Modifier,
    draftMail: (GtdTask) -> Unit = {},
) {
    LiveTaskListContent(state, callbacks, stringResource(R.string.waiting_empty), modifier) { task ->
        val followUpTitle =
            task.waitingForText?.trim()?.takeIf { it.isNotEmpty() }?.let {
                stringResource(
                    R.string.waiting_follow_up_title_who,
                    it,
                    task.title,
                )
            }
                ?: stringResource(R.string.waiting_follow_up_title, task.title)
        val busy = task.id in state.busy
        LiveTaskRow(
            task = task,
            readOnly = state.readOnly,
            busy = busy,
            onComplete = callbacks.complete,
            supporting = { WaitingSince(task, state.today) },
            action = {
                Row {
                    val mailDescription = stringResource(R.string.waiting_draft_email_label, task.title)
                    TextButton(
                        onClick = { draftMail(task) },
                        modifier = Modifier.testTag(WaitingTags.draftMail(task.id)).semantics { contentDescription = mailDescription },
                    ) { Text(stringResource(R.string.waiting_draft_email)) }
                    RowAction(
                        task = task,
                        label = stringResource(R.string.waiting_follow_up),
                        description = stringResource(R.string.waiting_follow_up_label, task.title),
                        enabled = !state.readOnly && !busy,
                        onClick = { followUp(task, followUpTitle) },
                    )
                }
            },
        )
    }
}

object WaitingTags {
    fun draftMail(id: UUID) = "waiting-draft-mail-$id"
}

private val EMAIL = Regex("[^\\s<>\"',;()\\[\\]]+@[^\\s<>\"',;()\\[\\]]+\\.[^\\s<>\"',;()\\[\\]]*[^\\s<>\"',;()\\[\\].]")

/**
 * The first email address written in [text], e.g. `anna@example.org` in "Anna (anna@example.org)";
 * a full stop that ends the sentence after it is not part of it.
 */
internal fun emailIn(text: String?): String? = text?.let { EMAIL.find(it)?.value }

/**
 * The `mailto:` of "Draft email": To is [to] (by default an address in the waiting-for text; empty if none), the subject
 * the task's title.
 */
internal fun followUpMailUri(
    task: GtdTask,
    to: String? = emailIn(task.waitingForText),
): String = "mailto:" + Uri.encode(to.orEmpty(), "@") + "?subject=" + Uri.encode(task.title)

/** Opens a mail app (only mail apps answer `ACTION_SENDTO`) with the follow-up to [to] prefilled. */
internal fun followUpMailIntent(
    task: GtdTask,
    to: String? = emailIn(task.waitingForText),
): Intent {
    val intent = Intent(Intent.ACTION_SENDTO, followUpMailUri(task, to).toUri()).putExtra(Intent.EXTRA_SUBJECT, task.title)
    to?.let { intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(it)) }
    return intent
}

/** "Anna, for 9 days"; in the error color once it is time to follow up. */
@Composable
private fun WaitingSince(
    task: GtdTask,
    today: LocalDate,
) {
    val who = task.waitingForText?.trim()?.takeIf { it.isNotEmpty() }
    val days = task.waitingSince?.let { maxOf(0, ChronoUnit.DAYS.between(it, today).toInt()) }
    val since =
        when {
            days == null -> null
            days == 0 -> stringResource(R.string.waiting_since_today)
            else -> pluralStringResource(R.plurals.waiting_days, days, days)
        }
    val text =
        when {
            who != null && since != null -> stringResource(R.string.waiting_for_since, who, since)
            who != null -> stringResource(R.string.waiting_for, who)
            since != null -> since
            else -> return
        }
    val long = days != null && days > LONG_WAIT_DAYS
    Text(text, color = if (long) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
