package ch.sonensei.cockpit.gtd.next

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R

/**
 * The saved filters (presets) above the list: tapping one applies it (tapping the active one clears the filter), its
 * close icon deletes it, and "Save filter…" saves the current filter under a name. Hidden while there is nothing to
 * show: no presets and no filter to save.
 */
@Composable
internal fun Presets(
    state: NextUiState,
    callbacks: NextCallbacks,
) {
    val presets = state.presets
    val canSave = state.filter.activeCount > 0 && presets.active == null
    if (presets.presets.isEmpty() && !canSave && presets.message == null && presets.editor == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(NextTags.PRESETS)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.presets.forEachIndexed { index, preset ->
                val deleteLabel = stringResource(R.string.next_preset_delete, preset.name)
                FilterChip(
                    selected = preset == presets.active,
                    onClick = { callbacks.applyPreset(preset) },
                    label = { Text(preset.name) },
                    trailingIcon = {
                        // Not an IconButton: its 48 dp touch target would cover the chip and take its taps.
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = deleteLabel,
                            modifier =
                                Modifier
                                    .size(18.dp)
                                    .clickable(role = Role.Button) { callbacks.removePreset(preset) }
                                    .testTag(NextTags.presetDelete(index)),
                        )
                    },
                    modifier = Modifier.testTag(NextTags.preset(index)),
                )
            }
            if (canSave) {
                AssistChip(
                    onClick = callbacks.openSavePreset,
                    label = { Text(stringResource(R.string.next_preset_save)) },
                    leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, contentDescription = null) },
                    modifier = Modifier.testTag(NextTags.PRESET_SAVE),
                )
            }
        }
        presets.message?.let { message ->
            Text(
                presetMessageText(message),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier =
                    Modifier
                        .padding(bottom = 4.dp)
                        .testTag(NextTags.PRESET_MESSAGE)
                        .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
    presets.editor?.let { SavePresetDialog(it, callbacks) }
}

@Composable
private fun SavePresetDialog(
    editor: PresetEditor,
    callbacks: NextCallbacks,
) {
    AlertDialog(
        onDismissRequest = callbacks.cancelSavePreset,
        title = { Text(stringResource(R.string.next_presets)) },
        text = {
            Column {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = callbacks.editPresetName,
                    label = { Text(stringResource(R.string.next_preset_name)) },
                    singleLine = true,
                    enabled = !editor.saving,
                    isError = editor.message != null,
                    modifier = Modifier.fillMaxWidth().testTag(NextTags.PRESET_NAME),
                )
                editor.message?.let { message ->
                    Text(
                        presetMessageText(message),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier
                                .padding(top = 4.dp)
                                .testTag(NextTags.PRESET_DIALOG_MESSAGE)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = callbacks.savePreset,
                enabled = !editor.saving,
                modifier = Modifier.testTag(NextTags.PRESET_CONFIRM),
            ) { Text(stringResource(R.string.next_preset_save_button)) }
        },
        dismissButton = {
            TextButton(onClick = callbacks.cancelSavePreset, modifier = Modifier.testTag(NextTags.PRESET_CANCEL)) {
                Text(stringResource(R.string.next_preset_cancel))
            }
        },
        modifier = Modifier.testTag(NextTags.PRESET_DIALOG),
    )
}

@Composable
private fun presetMessageText(message: PresetMessage): String =
    when (message) {
        PresetMessage.NAME_REQUIRED -> stringResource(R.string.next_preset_name_required)
        PresetMessage.TOO_MANY -> stringResource(R.string.next_preset_too_many, NextPresets.MAX_PRESETS)
        PresetMessage.CONFLICT -> stringResource(R.string.next_preset_conflict)
        PresetMessage.OFFLINE -> stringResource(R.string.next_preset_offline)
        PresetMessage.FAILED -> stringResource(R.string.next_preset_failed)
    }
