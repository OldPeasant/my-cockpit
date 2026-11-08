package ch.sonensei.cockpit.mail.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.files.DriveEntry
import ch.sonensei.cockpit.files.FolderCrumb
import java.util.UUID

/** Why a folder of the Files picker could not be listed. */
enum class FilePickerProblem { OFFLINE, FAILED }

/** The cockpit Files picker of compose while it is open: the folder shown ([path] from the drive root) and its entries. */
data class FilePickerState(
    val path: List<FolderCrumb> = emptyList(),
    val entries: List<DriveEntry> = emptyList(),
    val loading: Boolean = true,
    val problem: FilePickerProblem? = null,
) {
    val folderId: UUID? get() = path.lastOrNull()?.id
}

/** What the Files picker can ask for. */
data class FilePickerCallbacks(
    val open: (DriveEntry.Folder) -> Unit = {},
    val up: () -> Unit = {},
    val pick: (DriveEntry.File) -> Unit = {},
    val retry: () -> Unit = {},
    val close: () -> Unit = {},
)

/** Browses the drive folder by folder; picking a file attaches it and closes the picker. */
@Composable
internal fun FilePickerDialog(
    state: FilePickerState,
    callbacks: FilePickerCallbacks,
) {
    AlertDialog(
        onDismissRequest = callbacks.close,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = callbacks.close, modifier = Modifier.testTag(MailComposeTags.FILES_CANCEL)) {
                Text(stringResource(R.string.mail_compose_files_cancel))
            }
        },
        title = { Text(stringResource(R.string.mail_compose_files_title)) },
        text = {
            Column(Modifier.fillMaxWidth().testTag(MailComposeTags.FILES)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.path.isNotEmpty()) {
                        IconButton(onClick = callbacks.up, modifier = Modifier.testTag(MailComposeTags.FILES_UP)) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.mail_compose_files_up))
                        }
                    }
                    Text(
                        state.path.lastOrNull()?.name ?: stringResource(R.string.mail_compose_files_root),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                when {
                    state.problem != null ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(
                                    if (state.problem == FilePickerProblem.OFFLINE) {
                                        R.string.mail_compose_offline
                                    } else {
                                        R.string.mail_compose_files_failed
                                    },
                                ),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f).testTag(MailComposeTags.FILES_PROBLEM),
                            )
                            TextButton(onClick = callbacks.retry) { Text(stringResource(R.string.mail_retry)) }
                        }
                    !state.loading && state.entries.isEmpty() ->
                        Text(
                            stringResource(R.string.mail_compose_files_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp).testTag(MailComposeTags.FILES_EMPTY),
                        )
                    else ->
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(state.entries, key = { it.id }) { entry -> EntryRow(entry, callbacks) }
                        }
                }
            }
        },
    )
}

@Composable
private fun EntryRow(
    entry: DriveEntry,
    callbacks: FilePickerCallbacks,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                when (entry) {
                    is DriveEntry.Folder -> callbacks.open(entry)
                    is DriveEntry.File -> callbacks.pick(entry)
                }
            }.padding(vertical = 12.dp)
            .testTag(MailComposeTags.filesEntry(entry.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (entry is DriveEntry.Folder) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
