package ch.sonensei.cockpit.files

import android.content.res.Resources
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.core.links.ItemRef
import ch.sonensei.cockpit.core.links.LinkedItemsSection
import ch.sonensei.cockpit.search.SearchItemScreen
import ch.sonensei.cockpit.search.SearchTarget
import ch.sonensei.cockpit.ui.PlaceholderScreen
import coil3.ImageLoader
import coil3.compose.AsyncImage
import dagger.hilt.internal.GeneratedComponentManagerHolder
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * The Files screen. Its view model comes from Hilt, so outside a Hilt activity (the shell's navigation tests,
 * previews) it shows the placeholder instead of failing.
 */
@Composable
fun FilesScreen(modifier: Modifier = Modifier) {
    if (LocalActivity.current is GeneratedComponentManagerHolder) {
        FilesRoute(modifier)
    } else {
        PlaceholderScreen(R.string.nav_files, Icons.Outlined.Folder, modifier)
    }
}

/** The drive with the system pickers: upload picks device files, "save to device" a place to save one. */
@Composable
fun FilesRoute(modifier: Modifier = Modifier) {
    val viewModel: FilesViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Reloaded each time the screen is shown, so files changed on the web meanwhile show up.
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(enabled = state.path.isNotEmpty()) { viewModel.up() }
    // Registered later, so it goes first: back ends a selection before it leaves the folder.
    BackHandler(enabled = state.selecting) { viewModel.clearSelection() }
    // Registered last, so it goes first: back closes a preview before anything else.
    BackHandler(enabled = state.preview != null) { viewModel.closePreview() }
    var linked by rememberSaveable { mutableStateOf<String?>(null) }

    val noApp = stringResource(R.string.files_no_app)
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is FilesEvent.Open ->
                    if (!OpenedFiles.launch(context, OpenedFiles.view(context, event.file, event.mime))) {
                        Toast.makeText(context, noApp, Toast.LENGTH_SHORT).show()
                    }
                is FilesEvent.Saved ->
                    Toast.makeText(context, resources.getString(R.string.files_saved, event.name), Toast.LENGTH_SHORT).show()
                is FilesEvent.Changed ->
                    Toast.makeText(context, changedText(resources, event), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // The view model keeps these callbacks past the activity (rotation): they hold the application, never the activity.
    val app = context.applicationContext
    val resolver = app.contentResolver
    val pickUploads =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) viewModel.uploadPicked { uris.map { describePicked(resolver, it) } }
        }
    // Saveable: the activity may be recreated (rotation, the system reclaiming it) while the "save as" picker is open.
    var saving by rememberSaveable(stateSaver = PendingSaveSaver) { mutableStateOf<DriveEntry.File?>(null) }
    val pickSaveTarget =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
            val file = saving
            saving = null
            if (uri != null && file != null) {
                viewModel.save(file, { openForWriting(resolver, uri) }) { discardDocument(resolver, uri) }
            }
        }

    // A linked item opened from the preview, as "TYPE:id", replaces the files; Back returns to the preview.
    // The holder keeps the files' saved UI state (the open links panel, the PDF page) while the item is shown.
    val filesState = rememberSaveableStateHolder()
    linked?.split(':')?.let { (type, itemId) ->
        BackHandler { linked = null }
        SearchItemScreen(type, UUID.fromString(itemId), onClose = { linked = null }, modifier = modifier)
        return
    }

    filesState.SaveableStateProvider(FILES_STATE_KEY) {
        FilesContent(
            state = state,
            callbacks =
                FilesCallbacks(
                    openFolder = viewModel::openFolder,
                    openFile = { file -> viewModel.open(file) { OpenedFiles.target(app, file.id, file.name) } },
                    preview = { file -> viewModel.preview(file) { PreviewFiles.target(app, it.id) } },
                    previewStep = viewModel::previewStep,
                    closePreview = viewModel::closePreview,
                    retryPreview = viewModel::retryPreview,
                    saveFile = {
                        saving = it
                        pickSaveTarget.launch(it.name)
                    },
                    goTo = viewModel::goTo,
                    upload = { pickUploads.launch(arrayOf("*/*")) },
                    dismissUpload = viewModel::dismissUpload,
                    dismissDownloadMessage = viewModel::dismissDownloadMessage,
                    retry = viewModel::refresh,
                    toggleSelected = viewModel::toggleSelected,
                    clearSelection = viewModel::clearSelection,
                    newFolder = viewModel::startNewFolder,
                    rename = viewModel::startRename,
                    move = viewModel::startMove,
                    delete = viewModel::delete,
                    moveSelected = viewModel::startMoveSelected,
                    deleteSelected = viewModel::deleteSelected,
                    dismissActionMessage = viewModel::dismissActionMessage,
                    saveName = viewModel::saveName,
                    cancelName = viewModel::cancelName,
                    pickerOpen = viewModel::pickerOpen,
                    pickerGoTo = viewModel::pickerGoTo,
                    confirmMove = viewModel::confirmMove,
                    cancelMove = viewModel::cancelMove,
                ),
            modifier = modifier,
            imageLoader = viewModel.imageLoader,
            linksPanel = { file ->
                LinkedItemsSection(ItemRef("FILE", file.id), open = { target ->
                    if (target is SearchTarget.InApp) linked = "${target.type}:${target.id}"
                })
            },
        )
    }
}

