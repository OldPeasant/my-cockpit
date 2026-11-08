package ch.sonensei.cockpit.bookmarks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R

internal object AdderTags {
    const val ADD = "bookmarks-add"
    const val DIALOG = "bookmarks-add-dialog"
    const val URL = "bookmarks-add-url"
    const val TITLE = "bookmarks-add-title"
    const val TAGS = "bookmarks-add-tags"
    const val READ_LATER = "bookmarks-add-read-later"
    const val SAVE = "bookmarks-add-save"
    const val CANCEL = "bookmarks-add-cancel"
    const val ERROR = "bookmarks-add-error"
}

/** The "Add bookmark" dialog: the web address, and optionally a title, tags and read later. */
@Composable
internal fun BookmarkAdder(
    adding: AddBookmarkState,
    onSave: (NewBookmark) -> Unit,
    onCancel: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(adding.draft.url) }
    var title by rememberSaveable { mutableStateOf(adding.draft.title) }
    var tags by rememberSaveable { mutableStateOf(adding.draft.tags) }
    var readLater by rememberSaveable { mutableStateOf(adding.draft.readLater) }
    val save = { onSave(NewBookmark(url, title, tags, readLater)) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.bookmarks_add_heading)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.take(MAX_URL) },
                    label = { Text(stringResource(R.string.bookmarks_field_url)) },
                    placeholder = { Text("https://") },
                    singleLine = true,
                    isError = adding.failure == AddFailure.INVALID_URL,
                    enabled = !adding.saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag(AdderTags.URL),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(MAX_TITLE) },
                    label = { Text(stringResource(R.string.bookmarks_field_title_optional)) },
                    singleLine = true,
                    enabled = !adding.saving,
                    modifier = Modifier.fillMaxWidth().testTag(AdderTags.TITLE),
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text(stringResource(R.string.bookmarks_field_tags)) },
                    supportingText = { Text(stringResource(R.string.bookmarks_field_tags_hint)) },
                    singleLine = true,
                    enabled = !adding.saving,
                    modifier = Modifier.fillMaxWidth().testTag(AdderTags.TAGS),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = readLater, enabled = !adding.saving, role = Role.Checkbox, onValueChange = { readLater = it })
                        .testTag(AdderTags.READ_LATER),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = readLater, onCheckedChange = null, enabled = !adding.saving)
                    Text(stringResource(R.string.bookmarks_view_queue))
                }
                adding.failure?.let {
                    Text(
                        stringResource(
                            when (it) {
                                AddFailure.INVALID_URL -> R.string.bookmarks_add_invalid_url
                                AddFailure.OFFLINE -> R.string.bookmarks_change_offline
                                AddFailure.FAILED -> R.string.bookmarks_add_failed
                            },
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(AdderTags.ERROR),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = !adding.saving && url.isNotBlank(), modifier = Modifier.testTag(AdderTags.SAVE)) {
                Text(stringResource(R.string.bookmarks_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !adding.saving, modifier = Modifier.testTag(AdderTags.CANCEL)) {
                Text(stringResource(R.string.bookmarks_cancel))
            }
        },
        modifier = Modifier.testTag(AdderTags.DIALOG),
    )
}

/** The server's limits (`CreateBookmarkRequest`). */
private const val MAX_URL = 2048
private const val MAX_TITLE = 500
