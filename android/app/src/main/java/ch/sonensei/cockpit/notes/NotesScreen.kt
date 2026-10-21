package ch.sonensei.cockpit.notes

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
import kotlinx.coroutines.Job
import java.util.UUID

/**
 * The Notes screen. Its view models come from Hilt, so outside a Hilt activity (the shell's navigation tests,
 * previews) it shows the placeholder instead of failing.
 */
@Composable
fun NotesScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        NotesRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_notes, Icons.Outlined.Description, modifier)
    }
}

/** The list, or one note opened from it (kept across configuration changes). */
@Composable
fun NotesRoute(modifier: Modifier = Modifier) {
    // "new:<random>" for a new note (each one gets its own editor), else "<id>#<random>": the open note, and this
    // opening of it (a note opened again is loaded again).
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    // The save started when the editor was left: the list reloads once it is done.
    var saving by remember { mutableStateOf<Job?>(null) }
    val current = open
    if (current == null) {
        val viewModel: NotesListViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        // Reloaded each time the list is shown, so a note edited a moment ago shows its new text and place.
        LaunchedEffect(Unit) {
            saving?.join()
            saving = null
            viewModel.refresh()
        }
        NotesListContent(
            state = state,
            callbacks =
                NotesListCallbacks(
                    open = { open = "$it$OPENING${UUID.randomUUID()}" },
                    create = { open = NEW_NOTE + UUID.randomUUID() },
                    selectTag = viewModel::selectTag,
                    retry = viewModel::refresh,
                ),
            modifier = modifier,
        )
    } else {
        val id = current.takeUnless { it.startsWith(NEW_NOTE) }?.substringBefore(OPENING)?.let(UUID::fromString)
        NoteEditorRoute(
            id = id,
            key = id?.toString() ?: current,
            opening = current,
            onClose = {
                saving = it
                open = null
            },
            modifier = modifier,
        )
    }
}

private const val NEW_NOTE = "new:"
private const val OPENING = "#"

/** What the list can ask for, so it can be tested without a view model. */
data class NotesListCallbacks(
    val open: (UUID) -> Unit = {},
    val create: () -> Unit = {},
    /** The tag to filter by; `null` shows all notes. */
    val selectTag: (String?) -> Unit = {},
    val retry: () -> Unit = {},
)

internal object NotesTags {
    const val LIST = "notes-list"
    const val NEW = "notes-new"
    const val MESSAGE = "notes-message"
    const val RETRY = "notes-retry"
    const val EMPTY = "notes-empty"
    const val ALL = "notes-tag-all"

    fun tag(tag: String) = "notes-tag:$tag"

    fun note(id: UUID) = "notes-note:$id"
}

@Composable
fun NotesListContent(
    state: NotesListUiState,
    callbacks: NotesListCallbacks,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            state.message?.let { ListMessage(it, state.loading, callbacks.retry) }
            if (state.tags.isNotEmpty()) TagFilter(state, callbacks.selectTag)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.padding(top = 4.dp))
            if (state.notes.isEmpty() && state.loaded) {
                Text(
                    state.tag?.let { stringResource(R.string.notes_empty_tag, it) } ?: stringResource(R.string.notes_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp).testTag(NotesTags.EMPTY),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize().testTag(NotesTags.LIST), contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(state.notes, key = { it.id }) { note ->
                        NoteRow(note, onClick = { callbacks.open(note.id) })
                        HorizontalDivider(Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = callbacks.create,
            icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
            text = { Text(stringResource(R.string.notes_new)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag(NotesTags.NEW),
        )
    }
}

@Composable
private fun ListMessage(
    message: NotesListMessage,
    loading: Boolean,
    onRetry: () -> Unit,
) {
    val (text, color) =
        when (message) {
            NotesListMessage.OFFLINE -> R.string.notes_offline to MaterialTheme.colorScheme.secondaryContainer
            NotesListMessage.FAILED -> R.string.notes_failed to MaterialTheme.colorScheme.errorContainer
        }
    Surface(color = color, modifier = Modifier.fillMaxWidth().testTag(NotesTags.MESSAGE)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            )
            TextButton(onClick = onRetry, enabled = !loading, modifier = Modifier.testTag(NotesTags.RETRY)) {
                Text(stringResource(R.string.notes_retry))
            }
        }
    }
}

@Composable
private fun TagFilter(
    state: NotesListUiState,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = state.tag == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.notes_all)) },
                modifier = Modifier.testTag(NotesTags.ALL),
            )
        }
        items(state.tags, key = { it.tag }) { tag ->
            FilterChip(
                selected = tag.tag.equals(state.tag, ignoreCase = true),
                onClick = { onSelect(tag.tag) },
                label = { Text("#${tag.tag}") },
                modifier = Modifier.testTag(NotesTags.tag(tag.tag)),
            )
        }
    }
}

@Composable
private fun NoteRow(
    note: Note,
    onClick: () -> Unit,
) {
    val excerpt = remember(note.bodyMd) { plainExcerpt(note.bodyMd) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(NotesTags.note(note.id)),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                note.displayTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (note.pinned) {
                val pinned = stringResource(R.string.notes_pinned)
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp).size(18.dp).semantics { contentDescription = pinned },
                )
            }
        }
        // The title is usually the first line: the excerpt does not repeat it.
        val rest = excerpt.removePrefix(note.displayTitle).trim()
        if (rest.isNotEmpty()) {
            Text(
                rest,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (note.tags.isNotEmpty()) {
            Text(
                note.tags.joinToString("  ") { "#$it" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
