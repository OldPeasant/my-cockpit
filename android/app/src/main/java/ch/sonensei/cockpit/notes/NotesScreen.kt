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
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalResources
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
    // opening of it (a note opened again is loaded again, unless its editor still holds unsaved text).
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    // The save started when the editor was left: the list reloads once it is done.
    var saving by remember { mutableStateOf<Job?>(null) }
    // A note moved to the Trash in its editor: the list offers to undo it once it is shown.
    var trashed by remember { mutableStateOf<Note?>(null) }
    val editors: NoteEditors = hiltViewModel()
    val current = open
    if (current == null) {
        val viewModel: NotesListViewModel = hiltViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        // Reloaded each time the list is shown, so a note edited a moment ago shows its new text and place. Before
        // that, editors left with unsaved text try again, and the ones with nothing left are dropped.
        LaunchedEffect(Unit) {
            trashed?.let(viewModel::trashed)
            trashed = null
            saving?.join()
            saving = null
            editors.settle()
            viewModel.refresh()
        }
        NotesListContent(
            state = state,
            callbacks =
                NotesListCallbacks(
                    open = { open = "$it$OPENING${UUID.randomUUID()}" },
                    // A new note that could not be created yet is opened again rather than started over.
                    create = { open = editors.unsentNewNote() ?: (NEW_NOTE + UUID.randomUUID()) },
                    selectTag = viewModel::selectTag,
                    retry = viewModel::refresh,
                    setQuery = viewModel::setQuery,
                    showArchived = viewModel::showArchived,
                    togglePin = viewModel::togglePin,
                    toggleArchived = viewModel::toggleArchived,
                    delete = viewModel::delete,
                    undo = viewModel::undo,
                    dismiss = viewModel::dismiss,
                ),
            modifier = modifier,
        )
    } else {
        val id = current.takeUnless { it.startsWith(NEW_NOTE) }?.substringBefore(OPENING)?.let(UUID::fromString)
        NoteEditorRoute(
            id = id,
            key = id?.toString() ?: current,
            opening = current,
            onDeleted = { trashed = it },
            onClose = {
                saving = it
                open = null
            },
            modifier = modifier,
        )
    }
}

private const val NEW_NOTE = NoteEditors.NEW_NOTE_KEY
private const val OPENING = "#"

/** What the list can ask for, so it can be tested without a view model. */
data class NotesListCallbacks(
    val open: (UUID) -> Unit = {},
    val create: () -> Unit = {},
    /** The tag to filter by; `null` shows all notes. */
    val selectTag: (String?) -> Unit = {},
    val retry: () -> Unit = {},
    val setQuery: (String) -> Unit = {},
    /** `true`: the Archived view, `false`: the notes. */
    val showArchived: (Boolean) -> Unit = {},
    val togglePin: (Note) -> Unit = {},
    val toggleArchived: (Note) -> Unit = {},
    val delete: (Note) -> Unit = {},
    val undo: (NotesSnackbar.Trashed) -> Unit = {},
    val dismiss: (NotesSnackbar) -> Unit = {},
)

internal object NotesTags {
    const val LIST = "notes-list"
    const val NEW = "notes-new"
    const val MESSAGE = "notes-message"
    const val RETRY = "notes-retry"
    const val EMPTY = "notes-empty"
    const val ALL = "notes-tag-all"
    const val SEARCH = "notes-search"
    const val SEARCH_CLEAR = "notes-search-clear"
    const val SHOW_ACTIVE = "notes-show-active"
    const val SHOW_ARCHIVED = "notes-show-archived"
    const val PIN = "notes-pin"
    const val ARCHIVE = "notes-archive"
    const val DELETE = "notes-delete"

    fun tag(tag: String) = "notes-tag:$tag"

    fun menu(id: UUID) = "notes-menu:$id"

    fun note(id: UUID) = "notes-note:$id"
}

