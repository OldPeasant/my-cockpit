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
    /** Entries picked by a long press, to move or delete together; empty when not selecting. */
    val selected: Set<UUID> = emptySet(),
    /** A move or delete started from the list is running. */
    val working: Boolean = false,
    /** Why the last move or delete did not (fully) work. */
    val actionMessage: ActionMessage? = null,
    /** The "new folder" or "rename" dialog, when open. */
    val nameDialog: NameDialog? = null,
    /** The folder picker of a move, when open. */
    val movePicker: MovePicker? = null,
) {
    val selecting: Boolean get() = selected.isNotEmpty()
}

/** Why a change of the drive did not work. */
enum class ActionMessage {
    OFFLINE,

    /** An entry with that name is already in the folder. */
    NAME_TAKEN,

    /** The target already holds an entry of that name, or a folder would go into itself. */
    MOVE_CONFLICT,

    /** It was changed elsewhere meanwhile; the folder was reloaded. */
    CHANGED,
    FAILED,
}

/** Asks for a name: of a new folder in the folder shown ([entry] `null`), or the new name of [entry]. */
data class NameDialog(
    val entry: DriveEntry? = null,
    val saving: Boolean = false,
    val message: ActionMessage? = null,
)

/** Picks the folder to move [entries] into, browsing from the folder they are in ([origin]). */
data class MovePicker(
    val entries: List<DriveEntry>,
    val origin: List<FolderCrumb>,
    /** The folder browsed, from the drive root; moving puts the entries here. */
    val path: List<FolderCrumb>,
    val folders: List<DriveEntry.Folder> = emptyList(),
    val loading: Boolean = false,
    val message: FilesMessage? = null,
    val moving: Boolean = false,
    val moveMessage: ActionMessage? = null,
) {
    /** A folder cannot go into itself, so the folders being moved cannot be opened. */
    fun canOpen(folder: DriveEntry.Folder) = entries.none { it.id == folder.id }

    /** Moving into the folder they are already in changes nothing. */
    val canMove: Boolean get() = !moving && path.map { it.id } != origin.map { it.id }
}

/** What a finished change did, for a short confirmation. */
enum class FilesChange { CREATED, RENAMED, MOVED, TRASHED }

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

    /** [change] was done to [count] entries; [name] is the (new) name of the first. */
    data class Changed(
        val change: FilesChange,
        val count: Int,
        val name: String,
    ) : FilesEvent
}