private const val FILES_STATE_KEY = "files"

/** The confirmation of a finished change: the name for one entry, the count for several. */
internal fun changedText(
    resources: Resources,
    event: FilesEvent.Changed,
): String {
    val one = event.count == 1
    return when (event.change) {
        FilesChange.CREATED -> resources.getString(R.string.files_created, event.name)
        FilesChange.RENAMED -> resources.getString(R.string.files_renamed, event.name)
        FilesChange.MOVED ->
            if (one) {
                resources.getString(R.string.files_moved, event.name)
            } else {
                resources.getQuantityString(R.plurals.files_moved_many, event.count, event.count)
            }
        FilesChange.TRASHED ->
            if (one) {
                resources.getString(R.string.files_trashed, event.name)
            } else {
                resources.getQuantityString(R.plurals.files_trashed_many, event.count, event.count)
            }
    }
}

/** The file waiting for the "save as" picker's answer, kept as strings in the saved state. */
private val PendingSaveSaver =
    Saver<DriveEntry.File?, List<String>>(
        save = { file -> file?.let { listOf(it.id.toString(), it.name, it.mime, it.size.toString(), it.updatedAt.toString()) } },
        restore = { DriveEntry.File(UUID.fromString(it[0]), it[1], it[2], it[3].toLong(), Instant.parse(it[4])) },
    )

/** What the screen can ask for, so it can be tested without a view model. */
data class FilesCallbacks(
    val openFolder: (DriveEntry.Folder) -> Unit = {},
    /** Opens the file in another app ("Open with"). */
    val openFile: (DriveEntry.File) -> Unit = {},
    /** Shows the file in the app (a tap on its row). */
    val preview: (DriveEntry.File) -> Unit = {},
    /** -1: the previous file of the folder, 1: the next. */
    val previewStep: (Int) -> Unit = {},
    val closePreview: () -> Unit = {},
    val retryPreview: () -> Unit = {},
    val saveFile: (DriveEntry.File) -> Unit = {},
    /** Depth of the path to show: 0 is the drive root. */
    val goTo: (Int) -> Unit = {},
    val upload: () -> Unit = {},
    val dismissUpload: (Long) -> Unit = {},
    val dismissDownloadMessage: () -> Unit = {},
    val retry: () -> Unit = {},
    /** A long press, or a tap while selecting. */
    val toggleSelected: (DriveEntry) -> Unit = {},
    val clearSelection: () -> Unit = {},
    val newFolder: () -> Unit = {},
    val rename: (DriveEntry) -> Unit = {},
    val move: (DriveEntry) -> Unit = {},
    val delete: (DriveEntry) -> Unit = {},
    val moveSelected: () -> Unit = {},
    val deleteSelected: () -> Unit = {},
    val dismissActionMessage: () -> Unit = {},
    val saveName: (String) -> Unit = {},
    val cancelName: () -> Unit = {},
    val pickerOpen: (DriveEntry.Folder) -> Unit = {},
    /** Depth of the picker's path to show: 0 is the drive root. */
    val pickerGoTo: (Int) -> Unit = {},
    val confirmMove: () -> Unit = {},
    val cancelMove: () -> Unit = {},
)

