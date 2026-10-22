package ch.sonensei.cockpit.journal

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.notes.MarkdownView
import ch.sonensei.cockpit.notes.NotePhotoFiles
import ch.sonensei.cockpit.notes.PhotoSource
import ch.sonensei.cockpit.notes.SaveStatus
import ch.sonensei.cockpit.ui.PlaceholderScreen
import coil3.ImageLoader
import dagger.hilt.internal.GeneratedComponentManagerHolder
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the journal can ask for, so it can be tested without a view model. */
data class JournalCallbacks(
    val setEditing: (Boolean) -> Unit = {},
    val setBody: (String) -> Unit = {},
    /** The source, and where in the body the image goes (the cursor). */
    val addPhoto: (PhotoSource, Int) -> Unit = { _, _ -> },
    val saveNow: () -> Unit = {},
    val keepMine: () -> Unit = {},
    val useTheirs: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
    val retryLoad: () -> Unit = {},
)

internal object JournalTags {
    const val DATE = "journal-date"
    const val STATUS = "journal-status"
    const val SAVE_RETRY = "journal-save-retry"
    const val TOGGLE = "journal-toggle"
    const val BODY = "journal-body"
    const val VIEW = "journal-view"
    const val LOADING = "journal-loading"
    const val CAMERA = "journal-camera"
    const val GALLERY = "journal-gallery"
    const val CONFLICT = "journal-conflict"
    const val KEEP_MINE = "journal-keep-mine"
    const val USE_THEIRS = "journal-use-theirs"
    const val MESSAGE = "journal-message"
    const val UPLOADING = "journal-uploading"
    const val RETRY_LOAD = "journal-retry-load"
}

/**
 * The Journal screen: today's entry. Its view model comes from Hilt, so outside a Hilt activity (the shell's
 * navigation tests, previews) it shows the placeholder instead of failing.
 */
@Composable
fun JournalScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        JournalRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_journal, Icons.AutoMirrored.Outlined.MenuBook, modifier)
    }
}

/** Today's entry, with photos from the camera or the gallery. */
@Composable
fun JournalRoute(
    modifier: Modifier = Modifier,
    viewModel: JournalViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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

    JournalContent(
        state = state,
        imageLoader = viewModel.imageLoader,
        callbacks =
            JournalCallbacks(
                setEditing = viewModel::setEditing,
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
                retryLoad = viewModel::retryLoad,
            ),
        modifier = modifier,
    )
}

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

@Composable
fun JournalContent(
    state: JournalUiState,
    callbacks: JournalCallbacks,
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
        JournalBar(state, callbacks, insertAt = body.selection.start)
        state.conflict?.let { ConflictBanner(it, callbacks) }
        state.message?.let { MessageBanner(it, callbacks) }
        if (state.uploading || state.loading) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().testTag(if (state.uploading) JournalTags.UPLOADING else JournalTags.LOADING),
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val content = Modifier.widthIn(max = 680.dp).fillMaxWidth()
            Column(
                content.fillMaxHeight().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    state.date.format(dateFormat),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() }.testTag(JournalTags.DATE),
                )
                when {
                    state.loading || state.unloaded -> Unit
                    state.editing ->
                        OutlinedTextField(
                            value = body,
                            onValueChange = {
                                body = it
                                if (it.text != state.body) callbacks.setBody(it.text)
                            },
                            placeholder = { Text(stringResource(R.string.journal_body_hint)) },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            enabled = state.editable,
                            modifier = Modifier.fillMaxWidth().weight(1f).testTag(JournalTags.BODY),
                        )
                    else ->
                        Column(Modifier.verticalScroll(rememberScrollState()).testTag(JournalTags.VIEW)) {
                            if (state.body.isBlank()) {
                                Text(
                                    stringResource(R.string.journal_empty_body),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                MarkdownView(state.body, imageLoader)
                            }
                        }
                }
            }
        }
    }
}

