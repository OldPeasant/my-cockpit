package ch.sonensei.cockpit.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.auth.LocalPasskeySettingsEnabled
import ch.sonensei.cockpit.auth.LogoutButton
import ch.sonensei.cockpit.auth.PasskeySettings
import ch.sonensei.cockpit.core.network.ServerUrlValidation

/** Test tags of the settings sections. */
object SettingsTags {
    const val SERVER = "settings:server"
    const val SERVER_URL = "settings:server:url"
    const val SERVER_USER = "settings:server:user"
    const val SERVER_CHANGE = "settings:server:change"
    const val SERVER_FIELD = "settings:server:field"
    const val SERVER_SAVE = "settings:server:save"
    const val SERVER_ERROR = "settings:server:error"
    const val NOTIFICATIONS = "settings:notifications"
    const val NOTIFICATIONS_OPEN = "settings:notifications:open"
    const val EXACT_OPEN = "settings:exact:open"
    const val PUSH_CHOOSE = "settings:push:choose"
    const val PUSH_GET = "settings:push:get"
    const val PUSH_OPTION = "settings:push:option"
    const val CAPTURE = "settings:capture"
    const val CAPTURE_PENDING = "settings:capture:pending"
    const val CAPTURE_RESULT = "settings:capture:result"
    const val CAPTURE_SYNC = "settings:capture:sync"
    const val ABOUT = "settings:about"
    const val ABOUT_VERSION = "settings:about:version"
    const val ABOUT_DOWNLOAD = "settings:about:download"
}

/** Where to find a UnifiedPush distributor when none is installed. */
internal const val DISTRIBUTORS_URL = "https://unifiedpush.org/users/distributors/"

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    // Scrolls so that logout stays reachable however long the sections get.
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // The device sections talk to the server and the system: only inside a session (not in previews or shell tests).
        if (LocalPasskeySettingsEnabled.current) DeviceSettingsRoute()
        PasskeySettings()
        LogoutButton(Modifier.padding(bottom = 16.dp))
    }
}

@Composable
private fun DeviceSettingsRoute(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    DeviceSettingsSections(
        state = state,
        actions =
            SettingsActions(
                onChangeServer = viewModel::openServerForm,
                onServerAddressChange = viewModel::onServerAddressChange,
                onSaveServer = viewModel::saveServer,
                onCloseServerForm = viewModel::closeServerForm,
                onRetryUser = viewModel::loadUser,
                onOpenNotificationSettings = {
                    context.open(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
                onOpenExactAlarmSettings = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.open(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null)),
                        )
                    }
                },
                onChooseDistributor = viewModel::openDistributorPicker,
                onDistributorChosen = viewModel::chooseDistributor,
                onCloseDistributorPicker = viewModel::closeDistributorPicker,
                onGetDistributor = { context.open(Intent(Intent.ACTION_VIEW, DISTRIBUTORS_URL.toUri())) },
                onSyncNow = viewModel::syncNow,
                onDownload = { url -> context.open(Intent(Intent.ACTION_VIEW, url.toUri())) },
            ),
    )
}

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No app handles it (e.g. no browser, or a settings screen this device does not have).
    }
}

/** What the sections can ask for; the route turns these into ViewModel calls and system intents. */
data class SettingsActions(
    val onChangeServer: () -> Unit = {},
    val onServerAddressChange: (String) -> Unit = {},
    val onSaveServer: () -> Unit = {},
    val onCloseServerForm: () -> Unit = {},
    val onRetryUser: () -> Unit = {},
    val onOpenNotificationSettings: () -> Unit = {},
    val onOpenExactAlarmSettings: () -> Unit = {},
    val onChooseDistributor: () -> Unit = {},
    val onDistributorChosen: (String) -> Unit = {},
    val onCloseDistributorPicker: () -> Unit = {},
    val onGetDistributor: () -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onDownload: (String) -> Unit = {},
)

/** Server, notifications, capture and about. Stateless. */
@Composable
fun DeviceSettingsSections(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    ServerSection(state, actions)
    NotificationsSection(state, actions)
    CaptureSection(state, actions)
    AboutSection(state, actions)
    state.serverForm?.let { ServerDialog(it, actions) }
    if (state.distributorPickerOpen) state.distributors?.let { DistributorPicker(it, actions) }
}