internal object FilesTags {
    const val LIST = "files-list"
    const val EMPTY = "files-empty"
    const val MESSAGE = "files-message"
    const val RETRY = "files-retry"
    const val UPLOAD = "files-upload"
    const val DOWNLOAD_MESSAGE = "files-download-message"
    const val DOWNLOADING = "files-downloading"
    const val OPEN = "files-menu-open"
    const val SAVE = "files-menu-save"
    const val RENAME = "files-menu-rename"
    const val MOVE = "files-menu-move"
    const val DELETE = "files-menu-delete"
    const val NEW_FOLDER = "files-new-folder"
    const val ACTION_MESSAGE = "files-action-message"
    const val SELECTION_BAR = "files-selection"
    const val SELECTION_COUNT = "files-selection-count"
    const val SELECTION_CLEAR = "files-selection-clear"
    const val SELECTION_MOVE = "files-selection-move"
    const val SELECTION_DELETE = "files-selection-delete"
    const val NAME_DIALOG = "files-name-dialog"
    const val NAME_FIELD = "files-name-field"
    const val NAME_ERROR = "files-name-error"
    const val NAME_SAVE = "files-name-save"
    const val NAME_CANCEL = "files-name-cancel"
    const val PICKER = "files-picker"
    const val PICKER_EMPTY = "files-picker-empty"
    const val PICKER_MESSAGE = "files-picker-message"
    const val PICKER_MOVE = "files-picker-move"
    const val PICKER_CANCEL = "files-picker-cancel"

    fun pickerCrumb(depth: Int) = "files-picker-crumb:$depth"

    fun pickerFolder(id: UUID) = "files-picker-folder:$id"

    fun crumb(depth: Int) = "files-crumb:$depth"

    fun entry(id: UUID) = "files-entry:$id"

    fun more(id: UUID) = "files-more:$id"

    fun thumbnail(id: UUID) = "files-thumbnail:$id"

    fun upload(key: Long) = "files-upload:$key"

    fun dismissUpload(key: Long) = "files-upload-dismiss:$key"
}

@Composable
fun FilesContent(
    state: FilesUiState,
    callbacks: FilesCallbacks,
    modifier: Modifier = Modifier,
    /** Loads thumbnails and image previews; `null` shows the file type's icon instead. */
    imageLoader: ImageLoader? = null,
    /** Opens a previewed PDF; tests give fakes, as Robolectric does not render PDFs. */
    pdfOpener: (File) -> PdfPages? = ::openPdf,
    /** The previewed file's linked items; `null` (tests) hides the preview's links toggle. */
    linksPanel: (@Composable (DriveEntry.File) -> Unit)? = null,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.selecting) {
                SelectionBar(state.selected.size, state.working, callbacks)
            } else {
                Breadcrumbs(state.path, callbacks.goTo)
            }
            state.actionMessage?.let {
                Banner(text = it.text(), error = true, tag = FilesTags.ACTION_MESSAGE, icon = Icons.Outlined.ErrorOutline) {
                    IconButton(onClick = callbacks.dismissActionMessage) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.files_dismiss))
                    }
                }
            }
            state.message?.let {
                Banner(
                    text = if (it == FilesMessage.OFFLINE) R.string.files_offline else R.string.files_failed,
                    error = it == FilesMessage.FAILED,
                    tag = FilesTags.MESSAGE,
                ) {
                    TextButton(onClick = callbacks.retry, enabled = !state.loading, modifier = Modifier.testTag(FilesTags.RETRY)) {
                        Text(stringResource(R.string.files_retry))
                    }
                }
            }
            state.downloadMessage?.let {
                Banner(
                    text = if (it == FilesMessage.OFFLINE) R.string.files_download_offline else R.string.files_download_failed,
                    error = it == FilesMessage.FAILED,
                    tag = FilesTags.DOWNLOAD_MESSAGE,
                ) {
                    IconButton(onClick = callbacks.dismissDownloadMessage) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.files_dismiss))
                    }
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.padding(top = 4.dp))
            if (state.uploads.isNotEmpty()) Uploads(state.uploads, callbacks.dismissUpload)
            if (state.entries.isEmpty() && state.loaded) {
                Text(
                    stringResource(R.string.files_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp).testTag(FilesTags.EMPTY),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize().testTag(FilesTags.LIST), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.entries, key = { it.id }) { entry ->
                        // Not selecting: null; selecting: whether this one is picked.
                        val selected = if (state.selecting) entry.id in state.selected else null
                        when (entry) {
                            is DriveEntry.Folder -> FolderRow(entry, selected, state.working, callbacks)
                            is DriveEntry.File -> FileRow(entry, selected, state.downloading, state.working, callbacks, imageLoader)
                        }
                        HorizontalDivider(Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
        if (!state.selecting) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SmallFloatingActionButton(onClick = callbacks.newFolder, modifier = Modifier.testTag(FilesTags.NEW_FOLDER)) {
                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = stringResource(R.string.files_new_folder))
                }
                ExtendedFloatingActionButton(
                    onClick = callbacks.upload,
                    icon = { Icon(Icons.Outlined.Upload, contentDescription = null) },
                    text = { Text(stringResource(R.string.files_upload)) },
                    modifier = Modifier.testTag(FilesTags.UPLOAD),
                )
            }
        }
    }
    state.preview?.let {
        FilePreviewView(
            preview = it,
            downloading = state.downloading == it.file.id,
            busy = state.downloading != null,
            downloadMessage = state.downloadMessage,
            callbacks = callbacks,
            imageLoader = imageLoader,
            pdfOpener = pdfOpener,
            linksPanel = linksPanel,
        )
    }
    state.nameDialog?.let { NameDialogView(it, callbacks) }
    state.movePicker?.let { MovePickerDialog(it, callbacks) }
}

