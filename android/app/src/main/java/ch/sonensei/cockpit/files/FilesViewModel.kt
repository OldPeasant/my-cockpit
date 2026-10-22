package ch.sonensei.cockpit.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject

/** Why what is shown may be outdated or missing, or why a download did not work. */
enum class FilesMessage { OFFLINE, FAILED }

/** Where an upload stands. */
enum class UploadState { UPLOADING, DONE, OFFLINE, TOO_LARGE, FAILED }

/** One file picked for upload, shown until dismissed. */
data class UploadItem(
    val key: Long,
    val name: String,
    val state: UploadState,
)

data class FilesUiState(
    /** From the drive root down to the folder shown; empty at the root. */
    val path: List<FolderCrumb> = emptyList(),
    val entries: List<DriveEntry> = emptyList(),
    val loading: Boolean = false,
    /** The contents of the folder shown arrived. */
    val loaded: Boolean = false,
    val message: FilesMessage? = null,
    /** The file being downloaded to open or save it. */
    val downloading: UUID? = null,
    /** Why the last download did not work. */
    val downloadMessage: FilesMessage? = null,
    val uploads: List<UploadItem> = emptyList(),
)

/** What the screen does once a download is complete. */
sealed interface FilesEvent {
    /** Open [file], downloaded from the drive, in another app. */
    data class Open(
        val file: File,
        val mime: String,
    ) : FilesEvent

    /** [name] was saved where the user chose. */
    data class Saved(
        val name: String,
    ) : FilesEvent
}

/**
 * The drive (SPEC.md §8.2 on the phone, §9.2 item 7): browse folders, open or save files, upload from the device.
 * Device files (the cache, picked files) are touched on [io], never on the main thread.
 */
@HiltViewModel
class FilesViewModel internal constructor(
    private val files: FilesRepository,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    @Inject
    constructor(files: FilesRepository) : this(files, Dispatchers.IO)

    private val state = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = state.asStateFlow()

    private val eventChannel = Channel<FilesEvent>(Channel.BUFFERED)
    val events: Flow<FilesEvent> = eventChannel.receiveAsFlow()

    private var loadJob: Job? = null
    private var downloadJob: Job? = null
    private var nextUploadKey = 0L

    /** The folder shown; `null` at the drive root. */
    private fun folderId(): UUID? =
        state.value.path
            .lastOrNull()
            ?.id

    /** Loads the folder shown again; a load still running is dropped. */
    fun refresh() {
        loadJob?.cancel()
        val folder = folderId()
        state.update { it.copy(loading = true) }
        loadJob =
            viewModelScope.launch {
                val result = files.list(folder)
                state.update {
                    it.copy(
                        entries = (result as? FilesResult.Ok)?.value ?: it.entries,
                        loading = false,
                        loaded = it.loaded || result is FilesResult.Ok,
                        message = result.message(),
                    )
                }
            }
    }

    fun openFolder(folder: DriveEntry.Folder) = show(state.value.path + FolderCrumb(folder.id, folder.name))

    /** Shows the folder at [depth] of the path: 0 is the drive root. */
    fun goTo(depth: Int) {
        if (depth < state.value.path.size) show(state.value.path.take(depth.coerceAtLeast(0)))
    }

    /** Goes to the parent folder; `false` at the drive root. */
    fun up(): Boolean {
        val path = state.value.path
        if (path.isEmpty()) return false
        show(path.dropLast(1))
        return true
    }

    private fun show(path: List<FolderCrumb>) {
        // Nothing of the new folder has arrived yet: neither the old entries nor its "empty" text may show.
        state.update { it.copy(path = path, entries = emptyList(), loaded = false, message = null) }
        refresh()
    }

    /**
     * Downloads [file] into the cache file [target] chooses, then asks the screen to open it. [target] runs only once
     * this download starts (it deletes the files opened earlier), and a failed download deletes what it wrote.
     */
    fun open(
        file: DriveEntry.File,
        target: () -> File,
    ) {
        var cache: File? = null
        download(
            file,
            prepare = { cache = target() },
            out = { cache?.let(::openCache) },
            discard = { cache?.delete() },
        ) { FilesEvent.Open(checkNotNull(cache), file.mime) }
    }

    /** Downloads [file] into the document [target] opens (the user picked where); a failed download calls [discard]. */
    fun save(
        file: DriveEntry.File,
        target: () -> OutputStream?,
        discard: () -> Unit = {},
    ) = download(file, prepare = {}, out = target, discard = discard) { FilesEvent.Saved(file.name) }

    private fun download(
        file: DriveEntry.File,
        prepare: () -> Unit,
        out: () -> OutputStream?,
        discard: () -> Unit,
        done: () -> FilesEvent,
    ) {
        // One at a time: a second tap while a file is downloading is ignored, before it touches any file.
        if (downloadJob?.isActive == true) return
        state.update { it.copy(downloading = file.id, downloadMessage = null) }
        downloadJob =
            viewModelScope.launch {
                var ok = false
                try {
                    prepare()
                    val result = files.download(file.id, out)
                    ok = result is FilesResult.Ok
                    state.update { it.copy(downloading = null, downloadMessage = result.message()) }
                    if (ok) eventChannel.send(done())
                } finally {
                    // Failed or cancelled: an empty or cut-off file must not look like a copy.
                    if (!ok) withContext(NonCancellable + io) { runCatching { discard() } }
                }
            }
    }

    fun dismissDownloadMessage() = state.update { it.copy(downloadMessage = null) }

    /** Uploads [sources] one after the other into the folder shown now, which is reloaded if still shown. */
    fun upload(sources: List<UploadSource>) = upload(folderId(), sources)

    /** Like [upload], for files picked on the device: [describe] asks their providers for names and sizes, off the main thread. */
    fun uploadPicked(describe: () -> List<UploadSource>) {
        val folder = folderId()
        viewModelScope.launch { upload(folder, withContext(io) { describe() }) }
    }

    private fun upload(
        folder: UUID?,
        sources: List<UploadSource>,
    ) {
        if (sources.isEmpty()) return
        val items = sources.map { UploadItem(nextUploadKey++, it.name, UploadState.UPLOADING) }
        state.update { it.copy(uploads = it.uploads + items) }
        viewModelScope.launch {
            sources.zip(items).forEach { (source, item) ->
                val result = files.upload(folder, source)
                setUpload(item.key, result.uploadState())
            }
            if (folderId() == folder) refresh()
        }
    }

    /** Hides the finished upload [key]. */
    fun dismissUpload(key: Long) =
        state.update { s -> s.copy(uploads = s.uploads.filterNot { it.key == key && it.state != UploadState.UPLOADING }) }

    private fun setUpload(
        key: Long,
        upload: UploadState,
    ) = state.update { s -> s.copy(uploads = s.uploads.map { if (it.key == key) it.copy(state = upload) else it }) }
}

private fun openCache(file: File): OutputStream? =
    try {
        FileOutputStream(file)
    } catch (_: IOException) {
        null
    }

private fun FilesResult<*>.message() =
    when (this) {
        is FilesResult.Ok -> null
        FilesResult.Offline -> FilesMessage.OFFLINE
        is FilesResult.Failed -> FilesMessage.FAILED
    }

private const val TOO_LARGE = 413

private fun FilesResult<*>.uploadState() =
    when (this) {
        is FilesResult.Ok -> UploadState.DONE
        FilesResult.Offline -> UploadState.OFFLINE
        is FilesResult.Failed -> if (status == TOO_LARGE) UploadState.TOO_LARGE else UploadState.FAILED
    }
