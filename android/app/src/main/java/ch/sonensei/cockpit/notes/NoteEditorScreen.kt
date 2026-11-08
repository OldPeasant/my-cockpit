package ch.sonensei.cockpit.notes

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.search.SearchHit
import ch.sonensei.cockpit.search.searchTypeStyle
import coil3.ImageLoader
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/** Where a photo comes from. */
enum class PhotoSource { CAMERA, GALLERY }

/** What the editor can ask for, so it can be tested without a view model. */
data class NoteEditorCallbacks(
    val close: () -> Unit = {},
    val setEditing: (Boolean) -> Unit = {},
    val setTitle: (String) -> Unit = {},
    val setBody: (String) -> Unit = {},
    /** The source, and where in the body the image goes (the cursor). */
    val addPhoto: (PhotoSource, Int) -> Unit = { _, _ -> },
    val saveNow: () -> Unit = {},
    val keepMine: () -> Unit = {},
    val useTheirs: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
    val retryLoad: () -> Unit = {},
    val togglePin: () -> Unit = {},
    val toggleArchived: () -> Unit = {},
    val delete: () -> Unit = {},
    /** A checklist item, by its line in the body, was tapped in the rendered note. */
    val toggleChecklist: (Int) -> Unit = {},
    /** The inline link `[[query` being typed changed (`null`: none is typed). */
    val searchLinks: (String?) -> Unit = {},
)

internal object NoteEditorTags {
    const val STATUS = "note-status"
    const val SAVE_RETRY = "note-save-retry"
    const val TOGGLE = "note-toggle"
    const val TITLE = "note-title"
    const val BODY = "note-body"
    const val VIEW = "note-view"
    const val LOADING = "note-loading"
    const val CAMERA = "note-camera"
    const val GALLERY = "note-gallery"
    const val CONFLICT = "note-conflict"
    const val KEEP_MINE = "note-keep-mine"
    const val USE_THEIRS = "note-use-theirs"
    const val MESSAGE = "note-message"
    const val UPLOADING = "note-uploading"
    const val RETRY_LOAD = "note-retry-load"
    const val MENU = "note-menu"
    const val PIN = "note-pin"
    const val ARCHIVE = "note-archive"
    const val DELETE = "note-delete"
    const val LINK_SUGGESTIONS = "note-link-suggestions"
    const val LINK_HIT = "note-link-hit"
}

/**
 * Note [id] (`null`: a new note). [key] picks the editor from [NoteEditors] (each new note gets its own); [opening]
 * identifies this opening of the editor, so a note opened again is loaded again. Leaving (the back arrow or the system
 * back) saves what is not saved yet: [onClose] gets that save, `null` when the editor closes because the note is gone.
 * A note moved to the Trash here goes to [onDeleted] first.
 */
@Composable
fun NoteEditorRoute(
    id: UUID?,
    key: String,
    onClose: (saving: Job?) -> Unit,
    modifier: Modifier = Modifier,
    opening: String = key,
    onDeleted: (Note) -> Unit = {},
    viewModel: NoteEditorViewModel = noteEditor(key),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, opening) { viewModel.open(id, opening) }
    LaunchedEffect(state.closed) {
        if (state.closed) {
            state.deleted?.let(onDeleted)
            onClose(null)
        }
    }
    val close = { onClose(viewModel.saveNow()) }
    BackHandler(onBack = close)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var insertAt by rememberSaveable { mutableStateOf(0) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }

    fun upload(uri: Uri) {
        scope.launch { viewModel.addPhoto(NotePhotoFiles.read(context, uri), insertAt) }
    }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
            val uri = cameraUri?.let(Uri::parse)
            cameraUri = null
            if (taken && uri != null) upload(uri)
        }
    val gallery =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) upload(uri)
        }

    NoteEditorContent(
        state = state,
        imageLoader = viewModel.imageLoader,
        callbacks =
            NoteEditorCallbacks(
                close = close,
                setEditing = viewModel::setEditing,
                setTitle = viewModel::setTitle,
                setBody = viewModel::setBody,
                addPhoto = { source, at ->
                    insertAt = at
                    when (source) {
                        PhotoSource.CAMERA -> {
                            NotePhotoFiles.clear(context)
                            val (_, uri) = NotePhotoFiles.create(context)
                            cameraUri = uri.toString()
                            try {
                                camera.launch(uri)
                            } catch (_: ActivityNotFoundException) {
                                cameraUri = null
                                viewModel.addPhoto(null)
                            }
                        }
                        PhotoSource.GALLERY ->
                            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                },
                saveNow = { viewModel.saveNow() },
                keepMine = viewModel::keepMine,
                useTheirs = viewModel::useTheirs,
                dismissMessage = viewModel::dismissMessage,
                retryLoad = { id?.let(viewModel::retryLoad) },
                togglePin = viewModel::togglePin,
                toggleArchived = viewModel::toggleArchived,
                delete = viewModel::delete,
                toggleChecklist = viewModel::toggleChecklist,
                searchLinks = viewModel::searchLinks,
            ),
        modifier = modifier,
    )
}