/** Replaces the breadcrumbs while selecting: how many are picked, and what can be done with them. */
@Composable
private fun SelectionBar(
    count: Int,
    working: Boolean,
    callbacks: FilesCallbacks,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().testTag(FilesTags.SELECTION_BAR)) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = callbacks.clearSelection, modifier = Modifier.testTag(FilesTags.SELECTION_CLEAR)) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.files_clear_selection))
            }
            Text(
                pluralStringResource(R.plurals.files_selected, count, count),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 8.dp).testTag(FilesTags.SELECTION_COUNT),
            )
            if (working) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            IconButton(onClick = callbacks.moveSelected, enabled = !working, modifier = Modifier.testTag(FilesTags.SELECTION_MOVE)) {
                Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = stringResource(R.string.files_move))
            }
            IconButton(onClick = callbacks.deleteSelected, enabled = !working, modifier = Modifier.testTag(FilesTags.SELECTION_DELETE)) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.files_delete))
            }
        }
    }
}

private fun ActionMessage.text() =
    when (this) {
        ActionMessage.OFFLINE -> R.string.files_action_offline
        ActionMessage.NAME_TAKEN -> R.string.files_name_taken
        ActionMessage.MOVE_CONFLICT -> R.string.files_action_conflict
        ActionMessage.CHANGED -> R.string.files_action_changed
        ActionMessage.FAILED -> R.string.files_action_failed
    }

private fun NameProblem.text() =
    when (this) {
        NameProblem.EMPTY -> R.string.files_name_empty
        NameProblem.TOO_LONG -> R.string.files_name_too_long
        NameProblem.INVALID -> R.string.files_name_invalid
    }