@Composable
private fun JournalBar(
    state: JournalUiState,
    callbacks: JournalCallbacks,
    insertAt: Int,
) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (!state.loading && !state.unloaded) {
                val text =
                    when (state.status) {
                        SaveStatus.SAVED -> R.string.journal_status_saved
                        SaveStatus.UNSAVED -> R.string.journal_status_unsaved
                        SaveStatus.SAVING -> R.string.journal_status_saving
                        SaveStatus.OFFLINE -> R.string.journal_status_offline
                        SaveStatus.FAILED -> R.string.journal_status_failed
                    }
                val failed = state.status == SaveStatus.OFFLINE || state.status == SaveStatus.FAILED
                Text(
                    stringResource(text),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.weight(1f, fill = false).testTag(JournalTags.STATUS),
                )
                if (failed && state.conflict == null) {
                    TextButton(onClick = callbacks.saveNow, modifier = Modifier.testTag(JournalTags.SAVE_RETRY)) {
                        Text(stringResource(R.string.journal_save_retry))
                    }
                }
            }
        }
        if (state.editable) {
            if (state.uploading) {
                val uploading = stringResource(R.string.journal_uploading)
                CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp).semantics { contentDescription = uploading })
            } else {
                IconButton(
                    onClick = { callbacks.addPhoto(PhotoSource.CAMERA, insertAt) },
                    modifier = Modifier.testTag(JournalTags.CAMERA),
                ) {
                    Icon(Icons.Outlined.AddAPhoto, contentDescription = stringResource(R.string.journal_take_photo))
                }
                IconButton(
                    onClick = { callbacks.addPhoto(PhotoSource.GALLERY, insertAt) },
                    modifier = Modifier.testTag(JournalTags.GALLERY),
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = stringResource(R.string.journal_pick_photo))
                }
            }
            TextButton(onClick = { callbacks.setEditing(!state.editing) }, modifier = Modifier.testTag(JournalTags.TOGGLE)) {
                Text(stringResource(if (state.editing) R.string.journal_view else R.string.journal_edit))
            }
        }
    }
}

@Composable
private fun ConflictBanner(
    conflict: JournalConflict,
    callbacks: JournalCallbacks,
) {
    val deleted = conflict.current == null
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag(JournalTags.CONFLICT)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(if (deleted) R.string.journal_conflict_deleted else R.string.journal_conflict),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = callbacks.keepMine, modifier = Modifier.testTag(JournalTags.KEEP_MINE)) {
                    Text(stringResource(if (deleted) R.string.journal_keep_mine_deleted else R.string.journal_keep_mine))
                }
                OutlinedButton(onClick = callbacks.useTheirs, modifier = Modifier.testTag(JournalTags.USE_THEIRS)) {
                    Text(stringResource(if (deleted) R.string.journal_use_theirs_deleted else R.string.journal_use_theirs))
                }
            }
        }
    }
}

@Composable
private fun MessageBanner(
    message: JournalMessage,
    callbacks: JournalCallbacks,
) {
    val text =
        when (message) {
            JournalMessage.LOAD_OFFLINE -> R.string.journal_load_offline
            JournalMessage.LOAD_FAILED -> R.string.journal_load_failed
            JournalMessage.PHOTO_UNREADABLE -> R.string.journal_photo_unreadable
            JournalMessage.PHOTO_OFFLINE -> R.string.journal_photo_offline
            JournalMessage.PHOTO_FAILED -> R.string.journal_photo_failed
        }
    val load = message == JournalMessage.LOAD_OFFLINE || message == JournalMessage.LOAD_FAILED
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().testTag(JournalTags.MESSAGE)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            if (load) {
                TextButton(onClick = callbacks.retryLoad, modifier = Modifier.testTag(JournalTags.RETRY_LOAD)) {
                    Text(stringResource(R.string.journal_retry))
                }
            } else {
                TextButton(onClick = callbacks.dismissMessage) { Text(stringResource(R.string.journal_dismiss)) }
            }
        }
    }
}
