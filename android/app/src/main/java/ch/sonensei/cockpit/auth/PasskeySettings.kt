package ch.sonensei.cockpit.auth

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.core.network.generated.models.PasskeyResponse
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Test tags of the passkey settings. */
object PasskeyTags {
    const val SECTION = "passkeys:section"
    const val ITEM = "passkeys:item"
    const val ADD = "passkeys:add"
    const val NAME = "passkeys:name"
    const val CODE = "passkeys:code"
    const val SUBMIT = "passkeys:submit"
    const val ERROR = "passkeys:error"
    const val ADDED = "passkeys:added"
}

/** Whether a session is running, so the passkey section can talk to the server; false in previews and shell-only tests. */
val LocalPasskeySettingsEnabled = staticCompositionLocalOf { false }

/** The passkey section of the settings; shows nothing outside a session. */
@Composable
fun PasskeySettings(modifier: Modifier = Modifier) {
    if (!LocalPasskeySettingsEnabled.current) return
    PasskeySettingsRoute(modifier)
}

@Composable
private fun PasskeySettingsRoute(
    modifier: Modifier,
    viewModel: PasskeySettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val credentials = rememberPasskeyCredentials()
    PasskeySettingsSection(
        state = state,
        onRetry = viewModel::load,
        onAdd = viewModel::openForm,
        onNameChange = viewModel::onNameChange,
        onCodeChange = viewModel::onCodeChange,
        onSubmit = { viewModel.submit(credentials) },
        onDismiss = viewModel::closeForm,
        modifier = modifier,
    )
}

/** The user's passkeys and the action to add one; the add form opens as a dialog. Stateless. */
@Composable
fun PasskeySettingsSection(
    state: PasskeySettingsUiState,
    onRetry: () -> Unit,
    onAdd: () -> Unit,
    onNameChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.widthIn(max = 480.dp).fillMaxWidth().testTag(PasskeyTags.SECTION),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.passkeys_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.passkeys_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val passkeys = state.passkeys
            when {
                passkeys != null && passkeys.isEmpty() -> Muted(R.string.passkeys_empty)
                passkeys != null -> passkeys.forEach { PasskeyRow(it) }
                !state.loadFailed -> CircularProgressIndicator(Modifier.size(24.dp))
            }
            if (state.loadFailed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted(R.string.passkeys_load_failed, Modifier.weight(1f))
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.passkeys_retry)) }
                }
            }
            state.added?.let {
                Text(
                    stringResource(R.string.passkeys_added, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(PasskeyTags.ADDED).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            OutlinedButton(onClick = onAdd, modifier = Modifier.testTag(PasskeyTags.ADD)) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.passkeys_add))
            }
        }
    }
    state.form?.let { AddPasskeyDialog(it, onNameChange, onCodeChange, onSubmit, onDismiss) }
}

@Composable
private fun PasskeyRow(passkey: PasskeyResponse) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().testTag(PasskeyTags.ITEM).semantics(mergeDescendants = true) {},
    ) {
        Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(passkey.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                passkey.lastUsedAt?.let { stringResource(R.string.passkeys_last_used, it.formatted()) }
                    ?: stringResource(R.string.passkeys_never_used),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddPasskeyDialog(
    form: PasskeyForm,
    onNameChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val editable = !form.submitting
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
        title = { Text(stringResource(R.string.passkeys_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.passkeys_name)) },
                    singleLine = true,
                    enabled = editable,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag(PasskeyTags.NAME),
                )
                OutlinedTextField(
                    value = form.code,
                    onValueChange = onCodeChange,
                    label = { Text(stringResource(R.string.login_totp)) },
                    supportingText = { Text(stringResource(R.string.passkeys_code_hint)) },
                    singleLine = true,
                    enabled = editable,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (form.canSubmit) onSubmit() }),
                    modifier = Modifier.fillMaxWidth().testTag(PasskeyTags.CODE),
                )
                form.error?.let {
                    Text(
                        stringResource(it.messageRes()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(PasskeyTags.ERROR).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = form.canSubmit, modifier = Modifier.testTag(PasskeyTags.SUBMIT)) {
                Text(stringResource(if (form.submitting) R.string.passkeys_adding else R.string.passkeys_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = editable) { Text(stringResource(R.string.passkeys_cancel)) }
        },
    )
}

@Composable
private fun Muted(
    @StringRes textRes: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

private fun OffsetDateTime.formatted(): String =
    atZoneSameInstant(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

@StringRes
private fun PasskeyError.messageRes(): Int =
    when (this) {
        PasskeyError.WRONG_CODE -> R.string.passkeys_error_wrong_code
        PasskeyError.TOO_MANY_ATTEMPTS -> R.string.login_error_too_many_attempts
        PasskeyError.ALREADY_REGISTERED -> R.string.passkeys_error_already_registered
        PasskeyError.UNAVAILABLE -> R.string.passkeys_error_unavailable
        PasskeyError.FAILED -> R.string.passkeys_error_failed
        PasskeyError.NETWORK -> R.string.passkeys_error_network
        PasskeyError.SERVER -> R.string.passkeys_error_server
    }

@Preview
@Composable
private fun PasskeySettingsPreview() =
    CockpitTheme {
        PasskeySettingsSection(
            state = PasskeySettingsUiState(passkeys = emptyList()),
            onRetry = {},
            onAdd = {},
            onNameChange = {},
            onCodeChange = {},
            onSubmit = {},
            onDismiss = {},
        )
    }