/** The name of a new folder, or the new name of an entry; the problem shows once something was typed. */
@Composable
private fun NameDialogView(
    dialog: NameDialog,
    callbacks: FilesCallbacks,
) {
    val initial = dialog.entry?.name.orEmpty()
    var name by rememberSaveable(dialog.entry?.id) { mutableStateOf(initial) }
    var edited by rememberSaveable(dialog.entry?.id) { mutableStateOf(false) }
    val problem = nameProblem(name)
    // A rename to the same name changes nothing.
    val unchanged = dialog.entry != null && name.trim() == initial
    val canSave = problem == null && !unchanged && !dialog.saving
    val error = (if (edited) problem?.text() else null) ?: dialog.message?.text()
    AlertDialog(
        onDismissRequest = callbacks.cancelName,
        title = { Text(stringResource(if (dialog.entry == null) R.string.files_new_folder else R.string.files_rename)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    edited = true
                },
                label = { Text(stringResource(if (dialog.entry == null) R.string.files_folder_name else R.string.files_new_name)) },
                singleLine = true,
                isError = error != null,
                enabled = !dialog.saving,
                supportingText = error?.let { { Text(stringResource(it), modifier = Modifier.testTag(FilesTags.NAME_ERROR)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSave) callbacks.saveName(name) }),
                modifier = Modifier.fillMaxWidth().testTag(FilesTags.NAME_FIELD),
            )
        },
        confirmButton = {
            TextButton(onClick = { callbacks.saveName(name) }, enabled = canSave, modifier = Modifier.testTag(FilesTags.NAME_SAVE)) {
                Text(stringResource(if (dialog.entry == null) R.string.files_create else R.string.files_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = callbacks.cancelName, enabled = !dialog.saving, modifier = Modifier.testTag(FilesTags.NAME_CANCEL)) {
                Text(stringResource(R.string.files_cancel))
            }
        },
        modifier = Modifier.testTag(FilesTags.NAME_DIALOG),
    )
}

/** Browses the drive's folders to pick where the entries go; the ones being moved cannot be opened. */
@Composable
private fun MovePickerDialog(
    picker: MovePicker,
    callbacks: FilesCallbacks,
) {
    val title =
        if (picker.entries.size == 1) {
            stringResource(R.string.files_move_title, picker.entries.first().name)
        } else {
            pluralStringResource(R.plurals.files_move_title_many, picker.entries.size, picker.entries.size)
        }
    AlertDialog(
        onDismissRequest = callbacks.cancelMove,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                PathCrumbs(picker.path, enabled = !picker.moving, tag = FilesTags::pickerCrumb, goTo = callbacks.pickerGoTo)
                if (picker.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                picker.message?.let {
                    Text(
                        stringResource(if (it == FilesMessage.OFFLINE) R.string.files_offline else R.string.files_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp).testTag(FilesTags.PICKER_MESSAGE),
                    )
                }
                if (!picker.loading && picker.message == null && picker.folders.isEmpty()) {
                    Text(
                        stringResource(R.string.files_move_no_folders),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp).testTag(FilesTags.PICKER_EMPTY),
                    )
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(picker.folders, key = { it.id }) { folder ->
                        val openable = picker.canOpen(folder)
                        val kind = if (folder.special != null) FileKind.SPECIAL_FOLDER else FileKind.FOLDER
                        val nameColor = if (openable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = openable && !picker.moving) { callbacks.pickerOpen(folder) }
                                .padding(vertical = 8.dp, horizontal = 4.dp)
                                .testTag(FilesTags.pickerFolder(folder.id)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            KindTile(kind, dimmed = !openable)
                            Text(
                                folder.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = nameColor,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                                modifier = Modifier.weight(1f).padding(start = 12.dp),
                            )
                        }
                    }
                }
                picker.moveMessage?.let {
                    Text(
                        stringResource(it.text()),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp).testTag(FilesTags.ACTION_MESSAGE),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = callbacks.confirmMove, enabled = picker.canMove, modifier = Modifier.testTag(FilesTags.PICKER_MOVE)) {
                Text(stringResource(R.string.files_move_here))
            }
        },
        dismissButton = {
            TextButton(onClick = callbacks.cancelMove, enabled = !picker.moving, modifier = Modifier.testTag(FilesTags.PICKER_CANCEL)) {
                Text(stringResource(R.string.files_cancel))
            }
        },
        modifier = Modifier.testTag(FilesTags.PICKER),
    )
}

/** The drive root, then each folder down to the one shown; the last is where you are. */
@Composable
private fun Breadcrumbs(
    path: List<FolderCrumb>,
    goTo: (Int) -> Unit,
) = PathCrumbs(path, enabled = true, tag = FilesTags::crumb, goTo = goTo, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))

@Composable
private fun PathCrumbs(
    path: List<FolderCrumb>,
    enabled: Boolean,
    tag: (Int) -> String,
    goTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val names = listOf(stringResource(R.string.files_root)) + path.map { it.name }
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        names.forEachIndexed { depth, name ->
            val current = depth == names.lastIndex
            if (depth > 0) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            TextButton(onClick = { goTo(depth) }, enabled = enabled && !current, modifier = Modifier.testTag(tag(depth))) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Banner(
    text: Int,
    error: Boolean,
    tag: String,
    icon: ImageVector = Icons.Outlined.CloudOff,
    action: @Composable () -> Unit,
) {
    val color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    Surface(color = color, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            )
            action()
        }
    }
}

@Composable
private fun Uploads(
    uploads: List<UploadItem>,
    dismiss: (Long) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            uploads.forEach { upload ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).testTag(FilesTags.upload(upload.key)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 6.dp).semantics(mergeDescendants = true) {}) {
                        Text(upload.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(upload.state.text()),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (upload.state.isProblem()) colors.error else colors.onSurfaceVariant,
                        )
                    }
                    if (upload.state == UploadState.UPLOADING) {
                        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { dismiss(upload.key) }, modifier = Modifier.testTag(FilesTags.dismissUpload(upload.key))) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.files_dismiss))
                        }
                    }
                }
            }
        }
    }
}