/** The editor for [key], kept by the Notes screen's [NoteEditors]. */
@Composable
private fun noteEditor(key: String): NoteEditorViewModel {
    val editors: NoteEditors = hiltViewModel()
    return remember(editors, key) { editors.editor(key) }
}

@Composable
fun NoteEditorContent(
    state: NoteEditorUiState,
    callbacks: NoteEditorCallbacks,
    imageLoader: ImageLoader?,
    modifier: Modifier = Modifier,
) {
    // The body with its cursor; follows the state when the text changes from elsewhere (load, photo, conflict).
    var body by remember { mutableStateOf(TextFieldValue(state.body)) }
    if (body.text != state.body) {
        val cursor = (body.selection.start + state.body.length - body.text.length).coerceIn(0, state.body.length)
        body = TextFieldValue(state.body, TextRange(cursor))
    }

    Column(modifier.fillMaxSize()) {
        EditorBar(state, callbacks, insertAt = body.selection.start)
        state.conflict?.let { ConflictBanner(it, callbacks) }
        state.message?.let { MessageBanner(it, callbacks) }
        if (state.uploading || state.loading) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().testTag(if (state.uploading) NoteEditorTags.UPLOADING else NoteEditorTags.LOADING),
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val content = Modifier.widthIn(max = 680.dp).fillMaxWidth()
            when {
                state.loading -> Unit
                state.editing ->
                    Column(content.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = state.title,
                            onValueChange = callbacks.setTitle,
                            placeholder = { Text(stringResource(R.string.notes_title_hint)) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleLarge,
                            enabled = state.editable,
                            modifier = Modifier.fillMaxWidth().testTag(NoteEditorTags.TITLE),
                        )
                        val query = body.linkQuery()
                        val links = state.links
                        if (query != null && links != null && state.editable) {
                            LinkSuggestionsPanel(links) { hit ->
                                val (text, cursor) = Markdown.insertLink(body.text, query, hit.type.lowercase(), hit.id)
                                body = TextFieldValue(text, TextRange(cursor))
                                callbacks.setBody(text)
                                callbacks.searchLinks(null)
                            }
                        }
                        OutlinedTextField(
                            value = body,
                            onValueChange = {
                                body = it
                                if (it.text != state.body) callbacks.setBody(it.text)
                                callbacks.searchLinks(it.linkQuery()?.query)
                            },
                            placeholder = { Text(stringResource(R.string.notes_body_hint)) },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            enabled = state.editable,
                            modifier = Modifier.fillMaxWidth().weight(1f).testTag(NoteEditorTags.BODY),
                        )
                    }
                state.note == null && state.body.isEmpty() && state.message != null -> Unit
                else ->
                    Column(
                        content
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                            .testTag(NoteEditorTags.VIEW),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        state.title.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.headlineMedium)
                        }
                        if (state.body.isBlank()) {
                            Text(
                                stringResource(R.string.notes_empty_body),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            MarkdownView(
                                state.body,
                                imageLoader,
                                onToggleChecklist = callbacks.toggleChecklist.takeIf { state.editable },
                            )
                        }
                    }
            }
        }
    }
}

/** The inline link `[[query` right before the cursor, if nothing is selected. */
private fun TextFieldValue.linkQuery(): LinkQuery? = if (selection.collapsed) Markdown.linkQuery(text, selection.start) else null

