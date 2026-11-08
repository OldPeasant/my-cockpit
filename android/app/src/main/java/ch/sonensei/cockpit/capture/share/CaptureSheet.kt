package ch.sonensei.cockpit.capture.share

import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object ShareTags {
    const val SHEET = "share:sheet"
    const val TEXT = "share:text"
    const val NOTES = "share:notes"
    const val FILE = "share:file"
    const val SAVE = "share:save"
    const val CANCEL = "share:cancel"
    const val ERROR = "share:error"
    const val URL = "share:url"
    const val DESTINATION_INBOX = "share:destination:inbox"
    const val DESTINATION_READ_LATER = "share:destination:read-later"
    const val SAVE_ANOTHER = "share:save-another"
    const val ATTACH = "share:attach"
    const val REMOVE_FILE = "share:remove-file"
    const val PREVIEW = "share:preview"
    const val PREVIEW_PROJECT = "share:preview:project"
    const val PREVIEW_CONTEXT = "share:preview:context"
    const val PREVIEW_DUE = "share:preview:due"
    const val PREVIEW_WAITING = "share:preview:waiting"
}

/** The system picker's MIME filter: any file, photos included. */
private val PICKABLE_TYPES = arrayOf("*/*")

/** The compact capture sheet over the sending app: the title is the one big element, everything else stays quiet. */
@Composable
fun CaptureSheet(
    state: ShareUiState,
    onTextChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDestinationChange: (ShareDestination) -> Unit = {},
    /** Focus the title (and so open the keyboard) on open: for an empty capture, not for a share to review. */
    autoFocus: Boolean = false,
    /** Saves and keeps the sheet open, cleared, for the next capture; null hides the button. */
    onSaveAndAddAnother: (() -> Unit)? = null,
    /** Attaches files picked with the system picker; null hides the attach button. */
    onAddFiles: ((List<Uri>) -> Unit)? = null,
    /** Removes an attachment; null shows the attachments without a remove action. */
    onRemoveFile: ((Uri) -> Unit)? = null,
) {
    val titleFocus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { titleFocus.requestFocus() }
    // After "save and add another" the cleared title takes the focus again for the next capture.
    LaunchedEffect(state.savedAndCleared) { if (state.savedAndCleared > 0) titleFocus.requestFocus() }
    // A save cannot be interrupted: leaving would cancel the copy of the shared files and drop the capture.
    BackHandler(enabled = state.saving) {}
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
                if (state.offersReadLater) {
                    DestinationChoice(state, onDestinationChange)
                } else {
                    Heading()
                }
                TextField(
                    value = state.text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).testTag(ShareTags.TEXT),
                    textStyle = MaterialTheme.typography.headlineSmall,
                    placeholder = {
                        val hint = if (state.readLater) R.string.share_title_hint_read_later else R.string.share_text_hint
                        Text(stringResource(hint), style = MaterialTheme.typography.headlineSmall)
                    },
                    singleLine = true,
                    enabled = !state.saving,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave() }),
                    colors = quietFieldColors(),
                )
                if (!state.readLater) state.preview?.takeIf { it.hasTokens }?.let { SyntaxPreview(it) }
                // A bookmark has no notes: the URL and the page's description stand for them.
                if (state.readLater) {
                    Text(
                        state.url.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp).testTag(ShareTags.URL),
                    )
                } else {
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
                }
                if (state.files.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        state.files.forEach { file -> FileChip(file, enabled = !state.saving, onRemove = onRemoveFile) }
                    }
                }
                state.error?.let { error ->
                    Text(
                        error.message(),
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
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // A bookmark has no attachments.
                    if (onAddFiles != null && !state.readLater) {
                        AttachButton(enabled = !state.saving, onAddFiles = onAddFiles)
                    }
                    FlowRow(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onCancel, enabled = !state.saving, modifier = Modifier.testTag(ShareTags.CANCEL)) {
                            Text(stringResource(R.string.share_cancel))
                        }
                        if (onSaveAndAddAnother != null) {
                            TextButton(
                                onClick = onSaveAndAddAnother,
                                enabled = state.canSave,
                                modifier = Modifier.testTag(ShareTags.SAVE_ANOTHER),
                            ) {
                                Text(stringResource(R.string.share_save_add_another))
                            }
                        }
                        Button(onClick = onSave, enabled = state.canSave, modifier = Modifier.testTag(ShareTags.SAVE)) {
                            if (state.saving || state.loadingFiles) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(8.dp))
                            }
                            Text(stringResource(if (state.readLater) R.string.share_save_read_later else R.string.share_save))
                        }
                    }
                }
            }
        }
    }
}

/** Opens the system picker (files and photos, several at once) and hands the picked files to [onAddFiles]. */
@Composable
private fun AttachButton(
    enabled: Boolean,
    onAddFiles: (List<Uri>) -> Unit,
) {
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) onAddFiles(uris)
        }
    IconButton(onClick = { picker.launch(PICKABLE_TYPES) }, enabled = enabled, modifier = Modifier.testTag(ShareTags.ATTACH)) {
        Icon(Icons.Outlined.AttachFile, contentDescription = stringResource(R.string.share_attach))
    }
}