private fun UploadState.text() =
    when (this) {
        UploadState.UPLOADING -> R.string.files_uploading
        UploadState.DONE -> R.string.files_uploaded
        UploadState.OFFLINE -> R.string.files_upload_offline
        UploadState.TOO_LARGE -> R.string.files_upload_too_large
        UploadState.FAILED -> R.string.files_upload_failed
    }

private fun UploadState.isProblem() = this != UploadState.UPLOADING && this != UploadState.DONE

@Composable
private fun FolderRow(
    folder: DriveEntry.Folder,
    selected: Boolean?,
    working: Boolean,
    callbacks: FilesCallbacks,
) {
    // The auto-managed folders cannot be renamed, moved or deleted: no menu, and no selection.
    val special = folder.special != null
    EntryRow(
        entry = folder,
        kind = if (special) FileKind.SPECIAL_FOLDER else FileKind.FOLDER,
        subtitle = if (special) stringResource(R.string.files_special) else null,
        selected = selected,
        onClick = { callbacks.openFolder(folder) },
        callbacks = callbacks,
        selectable = !special,
    ) {
        if (!special && selected == null) EntryMenu(folder, working, callbacks) {}
    }
}

@Composable
private fun FileRow(
    file: DriveEntry.File,
    selected: Boolean?,
    downloading: UUID?,
    working: Boolean,
    callbacks: FilesCallbacks,
    imageLoader: ImageLoader?,
) {
    // One download at a time: while one runs, no file can be opened or saved (the "save as" picker would create a document).
    val idle = downloading == null
    EntryRow(
        entry = file,
        kind = fileKind(file.mime),
        subtitle = "${formatSize(file.size)}, ${formatInstant(file.updatedAt)}",
        selected = selected,
        onClick = { callbacks.preview(file) },
        callbacks = callbacks,
        enabled = idle || selected != null,
        thumbnail = if (imageLoader != null && previewKind(file.mime) == PreviewKind.IMAGE) thumbnailUrl(file.id) else null,
        imageLoader = imageLoader,
    ) {
        if (downloading == file.id) {
            CircularProgressIndicator(
                Modifier.padding(12.dp).size(24.dp).testTag(FilesTags.DOWNLOADING),
                strokeWidth = 2.dp,
            )
        } else if (selected == null) {
            EntryMenu(file, working, callbacks) { close ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_open)) },
                    onClick = {
                        close()
                        callbacks.openFile(file)
                    },
                    enabled = idle,
                    modifier = Modifier.testTag(FilesTags.OPEN),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_save)) },
                    onClick = {
                        close()
                        callbacks.saveFile(file)
                    },
                    enabled = idle,
                    modifier = Modifier.testTag(FilesTags.SAVE),
                )
            }
        }
    }
}

/** The "more" menu of an entry: [first] (open, save) and then rename, move and delete. */
@Composable
private fun EntryMenu(
    entry: DriveEntry,
    working: Boolean,
    callbacks: FilesCallbacks,
    first: @Composable (close: () -> Unit) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val close = { menu = false }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag(FilesTags.more(entry.id))) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.files_more, entry.name))
        }
        DropdownMenu(expanded = menu, onDismissRequest = close) {
            first(close)
            MenuAction(R.string.files_rename, Icons.Outlined.Edit, FilesTags.RENAME, enabled = !working) {
                close()
                callbacks.rename(entry)
            }
            MenuAction(R.string.files_move, Icons.AutoMirrored.Outlined.DriveFileMove, FilesTags.MOVE, enabled = !working) {
                close()
                callbacks.move(entry)
            }
            MenuAction(R.string.files_delete, Icons.Outlined.Delete, FilesTags.DELETE, enabled = !working) {
                close()
                callbacks.delete(entry)
            }
        }
    }
}

@Composable
private fun MenuAction(
    text: Int,
    icon: ImageVector,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) = DropdownMenuItem(
    text = { Text(stringResource(text)) },
    leadingIcon = { Icon(icon, contentDescription = null) },
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.testTag(tag),
)

/**
 * A row of the list. A long press starts selecting (or adds to it); while selecting ([selected] not `null`), a tap
 * picks or unpicks the row instead of opening it.
 */