/**
 * The drive (SPEC.md §8.2 on the phone, §9.2 item 7): browse folders, open or save files, upload from the device, and
 * create folders, rename, move or delete (to the Trash) entries, one or a long-press selection at a time.
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
    private var pickerJob: Job? = null
    private var nextUploadKey = 0L

    /**
     * A change was refused because the version sent was outdated: the next load that arrives gives the open dialog or
     * picker the reloaded versions, so trying again from there can work.
     */
    private var adoptVersions = false

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
                val fresh = (result as? FilesResult.Ok)?.value
                val adopt = fresh != null && adoptVersions
                if (fresh != null) adoptVersions = false
                state.update {
                    val entries = fresh ?: it.entries
                    val loadedState =
                        it.copy(
                            entries = entries,
                            // What is gone (moved or deleted elsewhere) cannot stay selected.
                            selected = it.selected.filterTo(mutableSetOf()) { id -> entries.any { e -> e.id == id } },
                            loading = false,
                            loaded = it.loaded || result is FilesResult.Ok,
                            message = result.message(),
                        )
                    if (adopt) loadedState.withVersionsOf(entries, folder) else loadedState
                }
            }
    }

    /** Reloads the folder shown after a change was refused because it was changed elsewhere (see [adoptVersions]). */
    private fun reloadStale() {
        adoptVersions = true
        refresh()
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
        state.update { it.copy(path = path, entries = emptyList(), loaded = false, message = null, selected = emptySet()) }
        refresh()
    }

    /** Adds [entry] to the selection or takes it out; the auto-managed folders cannot be changed, so not selected. */
    fun toggleSelected(entry: DriveEntry) {
        if (entry is DriveEntry.Folder && entry.special != null) return
        state.update { it.copy(selected = if (entry.id in it.selected) it.selected - entry.id else it.selected + entry.id) }
    }

    fun clearSelection() = state.update { it.copy(selected = emptySet()) }

    fun dismissActionMessage() = state.update { it.copy(actionMessage = null) }

    private fun selectedEntries(): List<DriveEntry> = state.value.let { s -> s.entries.filter { it.id in s.selected } }

    fun startNewFolder() = state.update { it.copy(nameDialog = NameDialog()) }

    fun startRename(entry: DriveEntry) = state.update { it.copy(nameDialog = NameDialog(entry)) }

    fun cancelName() = state.update { if (it.nameDialog?.saving == true) it else it.copy(nameDialog = null) }

    /** Creates the folder or renames the entry of the open dialog; it stays open, with the reason, when that fails. */
    fun saveName(name: String) {
        val dialog = state.value.nameDialog ?: return
        if (dialog.saving || nameProblem(name) != null) return
        val folder = folderId()
        state.update { it.copy(nameDialog = dialog.copy(saving = true, message = null)) }
        viewModelScope.launch {
            val entry = dialog.entry
            val result = if (entry == null) files.createFolder(folder, name) else files.rename(entry, name)
            // Folder names are unique in their folder, file names need not be: a 409 on a file is a concurrent change.
            val message = result.actionMessage(conflict = if (entry is DriveEntry.File) ActionMessage.CHANGED else ActionMessage.NAME_TAKEN)
            if (result is FilesResult.Ok) {
                state.update { it.copy(nameDialog = null) }
                eventChannel.send(FilesEvent.Changed(if (entry == null) FilesChange.CREATED else FilesChange.RENAMED, 1, name.trim()))
                refresh()
            } else {
                state.update { it.copy(nameDialog = dialog.copy(saving = false, message = message)) }
                if (message == ActionMessage.CHANGED) reloadStale()
            }
        }
    }

    /** Opens the folder picker to move [entry]. */
    fun startMove(entry: DriveEntry) = startMove(listOf(entry))

    /** Opens the folder picker to move the selected entries. */
    fun startMoveSelected() = startMove(selectedEntries())

    private fun startMove(entries: List<DriveEntry>) {
        if (entries.isEmpty()) return
        val path = state.value.path
        state.update { it.copy(movePicker = MovePicker(entries, origin = path, path = path)) }
        loadPicker()
    }

    private fun loadPicker() {
        val picker = state.value.movePicker ?: return
        pickerJob?.cancel()
        updatePicker { it.copy(folders = emptyList(), loading = true, message = null) }
        pickerJob =
            viewModelScope.launch {
                val result = files.list(picker.path.lastOrNull()?.id)
                updatePicker {
                    it.copy(
                        folders = (result as? FilesResult.Ok)?.value?.filterIsInstance<DriveEntry.Folder>() ?: emptyList(),
                        loading = false,
                        message = result.message(),
                    )
                }
            }
    }

    private fun updatePicker(change: (MovePicker) -> MovePicker) = state.update { s -> s.copy(movePicker = s.movePicker?.let(change)) }

    fun pickerOpen(folder: DriveEntry.Folder) {
        val picker = state.value.movePicker ?: return
        if (picker.moving || !picker.canOpen(folder)) return
        updatePicker { it.copy(path = it.path + FolderCrumb(folder.id, folder.name), moveMessage = null) }
        loadPicker()
    }

    /** Shows the folder at [depth] of the picker's path: 0 is the drive root. */
    fun pickerGoTo(depth: Int) {
        val picker = state.value.movePicker ?: return
        if (picker.moving || depth >= picker.path.size) return
        updatePicker { it.copy(path = it.path.take(depth.coerceAtLeast(0)), moveMessage = null) }
        loadPicker()
    }

    fun cancelMove() {
        if (state.value.movePicker?.moving == true) return
        pickerJob?.cancel()
        state.update { it.copy(movePicker = null) }
    }

    /** Moves the picker's entries into the folder it shows, one after the other; the picker stays open if one fails. */
    fun confirmMove() {
        val picker = state.value.movePicker ?: return
        if (!picker.canMove) return
        val target = picker.path.lastOrNull()?.id
        updatePicker { it.copy(moving = true, moveMessage = null) }
        viewModelScope.launch {
            val results = picker.entries.map { it to files.move(it, target) }
            val failures = results.filter { (_, result) -> result !is FilesResult.Ok }
            val moved = picker.entries.size - failures.size
            // Only a folder move can clash with a name or go into itself; for a file a 409 is a concurrent change.
            val messages = failures.map { (entry, result) -> result.actionMessage(conflict = entry.moveConflict()) }
            val problem = messages.firstOrNull()
            state.update {
                it.copy(
                    // Only what is left to move stays in the picker, so trying again does not move anything twice.
                    movePicker =
                        if (problem == null) {
                            null
                        } else {
                            picker.copy(entries = failures.map { (entry, _) -> entry }, moving = false, moveMessage = problem)
                        },
                    selected = if (problem == null) emptySet() else it.selected,
                )
            }
            results.firstOrNull { (_, result) -> result is FilesResult.Ok }?.let { (first, _) ->
                eventChannel.send(FilesEvent.Changed(FilesChange.MOVED, moved, first.name))
            }
            if (ActionMessage.CHANGED in messages) reloadStale() else refresh()
        }
    }

    /** Moves [entry] to the Trash. No confirmation: it can be restored from there. */
    fun delete(entry: DriveEntry) = delete(listOf(entry))

    /** Moves the selected entries to the Trash. */
    fun deleteSelected() = delete(selectedEntries())

    private fun delete(entries: List<DriveEntry>) {
        if (entries.isEmpty() || state.value.working) return
        state.update { it.copy(working = true, actionMessage = null) }
        viewModelScope.launch {
            val results = entries.map { files.delete(it) }
            // Already gone (deleted elsewhere) is what was asked for.
            val failures = results.filter { it !is FilesResult.Ok && !(it is FilesResult.Failed && it.status == NOT_FOUND) }
            val trashed = results.count { it is FilesResult.Ok }
            state.update {
                it.copy(
                    working = false,
                    // Deleting checks no names: a 409 is a concurrent change.
                    actionMessage = failures.firstOrNull()?.actionMessage(conflict = ActionMessage.CHANGED),
                    selected = if (failures.isEmpty()) emptySet() else it.selected,
                )
            }
            entries.zip(results).firstOrNull { (_, result) -> result is FilesResult.Ok }?.let { (first, _) ->
                eventChannel.send(FilesEvent.Changed(FilesChange.TRASHED, trashed, first.name))
            }
            refresh()
        }
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

    /**
     * Downloads [file] into the document [target] opens (the user picked where); a failed download, or a save ignored
     * because another file is downloading, calls [discard].
     */
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
        // One at a time: a second tap while a file is downloading is ignored, before it touches any file. What it was
        // given is discarded: the "save as" picker already created an empty document, which must not look like a copy.
        if (downloadJob?.isActive == true) {
            viewModelScope.launch(io) { runCatching { discard() } }
            return
        }
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
private const val NOT_FOUND = 404
private const val CONFLICT = 409
private const val PRECONDITION_FAILED = 412

/** What a `409` on moving this entry means: a clash of folder names or a folder into itself, for a file a concurrent change. */
private fun DriveEntry.moveConflict() = if (this is DriveEntry.Folder) ActionMessage.MOVE_CONFLICT else ActionMessage.CHANGED

/**
 * Why a change failed. What a `409` means depends on the operation and the entry ([conflict]); [ActionMessage.CHANGED]
 * (outdated or gone) means the folder is reloaded.
 */
private fun FilesResult<*>.actionMessage(conflict: ActionMessage) =
    when (this) {
        is FilesResult.Ok -> null
        FilesResult.Offline -> ActionMessage.OFFLINE
        is FilesResult.Failed ->
            when (status) {
                CONFLICT -> conflict
                PRECONDITION_FAILED, NOT_FOUND -> ActionMessage.CHANGED
                else -> ActionMessage.FAILED
            }
    }

/**
 * The open dialog and picker take the reloaded versions of their entries from [fresh], the contents of [folder], so
 * trying again sends the current `If-Match`. Entries gone from the folder the picker moves from are dropped; when none
 * is left, the picker closes and says why.
 */
private fun FilesUiState.withVersionsOf(
    fresh: List<DriveEntry>,
    folder: UUID?,
): FilesUiState {
    val byId = fresh.associateBy { it.id }
    val dialog = nameDialog?.let { d -> d.copy(entry = d.entry?.let { byId[it.id] ?: it }) }
    val picker = movePicker
    if (picker == null || picker.moving || picker.origin.lastOrNull()?.id != folder) return copy(nameDialog = dialog)
    val left = picker.entries.mapNotNull { byId[it.id] }
    return if (left.isEmpty()) {
        copy(nameDialog = dialog, movePicker = null, actionMessage = picker.moveMessage ?: ActionMessage.CHANGED)
    } else {
        copy(nameDialog = dialog, movePicker = picker.copy(entries = left))
    }
}

private fun FilesResult<*>.uploadState() =
    when (this) {
        is FilesResult.Ok -> UploadState.DONE
        FilesResult.Offline -> UploadState.OFFLINE
        is FilesResult.Failed -> if (status == TOO_LARGE) UploadState.TOO_LARGE else UploadState.FAILED
    }
