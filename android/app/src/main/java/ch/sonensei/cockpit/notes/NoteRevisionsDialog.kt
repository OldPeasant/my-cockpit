package ch.sonensei.cockpit.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.sonensei.cockpit.R
import coil3.ImageLoader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal object NoteRevisionsTags {
    const val DIALOG = "note-revisions"
    const val ITEM = "note-revision"
    const val PREVIEW = "note-revision-preview"
    const val RESTORE = "note-revision-restore"
    const val RETRY = "note-revisions-retry"
    const val CLOSE = "note-revisions-close"
}

/** What the list of earlier versions can ask for. */
data class NoteRevisionsCallbacks(
    val select: (Int) -> Unit = {},
    val restore: () -> Unit = {},
    val retry: () -> Unit = {},
    val close: () -> Unit = {},
)

/**
 * A note's earlier versions (SPEC.md §8.1, the last 20), newest first: tap one to see it, restore the one shown. The
 * web shows the same in its revisions dialog.
 */
@Composable
fun NoteRevisionsDialog(
    state: NoteRevisionsState,
    imageLoader: ImageLoader?,
    callbacks: NoteRevisionsCallbacks,
) {
    AlertDialog(
        onDismissRequest = callbacks.close,
        modifier = Modifier.testTag(NoteRevisionsTags.DIALOG),
        title = { Text(stringResource(R.string.notes_revisions)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val revisions = state.revisions
                when {
                    state.error != null ->
                        Text(
                            stringResource(
                                if (state.error ==
                                    RevisionsError.OFFLINE
                                ) {
                                    R.string.notes_revisions_offline
                                } else {
                                    R.string.notes_revisions_failed
                                },
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    revisions == null -> Text(stringResource(R.string.notes_revisions_loading))
                    revisions.isEmpty() -> Text(stringResource(R.string.notes_revisions_empty))
                    else -> {
                        RevisionList(revisions, state.selected, callbacks.select)
                        state.selectedRevision?.let { Preview(it, imageLoader) }
                    }
                }
            }
        },
        confirmButton = {
            when {
                state.error != null ->
                    TextButton(onClick = callbacks.retry, modifier = Modifier.testTag(NoteRevisionsTags.RETRY)) {
                        Text(stringResource(R.string.notes_retry))
                    }
                !state.revisions.isNullOrEmpty() ->
                    Button(
                        onClick = callbacks.restore,
                        enabled = state.selectedRevision != null,
                        modifier = Modifier.testTag(NoteRevisionsTags.RESTORE),
                    ) {
                        Text(stringResource(R.string.notes_revisions_restore))
                    }
            }
        },
        dismissButton = {
            TextButton(onClick = callbacks.close, modifier = Modifier.testTag(NoteRevisionsTags.CLOSE)) {
                Text(stringResource(R.string.notes_revisions_close))
            }
        },
    )
}

@Composable
private fun RevisionList(
    revisions: List<NoteRevision>,
    selected: Int?,
    onSelect: (Int) -> Unit,
) {
    Column {
        revisions.forEachIndexed { index, revision ->
            if (index > 0) HorizontalDivider()
            val isSelected = revision.number == selected
            Column(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(revision.number) }
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .testTag(NoteRevisionsTags.ITEM),
            ) {
                Text(
                    formatSavedAt(revision.savedAt),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    revisionSummary(revision) ?: stringResource(R.string.notes_revisions_empty_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The revision shown: its title and rendered body, read-only. */
@Composable
private fun Preview(
    revision: NoteRevision,
    imageLoader: ImageLoader?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(NoteRevisionsTags.PREVIEW),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            revision.title?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            if (revision.bodyMd.isNotBlank()) MarkdownView(revision.bodyMd, imageLoader)
        }
    }
}

/** The revision's title, or else the first line of its body; `null` when both are empty. */
internal fun revisionSummary(revision: NoteRevision): String? =
    revision.title?.trim()?.takeIf { it.isNotEmpty() }
        ?: revision.bodyMd
            .trim()
            .lineSequence()
            .first()
            .takeIf { it.isNotEmpty() }

private fun formatSavedAt(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(instant.atZone(ZoneId.systemDefault()))
