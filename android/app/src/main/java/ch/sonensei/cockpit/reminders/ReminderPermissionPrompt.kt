package ch.sonensei.cockpit.reminders

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What the reminder prompt asks for; both false means it is hidden. */
data class ReminderPromptState(
    val askNotifications: Boolean = false,
    val askExactAlarms: Boolean = false,
    /** The notification permission was asked for before: Android may not show its dialog again, so open Settings. */
    val notificationsViaSettings: Boolean = false,
) {
    val visible: Boolean get() = askNotifications || askExactAlarms
}

/**
 * Asks for the permissions reminders need in context: only once the user has upcoming reminders, not at app start.
 * Opening the shell also starts the reminder sync.
 */
@HiltViewModel
class ReminderPromptViewModel
    @Inject
    constructor(
        private val store: ReminderStore,
        private val alarms: ReminderAlarms,
        private val notifier: ReminderNotifier,
        private val scheduler: ReminderScheduler,
        syncScheduler: ReminderSyncScheduler,
    ) : ViewModel() {
        private val permissions = MutableStateFlow(readPermissions())
        private val dismissed = MutableStateFlow(store.isPromptDismissed())
        private val notificationsRequested = MutableStateFlow(store.wasNotificationPermissionRequested())

        val state: StateFlow<ReminderPromptState> =
            combine(store.scheduled, permissions, dismissed, notificationsRequested) { scheduled, granted, dismissed, requested ->
                if (scheduled.isEmpty() || dismissed) {
                    ReminderPromptState()
                } else {
                    ReminderPromptState(
                        askNotifications = !granted.notifications,
                        askExactAlarms = !granted.exactAlarms,
                        notificationsViaSettings = requested,
                    )
                }
            }.stateIn(viewModelScope, SharingStarted.Eagerly, ReminderPromptState())

        init {
            syncScheduler.start()
        }

        /** Re-reads the permissions, e.g. back from the system dialog or Settings; exact alarms take effect at once. */
        fun refreshPermissions() {
            val before = permissions.value
            val now = readPermissions()
            permissions.value = now
            if (now.exactAlarms != before.exactAlarms) scheduler.rescheduleAll()
        }

        fun onNotificationPermissionRequested() {
            store.markNotificationPermissionRequested()
            notificationsRequested.value = true
        }

        fun dismiss() {
            store.dismissPrompt()
            dismissed.value = true
        }

        private fun readPermissions() = Granted(notifier.canNotify(), alarms.canScheduleExact)

        private data class Granted(
            val notifications: Boolean,
            val exactAlarms: Boolean,
        )
    }

object ReminderPromptTags {
    const val CARD = "reminders:prompt"
    const val ALLOW_NOTIFICATIONS = "reminders:prompt:notifications"
    const val ALLOW_EXACT = "reminders:prompt:exact"
    const val DISMISS = "reminders:prompt:dismiss"
}

/** The reminder prompt above the shell's content; nothing while it has nothing to ask. */
@Composable
fun ReminderPermissionPrompt(
    modifier: Modifier = Modifier,
    viewModel: ReminderPromptViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshPermissions()
        onPauseOrDispose {}
    }
    val requestNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshPermissions() }
    ReminderPermissionCard(
        state = state,
        onAllowNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !state.notificationsViaSettings) {
                viewModel.onNotificationPermissionRequested()
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        },
        onAllowExactAlarms = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null)),
                )
            }
        },
        onDismiss = viewModel::dismiss,
        modifier = modifier,
    )
}

@Composable
internal fun ReminderPermissionCard(
    state: ReminderPromptState,
    onAllowNotifications: () -> Unit,
    onAllowExactAlarms: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(ReminderPromptTags.CARD),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.NotificationsActive, contentDescription = null)
                Text(stringResource(R.string.reminder_prompt_title), style = MaterialTheme.typography.titleSmall)
            }
            Text(
                stringResource(
                    if (state.askNotifications) R.string.reminder_prompt_notifications else R.string.reminder_prompt_exact,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.askNotifications) {
                    Button(onClick = onAllowNotifications, modifier = Modifier.testTag(ReminderPromptTags.ALLOW_NOTIFICATIONS)) {
                        Text(stringResource(R.string.reminder_prompt_allow_notifications))
                    }
                }
                if (state.askExactAlarms) {
                    OutlinedButton(onClick = onAllowExactAlarms, modifier = Modifier.testTag(ReminderPromptTags.ALLOW_EXACT)) {
                        Text(stringResource(R.string.reminder_prompt_allow_exact))
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(ReminderPromptTags.DISMISS)) {
                    Text(stringResource(R.string.reminder_prompt_dismiss))
                }
            }
        }
    }
}