@Composable
fun NotesListContent(
    state: NotesListUiState,
    callbacks: NotesListCallbacks,
    modifier: Modifier = Modifier,
) {
    val snackbarHost = remember { SnackbarHostState() }
    NotesSnackbarEffect(state.snackbar, snackbarHost, callbacks.undo, callbacks.dismiss)
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            state.message?.let { ListMessage(it, state.loading, callbacks.retry) }
            SearchAndView(state, callbacks)
            if (state.tags.isNotEmpty()) TagFilter(state, callbacks.selectTag)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.padding(top = 4.dp))
            val shown = state.shown
            val answered = state.loaded && (!state.searching || state.hits != null)
            if (shown.isEmpty() && answered) {
                Text(
                    emptyText(state),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp).testTag(NotesTags.EMPTY),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize().testTag(NotesTags.LIST), contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(shown, key = { it.id }) { note ->
                        NoteRow(note, busy = note.id in state.busy, callbacks)
                        HorizontalDivider(Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
        // New notes are written in the notes view; the Archived view only holds archived ones.
        if (!state.archived) {
            ExtendedFloatingActionButton(
                onClick = callbacks.create,
                icon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                text = { Text(stringResource(R.string.notes_new)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag(NotesTags.NEW),
            )
        }
        SnackbarHost(snackbarHost, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun emptyText(state: NotesListUiState): String {
    val tag = state.tag
    return when {
        state.searching -> stringResource(R.string.notes_empty_search, state.query.trim())
        tag != null && state.archived -> stringResource(R.string.notes_empty_archived_tag, tag)
        tag != null -> stringResource(R.string.notes_empty_tag, tag)
        state.archived -> stringResource(R.string.notes_empty_archived)
        else -> stringResource(R.string.notes_empty)
    }
}

@Composable
private fun SearchAndView(
    state: NotesListUiState,
    callbacks: NotesListCallbacks,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.query,
            onValueChange = callbacks.setQuery,
            placeholder = { Text(stringResource(R.string.notes_search)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { callbacks.setQuery("") }, modifier = Modifier.testTag(NotesTags.SEARCH_CLEAR)) {
                        Icon(Icons.Outlined.Clear, contentDescription = stringResource(R.string.notes_search_clear))
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(NotesTags.SEARCH),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !state.archived,
                onClick = { callbacks.showArchived(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                modifier = Modifier.testTag(NotesTags.SHOW_ACTIVE),
            ) { Text(stringResource(R.string.notes_show_active)) }
            SegmentedButton(
                selected = state.archived,
                onClick = { callbacks.showArchived(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                modifier = Modifier.testTag(NotesTags.SHOW_ARCHIVED),
            ) { Text(stringResource(R.string.notes_show_archived)) }
        }
    }
}

@Composable
private fun NotesSnackbarEffect(
    snackbar: NotesSnackbar?,
    host: SnackbarHostState,
    onUndo: (NotesSnackbar.Trashed) -> Unit,
    onDismiss: (NotesSnackbar) -> Unit,
) {
    val resources = LocalResources.current
    LaunchedEffect(snackbar) {
        if (snackbar == null) return@LaunchedEffect
        val result =
            when (snackbar) {
                is NotesSnackbar.Trashed ->
                    host.showSnackbar(
                        message = resources.getString(R.string.notes_trashed, snackbar.note.displayTitle),
                        actionLabel = resources.getString(R.string.notes_undo),
                        duration = SnackbarDuration.Long,
                    )
                is NotesSnackbar.Archived ->
                    host.showSnackbar(
                        message =
                            resources.getString(
                                if (snackbar.note.archived) R.string.notes_archived_done else R.string.notes_unarchived_done,
                                snackbar.note.displayTitle,
                            ),
                        duration = SnackbarDuration.Short,
                    )
                is NotesSnackbar.Failed ->
                    host.showSnackbar(
                        message =
                            resources.getString(
                                when (snackbar.failure) {
                                    NoteChangeFailure.OFFLINE -> R.string.notes_change_offline
                                    NoteChangeFailure.CONFLICT -> R.string.notes_change_conflict
                                    NoteChangeFailure.FAILED -> R.string.notes_change_failed
                                },
                            ),
                        duration = SnackbarDuration.Short,
                    )
            }
        if (result == SnackbarResult.ActionPerformed && snackbar is NotesSnackbar.Trashed) onUndo(snackbar) else onDismiss(snackbar)
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
    busy: Boolean,
    callbacks: NotesListCallbacks,
) {
    Row(
        Modifier.fillMaxWidth().clickable { callbacks.open(note.id) }.testTag(NotesTags.note(note.id)),
        verticalAlignment = Alignment.Top,
    ) {
        NoteSummary(note, Modifier.weight(1f).padding(start = 16.dp, top = 12.dp, bottom = 12.dp))
        RowMenu(note, busy, callbacks)
    }
}

/** Pin, archive and delete of one note in the list. */
@Composable
private fun RowMenu(
    note: Note,
    busy: Boolean,
    callbacks: NotesListCallbacks,
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(top = 4.dp, end = 4.dp)) {
        val actions = stringResource(R.string.notes_actions_for, note.displayTitle)
        IconButton(onClick = { open = true }, enabled = !busy, modifier = Modifier.testTag(NotesTags.menu(note.id))) {
            Icon(Icons.Outlined.MoreVert, contentDescription = actions)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (note.pinned) R.string.notes_unpin else R.string.notes_pin)) },
                onClick = {
                    open = false
                    callbacks.togglePin(note)
                },
                modifier = Modifier.testTag(NotesTags.PIN),
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (note.archived) R.string.notes_unarchive else R.string.notes_archive)) },
                onClick = {
                    open = false
                    callbacks.toggleArchived(note)
                },
                modifier = Modifier.testTag(NotesTags.ARCHIVE),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notes_delete)) },
                onClick = {
                    open = false
                    callbacks.delete(note)
                },
                modifier = Modifier.testTag(NotesTags.DELETE),
            )
        }
    }
}

@Composable
private fun NoteSummary(
    note: Note,
    modifier: Modifier,
) {
    val excerpt = remember(note.bodyMd) { plainExcerpt(note.bodyMd) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