@Composable
private fun EntryRow(
    entry: DriveEntry,
    kind: FileKind,
    subtitle: String?,
    selected: Boolean?,
    onClick: () -> Unit,
    callbacks: FilesCallbacks,
    enabled: Boolean = true,
    selectable: Boolean = true,
    /** Where the server's thumbnail is loaded from, drawn over the type tile once it arrives. */
    thumbnail: String? = null,
    imageLoader: ImageLoader? = null,
    trailing: @Composable () -> Unit = {},
) {
    val selecting = selected != null
    val toggle = { callbacks.toggleSelected(entry) }
    val background = if (selected == true) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .combinedClickable(
                enabled = enabled && (!selecting || selectable),
                onClick = if (selecting) toggle else onClick,
                onLongClick = if (selectable) toggle else null,
            ).semantics { if (selecting) this.selected = selected == true }
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
            .testTag(FilesTags.entry(entry.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected == true) {
            SelectedTile()
        } else {
            Box {
                KindTile(kind, dimmed = selecting && !selectable)
                // Transparent until it loads; when it fails, the tile stays.
                if (thumbnail != null && imageLoader != null) {
                    AsyncImage(
                        model = thumbnail,
                        contentDescription = null,
                        imageLoader = imageLoader,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).testTag(FilesTags.thumbnail(entry.id)),
                    )
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Keeps the row's height while selecting hides the menu.
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { trailing() }
    }
}

/** What a row's tile shows: folders and the main file families each get their own icon and tone. */
internal enum class FileKind { FOLDER, SPECIAL_FOLDER, IMAGE, PDF, AUDIO, VIDEO, TEXT, OTHER }

internal fun fileKind(mime: String): FileKind {
    val type = mime.substringBefore(';').trim().lowercase()
    return when {
        type.startsWith("image/") -> FileKind.IMAGE
        type == "application/pdf" -> FileKind.PDF
        type.startsWith("audio/") -> FileKind.AUDIO
        type.startsWith("video/") -> FileKind.VIDEO
        type.startsWith("text/") || type in TEXT_TYPES -> FileKind.TEXT
        else -> FileKind.OTHER
    }
}

private val TEXT_TYPES = setOf("application/json", "application/xml", "application/yaml", "application/x-yaml")

/** A rounded tonal square behind the icon, tinted by kind, so a folder can be scanned by shape and color. */
@Composable
internal fun KindTile(
    kind: FileKind,
    dimmed: Boolean = false,
    size: Dp = 40.dp,
) {
    val colors = MaterialTheme.colorScheme
    val (icon: ImageVector, pair: Pair<Color, Color>) =
        when (kind) {
            FileKind.FOLDER -> Icons.Outlined.Folder to (colors.primaryContainer to colors.onPrimaryContainer)
            FileKind.SPECIAL_FOLDER -> Icons.Outlined.FolderSpecial to (colors.primaryContainer to colors.onPrimaryContainer)
            FileKind.IMAGE -> Icons.Outlined.Image to (colors.tertiaryContainer to colors.onTertiaryContainer)
            FileKind.PDF -> Icons.Outlined.PictureAsPdf to (colors.errorContainer to colors.onErrorContainer)
            FileKind.AUDIO -> Icons.Outlined.AudioFile to (colors.secondaryContainer to colors.onSecondaryContainer)
            FileKind.VIDEO -> Icons.Outlined.VideoFile to (colors.secondaryContainer to colors.onSecondaryContainer)
            FileKind.TEXT -> Icons.Outlined.Description to (colors.surfaceContainerHighest to colors.onSurfaceVariant)
            FileKind.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile to (colors.surfaceContainerHighest to colors.onSurfaceVariant)
        }
    Box(
        Modifier
            .size(size)
            .alpha(if (dimmed) DIMMED else 1f)
            .clip(RoundedCornerShape(size * TILE_CORNER))
            .background(pair.first),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = pair.second, modifier = Modifier.size(size * TILE_ICON))
    }
}

private const val DIMMED = 0.38f

/** Corner radius and icon size relative to the tile: 12 dp and 22 dp on the list's 40 dp tile. */
private const val TILE_CORNER = 0.3f
private const val TILE_ICON = 0.55f

/** A picked row's tile: a filled check, so the selection reads at a glance. */
@Composable
private fun SelectedTile() {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
    }
}

private fun formatInstant(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(instant.atZone(ZoneId.systemDefault()))
