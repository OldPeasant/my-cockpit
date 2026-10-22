package ch.sonensei.cockpit.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.sonensei.cockpit.R
import ch.sonensei.cockpit.ui.PlaceholderScreen
import dagger.hilt.internal.GeneratedComponentManagerHolder
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

    FilesContent(
        state = state,
        callbacks =
            FilesCallbacks(
                openFolder = viewModel::openFolder,
                openFile = { file -> viewModel.open(file) { OpenedFiles.target(app, file.id, file.name) } },
                saveFile = {
                    saving = it
                    pickSaveTarget.launch(it.name)
                },
                goTo = viewModel::goTo,
                upload = { pickUploads.launch(arrayOf("*/*")) },
                dismissUpload = viewModel::dismissUpload,
                dismissDownloadMessage = viewModel::dismissDownloadMessage,
                retry = viewModel::refresh,
            ),
        modifier = modifier,
    )
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
    val openFile: (DriveEntry.File) -> Unit = {},
    val saveFile: (DriveEntry.File) -> Unit = {},
    /** Depth of the path to show: 0 is the drive root. */
    val goTo: (Int) -> Unit = {},
    val upload: () -> Unit = {},
    val dismissUpload: (Long) -> Unit = {},
    val dismissDownloadMessage: () -> Unit = {},
    val retry: () -> Unit = {},
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

    fun crumb(depth: Int) = "files-crumb:$depth"

    fun entry(id: UUID) = "files-entry:$id"

    fun more(id: UUID) = "files-more:$id"

    fun upload(key: Long) = "files-upload:$key"

    fun dismissUpload(key: Long) = "files-upload-dismiss:$key"
}

@Composable
fun FilesContent(
    state: FilesUiState,
    callbacks: FilesCallbacks,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Breadcrumbs(state.path, callbacks.goTo)
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
                        when (entry) {
                            is DriveEntry.Folder -> FolderRow(entry) { callbacks.openFolder(entry) }
                            is DriveEntry.File -> FileRow(entry, state.downloading, callbacks)
                        }
                        HorizontalDivider(Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = callbacks.upload,
            icon = { Icon(Icons.Outlined.Upload, contentDescription = null) },
            text = { Text(stringResource(R.string.files_upload)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag(FilesTags.UPLOAD),
        )
    }
}

/** The drive root, then each folder down to the one shown; the last is where you are. */
@Composable
private fun Breadcrumbs(
    path: List<FolderCrumb>,
    goTo: (Int) -> Unit,
) {
    val names = listOf(stringResource(R.string.files_root)) + path.map { it.name }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
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
            TextButton(onClick = { goTo(depth) }, enabled = !current, modifier = Modifier.testTag(FilesTags.crumb(depth))) {
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
    action: @Composable () -> Unit,
) {
    val color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    Surface(color = color, modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null)
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
    onClick: () -> Unit,
) {
    EntryRow(
        tag = FilesTags.entry(folder.id),
        kind = if (folder.special != null) FileKind.SPECIAL_FOLDER else FileKind.FOLDER,
        title = folder.name,
        subtitle = folder.special?.let { stringResource(R.string.files_special) },
        onClick = onClick,
    )
}

@Composable
private fun FileRow(
    file: DriveEntry.File,
    downloading: UUID?,
    callbacks: FilesCallbacks,
) {
    var menu by remember { mutableStateOf(false) }
    // One download at a time: while one runs, no file can be opened or saved (the "save as" picker would create a document).
    val idle = downloading == null
    EntryRow(
        tag = FilesTags.entry(file.id),
        kind = fileKind(file.mime),
        title = file.name,
        subtitle = "${formatSize(file.size)}, ${formatInstant(file.updatedAt)}",
        onClick = { callbacks.openFile(file) },
        enabled = idle,
    ) {
        if (downloading == file.id) {
            CircularProgressIndicator(
                Modifier.padding(12.dp).size(24.dp).testTag(FilesTags.DOWNLOADING),
                strokeWidth = 2.dp,
            )
        } else {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag(FilesTags.more(file.id))) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.files_more, file.name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_open)) },
                        onClick = {
                            menu = false
                            callbacks.openFile(file)
                        },
                        enabled = idle,
                        modifier = Modifier.testTag(FilesTags.OPEN),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_save)) },
                        onClick = {
                            menu = false
                            callbacks.saveFile(file)
                        },
                        enabled = idle,
                        modifier = Modifier.testTag(FilesTags.SAVE),
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    tag: String,
    kind: FileKind,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindTile(kind)
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
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
        trailing()
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
private fun KindTile(kind: FileKind) {
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
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(pair.first),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = pair.second, modifier = Modifier.size(22.dp))
    }
}

private fun formatInstant(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(instant.atZone(ZoneId.systemDefault()))