/** Items the inline link being typed may point to; [onPick] inserts the link to one. */
@Composable
private fun LinkSuggestionsPanel(
    links: LinkSuggestions,
    onPick: (SearchHit) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag(NoteEditorTags.LINK_SUGGESTIONS),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            val info =
                when {
                    links.error == LinkSearchError.OFFLINE -> R.string.notes_link_offline
                    links.error == LinkSearchError.FAILED -> R.string.notes_link_failed
                    links.query.isBlank() -> R.string.notes_link_hint
                    links.hits.isEmpty() && links.searching -> R.string.notes_link_searching
                    links.hits.isEmpty() -> R.string.notes_link_none
                    else -> null
                }
            info?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (links.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            links.hits.take(MAX_LINK_HITS).forEachIndexed { index, hit ->
                if (index > 0) HorizontalDivider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(hit) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag(NoteEditorTags.LINK_HIT),
                ) {
                    Text(hit.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(
                        listOfNotNull(stringResource(searchTypeStyle(hit.type).labelRes), hit.subtitle).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private const val MAX_LINK_HITS = 6

@Composable
private fun EditorBar(
    state: NoteEditorUiState,
    callbacks: NoteEditorCallbacks,
    insertAt: Int,
) {
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = callbacks.close) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.notes_back))
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (!state.loading) {
                val text =
                    when (state.status) {
                        SaveStatus.SAVED -> R.string.notes_status_saved
                        SaveStatus.UNSAVED -> R.string.notes_status_unsaved
                        SaveStatus.SAVING -> R.string.notes_status_saving
                        SaveStatus.OFFLINE -> R.string.notes_status_offline
                        SaveStatus.FAILED -> R.string.notes_status_failed
                    }
                val failed = state.status == SaveStatus.OFFLINE || state.status == SaveStatus.FAILED
                Text(
                    stringResource(text),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.weight(1f, fill = false).testTag(NoteEditorTags.STATUS),
                )
                if (failed && state.conflict == null) {
                    TextButton(onClick = callbacks.saveNow, modifier = Modifier.testTag(NoteEditorTags.SAVE_RETRY)) {
                        Text(stringResource(R.string.notes_save_retry))
                    }
                }
            }
        }
        if (state.editable) {
            if (state.uploading) {
                val uploading = stringResource(R.string.notes_uploading)
                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp).semantics { contentDescription = uploading })
            } else {
                IconButton(
                    onClick = { callbacks.addPhoto(PhotoSource.CAMERA, insertAt) },
                    modifier = Modifier.testTag(NoteEditorTags.CAMERA),
                ) {
                    Icon(Icons.Outlined.AddAPhoto, contentDescription = stringResource(R.string.notes_take_photo))
                }
                IconButton(
                    onClick = { callbacks.addPhoto(PhotoSource.GALLERY, insertAt) },
                    modifier = Modifier.testTag(NoteEditorTags.GALLERY),
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = stringResource(R.string.notes_pick_photo))
                }
            }
            TextButton(onClick = { callbacks.setEditing(!state.editing) }, modifier = Modifier.testTag(NoteEditorTags.TOGGLE)) {
                Text(stringResource(if (state.editing) R.string.notes_view else R.string.notes_edit))
            }
            state.note?.let { EditorMenu(it, enabled = !state.changing, callbacks) }
        }
    }
}

/** Pin, archive and delete of a saved note. */
@Composable
private fun EditorMenu(
    note: Note,
    enabled: Boolean,
    callbacks: NoteEditorCallbacks,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag(NoteEditorTags.MENU)) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.notes_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (note.pinned) R.string.notes_unpin else R.string.notes_pin)) },
                onClick = {
                    open = false
                    callbacks.togglePin()
                },
                modifier = Modifier.testTag(NoteEditorTags.PIN),
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (note.archived) R.string.notes_unarchive else R.string.notes_archive)) },
                onClick = {
                    open = false
                    callbacks.toggleArchived()
                },
                modifier = Modifier.testTag(NoteEditorTags.ARCHIVE),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.notes_delete)) },
                onClick = {
                    open = false
                    callbacks.delete()
                },
                modifier = Modifier.testTag(NoteEditorTags.DELETE),
            )
        }
    }
}

@Composable
private fun ConflictBanner(
    conflict: NoteConflict,
    callbacks: NoteEditorCallbacks,
) {
    val deleted = conflict.current == null
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag(NoteEditorTags.CONFLICT)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(if (deleted) R.string.notes_conflict_deleted else R.string.notes_conflict),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = callbacks.keepMine, modifier = Modifier.testTag(NoteEditorTags.KEEP_MINE)) {
                    Text(stringResource(if (deleted) R.string.notes_keep_mine_deleted else R.string.notes_keep_mine))
                }
                OutlinedButton(onClick = callbacks.useTheirs, modifier = Modifier.testTag(NoteEditorTags.USE_THEIRS)) {
                    Text(stringResource(if (deleted) R.string.notes_use_theirs_deleted else R.string.notes_use_theirs))
                }
            }
        }
    }
}

@Composable
private fun MessageBanner(
    message: NoteEditorMessage,
    callbacks: NoteEditorCallbacks,
) {
    val text =
        when (message) {
            NoteEditorMessage.LOAD_OFFLINE -> R.string.notes_load_offline
            NoteEditorMessage.LOAD_FAILED -> R.string.notes_load_failed
            NoteEditorMessage.PHOTO_UNREADABLE -> R.string.notes_photo_unreadable
            NoteEditorMessage.PHOTO_OFFLINE -> R.string.notes_photo_offline
            NoteEditorMessage.PHOTO_FAILED -> R.string.notes_photo_failed
            NoteEditorMessage.CHANGE_OFFLINE -> R.string.notes_change_offline
            NoteEditorMessage.CHANGE_FAILED -> R.string.notes_change_failed
            NoteEditorMessage.CHANGE_CONFLICT -> R.string.notes_change_conflict_editor
        }
    val load = message == NoteEditorMessage.LOAD_OFFLINE || message == NoteEditorMessage.LOAD_FAILED
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag(NoteEditorTags.MESSAGE)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            if (load) {
                TextButton(onClick = callbacks.retryLoad, modifier = Modifier.testTag(NoteEditorTags.RETRY_LOAD)) {
                    Text(stringResource(R.string.notes_retry))
                }
            } else {
                TextButton(onClick = callbacks.dismissMessage) { Text(stringResource(R.string.notes_dismiss)) }
            }
        }
    }
}
