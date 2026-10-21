package ch.sonensei.cockpit.capture.share

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R

object ShareTags {
    const val SHEET = "share:sheet"
    const val TEXT = "share:text"
    const val NOTES = "share:notes"
    const val FILE = "share:file"
    const val SAVE = "share:save"
    const val CANCEL = "share:cancel"
    const val ERROR = "share:error"
}

/** The compact capture sheet over the sending app: the title is the one big element, everything else stays quiet. */
@Composable
fun CaptureSheet(
    state: ShareUiState,
    onTextChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            // A tap outside the sheet cancels, like a bottom sheet. No semantics: Cancel and Back do the same.
            .pointerInput(state.saving) { detectTapGestures { if (!state.saving) onCancel() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier =
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    // Taps on the sheet must not reach the scrim behind it.
                    .pointerInput(Unit) { detectTapGestures {} }
                    .testTag(ShareTags.SHEET),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 2.dp,
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Inbox,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(R.string.share_heading),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                TextField(
                    value = state.text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth().testTag(ShareTags.TEXT),
                    textStyle = MaterialTheme.typography.headlineSmall,
                    placeholder = { Text(stringResource(R.string.share_text_hint), style = MaterialTheme.typography.headlineSmall) },
                    singleLine = true,
                    enabled = !state.saving,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave() }),
                    colors = quietFieldColors(),
                )
                TextField(
                    value = state.notes,
                    onValueChange = onNotesChange,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).testTag(ShareTags.NOTES),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    placeholder = { Text(stringResource(R.string.share_notes_hint), style = MaterialTheme.typography.bodyMedium) },
                    enabled = !state.saving,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = quietFieldColors(),
                )
                if (state.files.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        state.files.forEach { file ->
                            AssistChip(
                                onClick = {},
                                enabled = false,
                                label = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, Modifier.size(18.dp))
                                },
                                modifier = Modifier.widthIn(max = 240.dp).testTag(ShareTags.FILE),
                            )
                        }
                    }
                }
                state.error?.let { error ->
                    Text(
                        stringResource(error.message()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier =
                            Modifier
                                .padding(start = 4.dp, top = 8.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite }
                                .testTag(ShareTags.ERROR),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel, enabled = !state.saving, modifier = Modifier.testTag(ShareTags.CANCEL)) {
                        Text(stringResource(R.string.share_cancel))
                    }
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = onSave, enabled = state.canSave, modifier = Modifier.testTag(ShareTags.SAVE)) {
                        if (state.saving || state.loadingFiles) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(stringResource(R.string.share_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun quietFieldColors() =
    TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
    )

private fun ShareError.message(): Int =
    when (this) {
        ShareError.EMPTY_TEXT -> R.string.share_error_empty
        ShareError.TOO_MANY_FILES -> R.string.share_error_too_many_files
        ShareError.TEXT_TOO_LONG -> R.string.share_error_too_long
        ShareError.UNREADABLE_FILE -> R.string.share_error_unreadable
        ShareError.FAILED -> R.string.share_error_failed
    }
