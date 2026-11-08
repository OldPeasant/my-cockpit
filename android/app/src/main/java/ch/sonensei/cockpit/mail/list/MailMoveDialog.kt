package ch.sonensei.cockpit.mail.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import java.util.UUID

internal object MailMoveTags {
    const val DIALOG = "mail-move-dialog"
    const val CANCEL = "mail-move-cancel"

    fun folder(id: UUID) = "mail-move-folder:$id"
}

/** "Move to folder": [folders] (of one account, the mail's own folder left out); a tap moves the mail there. */
@Composable
internal fun MoveFolderDialog(
    folders: List<MailFolder>,
    onPick: (MailFolder) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mail_move)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(folders, key = { it.id }) { folder ->
                    Text(
                        folderLabel(folder),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(folder) }
                                .padding(start = (folder.depth * 16).dp, top = 12.dp, bottom = 12.dp)
                                .testTag(MailMoveTags.folder(folder.id)),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(MailMoveTags.CANCEL)) {
                Text(stringResource(R.string.mail_folder_cancel))
            }
        },
        modifier = Modifier.testTag(MailMoveTags.DIALOG),
    )
}