@Composable
private fun SettingsCard(
    title: Int,
    tag: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().testTag(tag),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** A line with an icon that says at a glance whether the setting is fine. */
@Composable
private fun StatusLine(
    ok: Boolean,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Icon(
            if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Muted(
    text: String,
    modifier: Modifier = Modifier,
) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

@Composable
private fun LabelledValue(
    label: String,
    value: String,
    tag: String,
) {
    Column(Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ServerSection(
    state: SettingsUiState,
    actions: SettingsActions,
) = SettingsCard(R.string.settings_server_title, SettingsTags.SERVER) {
    LabelledValue(
        stringResource(R.string.settings_server_address),
        state.serverUrl?.toString() ?: stringResource(R.string.settings_server_not_set),
        SettingsTags.SERVER_URL,
    )
    when (val user = state.user) {
        is UserName.Known -> LabelledValue(stringResource(R.string.settings_server_user), user.name, SettingsTags.SERVER_USER)
        UserName.Loading ->
            LabelledValue(
                stringResource(R.string.settings_server_user),
                stringResource(R.string.settings_server_user_loading),
                SettingsTags.SERVER_USER,
            )
        UserName.Unknown ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Muted(stringResource(R.string.settings_server_user_unknown), Modifier.weight(1f).testTag(SettingsTags.SERVER_USER))
                TextButton(onClick = actions.onRetryUser) { Text(stringResource(R.string.settings_retry)) }
            }
    }
    OutlinedButton(onClick = actions.onChangeServer, modifier = Modifier.testTag(SettingsTags.SERVER_CHANGE)) {
        Text(stringResource(R.string.settings_server_change))
    }
}

@Composable
private fun NotificationsSection(
    state: SettingsUiState,
    actions: SettingsActions,
) = SettingsCard(R.string.settings_notifications_title, SettingsTags.NOTIFICATIONS) {
    Muted(stringResource(R.string.settings_notifications_body))
    state.permissions?.let { permissions ->
        StatusLine(
            permissions.notifications,
            stringResource(
                if (permissions.notifications) R.string.settings_notifications_allowed else R.string.settings_notifications_blocked,
            ),
        )
        OutlinedButton(onClick = actions.onOpenNotificationSettings, modifier = Modifier.testTag(SettingsTags.NOTIFICATIONS_OPEN)) {
            Text(stringResource(R.string.settings_notifications_open))
        }
        StatusLine(
            permissions.exactAlarms,
            stringResource(if (permissions.exactAlarms) R.string.settings_exact_allowed else R.string.settings_exact_blocked),
        )
        if (permissions.exactAlarmsSettingExists) {
            OutlinedButton(onClick = actions.onOpenExactAlarmSettings, modifier = Modifier.testTag(SettingsTags.EXACT_OPEN)) {
                Text(stringResource(R.string.settings_exact_open))
            }
        }
    }
    state.distributors?.let { distributors ->
        val selected = distributors.selected
        when {
            selected != null -> StatusLine(true, stringResource(R.string.settings_push_selected, selected.label))
            distributors.installed.isEmpty() -> StatusLine(false, stringResource(R.string.settings_push_none))
            else -> StatusLine(false, stringResource(R.string.settings_push_not_selected))
        }
        if (distributors.installed.isEmpty()) {
            OutlinedButton(onClick = actions.onGetDistributor, modifier = Modifier.testTag(SettingsTags.PUSH_GET)) {
                Text(stringResource(R.string.settings_push_get))
            }
        } else {
            OutlinedButton(onClick = actions.onChooseDistributor, modifier = Modifier.testTag(SettingsTags.PUSH_CHOOSE)) {
                Text(stringResource(R.string.settings_push_choose))
            }
        }
    }
}

@Composable
private fun CaptureSection(
    state: SettingsUiState,
    actions: SettingsActions,
) = SettingsCard(R.string.settings_capture_title, SettingsTags.CAPTURE) {
    state.capture?.let { capture ->
        StatusLine(
            capture.pendingCount == 0,
            if (capture.pendingCount == 0) {
                stringResource(R.string.settings_capture_synced)
            } else {
                pluralStringResource(R.plurals.settings_capture_pending, capture.pendingCount, capture.pendingCount)
            },
            Modifier.testTag(SettingsTags.CAPTURE_PENDING),
        )
        val result =
            when {
                capture.lastError != null -> stringResource(R.string.settings_capture_failed, captureFailureText(capture.lastError))
                capture.pendingCount > 0 -> stringResource(R.string.settings_capture_waiting)
                else -> null
            }
        result?.let { Muted(it, Modifier.testTag(SettingsTags.CAPTURE_RESULT)) }
    }
    when {
        state.syncRequested -> R.string.settings_capture_sync_requested
        state.syncFailed -> R.string.settings_capture_sync_failed
        else -> null
    }?.let {
        Text(
            stringResource(it),
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.syncFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    OutlinedButton(onClick = actions.onSyncNow, modifier = Modifier.testTag(SettingsTags.CAPTURE_SYNC)) {
        Text(stringResource(R.string.settings_capture_sync_now))
    }
}

@Composable
private fun AboutSection(
    state: SettingsUiState,
    actions: SettingsActions,
) = SettingsCard(R.string.settings_about_title, SettingsTags.ABOUT) {
    state.appVersion?.let {
        Text(
            stringResource(R.string.settings_about_version, it.name, it.code),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag(SettingsTags.ABOUT_VERSION),
        )
    }
    state.downloadUrl?.let { url ->
        OutlinedButton(onClick = { actions.onDownload(url.toString()) }, modifier = Modifier.testTag(SettingsTags.ABOUT_DOWNLOAD)) {
            Text(stringResource(R.string.settings_about_download))
        }
    }
}

@Composable
private fun ServerDialog(
    form: ServerForm,
    actions: SettingsActions,
) {
    AlertDialog(
        onDismissRequest = { if (!form.saving) actions.onCloseServerForm() },
        title = { Text(stringResource(R.string.settings_server_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted(stringResource(R.string.settings_server_dialog_body))
                OutlinedTextField(
                    value = form.address,
                    onValueChange = actions.onServerAddressChange,
                    label = { Text(stringResource(R.string.settings_server_address)) },
                    placeholder = { Text(stringResource(R.string.login_server_url_hint)) },
                    singleLine = true,
                    enabled = !form.saving,
                    isError = form.error != null || form.saveFailed,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { actions.onSaveServer() }),
                    modifier = Modifier.fillMaxWidth().testTag(SettingsTags.SERVER_FIELD),
                )
                form.error?.let { error ->
                    Text(
                        stringResource(
                            when (error.reason) {
                                ServerUrlValidation.Reason.MALFORMED -> R.string.login_error_server_url_malformed_address
                                ServerUrlValidation.Reason.HTTPS_REQUIRED -> R.string.login_error_server_url_https_required_address
                            },
                            error.address,
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(SettingsTags.SERVER_ERROR).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (form.saveFailed) {
                    Text(
                        stringResource(R.string.settings_server_save_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(SettingsTags.SERVER_ERROR).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onSaveServer, enabled = !form.saving, modifier = Modifier.testTag(SettingsTags.SERVER_SAVE)) {
                Text(stringResource(R.string.settings_server_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = actions.onCloseServerForm,
                enabled = !form.saving,
            ) { Text(stringResource(R.string.settings_server_cancel)) }
        },
    )
}

@Composable
private fun DistributorPicker(
    distributors: DistributorState,
    actions: SettingsActions,
) {
    AlertDialog(
        onDismissRequest = actions.onCloseDistributorPicker,
        title = { Text(stringResource(R.string.settings_push_picker_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                distributors.installed.forEach { distributor ->
                    val chosen = distributor.packageName == distributors.selected?.packageName
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = chosen,
                                    role = Role.RadioButton,
                                ) { actions.onDistributorChosen(distributor.packageName) }
                                .padding(vertical = 4.dp)
                                .testTag(SettingsTags.PUSH_OPTION),
                    ) {
                        RadioButton(selected = chosen, onClick = null)
                        Text(distributor.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onCloseDistributorPicker) { Text(stringResource(R.string.settings_server_cancel)) }
        },
    )
}