@Composable
private fun FileChip(
    file: SharedFile,
    enabled: Boolean,
    onRemove: ((Uri) -> Unit)?,
) {
    InputChip(
        selected = false,
        onClick = { onRemove?.invoke(file.uri) },
        enabled = enabled && onRemove != null,
        label = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null, Modifier.size(18.dp)) },
        trailingIcon =
            onRemove?.let {
                {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.share_remove_attachment, file.name),
                        modifier = Modifier.size(18.dp).testTag(ShareTags.REMOVE_FILE),
                    )
                }
            },
        modifier = Modifier.widthIn(max = 240.dp).testTag(ShareTags.FILE),
    )
}

/** What the capture syntax in the title will create, as quiet chips under it (SPEC.md §4.5). */
@Composable
private fun SyntaxPreview(preview: CapturePreview) {
    val label = stringResource(R.string.share_preview_label)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .semantics {
                    contentDescription = label
                    liveRegion = LiveRegionMode.Polite
                }.testTag(ShareTags.PREVIEW),
    ) {
        if (preview.waiting) {
            val text =
                preview.waitingFor?.let { stringResource(R.string.share_preview_waiting_for, it) }
                    ?: stringResource(R.string.share_preview_waiting)
            TokenChip(Icons.Outlined.HourglassEmpty, text, ShareTags.PREVIEW_WAITING)
        }
        preview.project?.let { TokenChip(Icons.Outlined.AccountTree, it.title, ShareTags.PREVIEW_PROJECT) }
        preview.contexts.forEach { TokenChip(Icons.Outlined.AlternateEmail, it.name.trim().removePrefix("@"), ShareTags.PREVIEW_CONTEXT) }
        preview.dueDate?.let {
            TokenChip(
                Icons.Outlined.Event,
                stringResource(R.string.share_preview_due, dueText(it)),
                ShareTags.PREVIEW_DUE,
            )
        }
    }
}

@Composable
private fun dueText(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale) }
    return date.format(formatter)
}

@Composable
private fun TokenChip(
    icon: ImageVector,
    text: String,
    tag: String,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.testTag(tag),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp).widthIn(max = 200.dp),
            )
        }
    }
}

@Composable
private fun Heading() {
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
}

/** For a shared web page: the Inbox (a task) or the read-later list (a bookmark), SPEC.md §8.4. */
@Composable
private fun DestinationChoice(
    state: ShareUiState,
    onDestinationChange: (ShareDestination) -> Unit,
) {
    val label = stringResource(R.string.share_destination)
    SingleChoiceSegmentedButtonRow(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .semantics { contentDescription = label },
    ) {
        DESTINATIONS.forEachIndexed { index, (destination, text, icon) ->
            SegmentedButton(
                selected = state.destination == destination,
                onClick = { onDestinationChange(destination) },
                shape = SegmentedButtonDefaults.itemShape(index, DESTINATIONS.size),
                enabled = !state.saving,
                icon = { SegmentedButtonDefaults.Icon(state.destination == destination) { Icon(icon, null, Modifier.size(18.dp)) } },
                label = { Text(stringResource(text)) },
                modifier =
                    Modifier.testTag(
                        if (destination ==
                            ShareDestination.INBOX
                        ) {
                            ShareTags.DESTINATION_INBOX
                        } else {
                            ShareTags.DESTINATION_READ_LATER
                        },
                    ),
            )
        }
    }
}

private val DESTINATIONS =
    listOf(
        Triple(ShareDestination.INBOX, R.string.share_destination_inbox, Icons.Outlined.Inbox),
        Triple(ShareDestination.READ_LATER, R.string.share_destination_read_later, Icons.Outlined.BookmarkBorder),
    )

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

@Composable
private fun ShareError.message(): String =
    when (this) {
        ShareError.EMPTY_TEXT -> stringResource(R.string.share_error_empty)
        ShareError.TOO_MANY_FILES -> stringResource(R.string.share_error_too_many_files, CaptureQueue.MAX_ATTACHMENTS)
        ShareError.TEXT_TOO_LONG -> plural(R.plurals.share_error_too_long, CaptureQueue.MAX_TEXT_LENGTH)
        ShareError.NOTES_TOO_LONG -> plural(R.plurals.share_error_notes_too_long, ShareViewModel.MAX_NOTES_LENGTH)
        ShareError.UNREADABLE_FILE -> stringResource(R.string.share_error_unreadable)
        ShareError.FAILED -> stringResource(R.string.share_error_failed)
    }

/** A count message whose count is also its only format argument. */
@Composable
private fun plural(
    id: Int,
    count: Int,
): String = pluralStringResource(id, count, count)
