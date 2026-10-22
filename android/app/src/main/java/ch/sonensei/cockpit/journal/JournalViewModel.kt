package ch.sonensei.cockpit.journal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.notes.NoteEditorViewModel
import ch.sonensei.cockpit.notes.NoteImages
import ch.sonensei.cockpit.notes.NotePhoto
import ch.sonensei.cockpit.notes.SaveStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Where journal saves run: as long as the app, so leaving the Journal screen never cancels or drops a save. */
@Singleton
class JournalSaveScope
    @Inject
    constructor() : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** A one-off problem shown to the user. */
enum class JournalMessage { LOAD_OFFLINE, LOAD_FAILED, PHOTO_UNREADABLE, PHOTO_OFFLINE, PHOTO_FAILED }

/**
 * The day's entry was changed elsewhere while it was written here: the server refused the save. [current] is the
 * server's entry, `null` when the day has none any more (it was deleted).
 */
data class JournalConflict(
    val current: JournalEntry?,
)

data class JournalUiState(
    val date: LocalDate,
    /** The entry as last loaded or saved; `null` while the day has none on the server. */
    val entry: JournalEntry? = null,
    val body: String = "",
    val loading: Boolean = true,
    /** The day's entry is not loaded (yet, or its load failed): nothing can be written, so nothing overwrites it. */
    val unloaded: Boolean = true,
    /** Plain-text editor instead of the rendered Markdown. */
    val editing: Boolean = false,
    val status: SaveStatus = SaveStatus.SAVED,
    val conflict: JournalConflict? = null,
    val uploading: Boolean = false,
    val message: JournalMessage? = null,
) {
    /** Writing is possible: the entry is loaded and no conflict waits for a decision. */
    val editable: Boolean get() = !loading && !unloaded && conflict == null
}

/**
 * Today's journal entry (SPEC.md §8.5: "Today" opens or creates it): the rendered Markdown or the plain editor, saved
 * automatically [AUTOSAVE_DELAY_MS] after the last change, one save at a time over the version the previous one
 * returned. The entry is only created once it has text. A save refused because the day changed elsewhere stops the
 * autosave until the user keeps their text or takes the server's. Saves run in [saves], which outlives this view
 * model, so leaving the screen neither cancels a running save nor drops text that waits for its autosave.
 */
@HiltViewModel
class JournalViewModel internal constructor(
    private val journal: JournalRepository,
    images: NoteImages,
    private val saves: CoroutineScope,
    clock: Clock,
) : ViewModel() {
    @Inject
    constructor(
        journal: JournalRepository,
        images: NoteImages,
        saves: JournalSaveScope,
    ) : this(journal, images, saves, Clock.systemDefaultZone())

    val imageLoader = images.loader

    private val state = MutableStateFlow(JournalUiState(date = LocalDate.now(clock)))
    val uiState: StateFlow<JournalUiState> = state.asStateFlow()

    private val saving = Mutex()
    private var autosave: Job? = null

    /** Image Markdown uploaded while a conflict came up: added once the user decided. */
    private var pendingImage: Pair<String, Int?>? = null

    init {
        viewModelScope.launch { load() }
    }

    /** Loads the entry again after a failed load. */
    fun retryLoad() {
        if (state.value.loading || !state.value.unloaded) return
        state.update { it.copy(loading = true, message = null) }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val result = journal.get(state.value.date)
        state.update {
            when (result) {
                is JournalResult.Ok ->
                    it.copy(
                        entry = result.value,
                        body = result.value?.bodyMd.orEmpty(),
                        loading = false,
                        unloaded = false,
                        // A day without text opens in the editor, ready to write.
                        editing = result.value?.bodyMd.isNullOrEmpty(),
                    )
                JournalResult.Offline -> it.copy(loading = false, message = JournalMessage.LOAD_OFFLINE)
                is JournalResult.Failed -> it.copy(loading = false, message = JournalMessage.LOAD_FAILED)
            }
        }
    }

    fun setEditing(editing: Boolean) {
        if (editing && !state.value.editable) return
        state.update { it.copy(editing = editing) }
        if (!editing) saveSoon(0)
    }

    fun setBody(body: String) {
        if (!state.value.editable) return
        state.update { it.copy(body = body).withStatus() }
        saveSoon(AUTOSAVE_DELAY_MS)
    }

    /** Saves now, e.g. after a failed save. The returned job ends once the text is saved (or the save failed). */
    fun saveNow(): Job {
        autosave?.cancel()
        autosave = null
        return saves.launch { save() }
    }

    /** Saves after [delayMs], unless another change comes first. Only the wait is cancelled, never a running save. */
    private fun saveSoon(delayMs: Long) {
        autosave?.cancel()
        autosave =
            viewModelScope.launch {
                delay(delayMs)
                saves.launch { save() }
            }
    }

    /** The screen is gone: text still waiting for its autosave is saved now, in [saves]. */
    override fun onCleared() {
        autosave?.cancel()
        autosave = null
        saves.launch { save() }
    }

    private suspend fun save() =
        saving.withLock {
            val before = state.value
            if (before.conflict != null || before.loading || before.unloaded || before.isSaved()) return@withLock
            state.update { it.copy(status = SaveStatus.SAVING) }
            val result = journal.save(before.date, before.entry, before.body)
            state.update {
                when (result) {
                    is JournalSaveResult.Saved -> it.copy(entry = result.entry).withStatus()
                    is JournalSaveResult.Conflict ->
                        it.copy(conflict = JournalConflict(result.current), status = SaveStatus.UNSAVED, editing = false)
                    JournalSaveResult.Offline -> it.copy(status = SaveStatus.OFFLINE)
                    is JournalSaveResult.Failed -> it.copy(status = SaveStatus.FAILED)
                }
            }
        }

    /** Resolves the conflict with the text written here: it is saved over the server's entry (or as a new one). */
    fun keepMine() {
        val conflict = state.value.conflict ?: return
        state.update { it.copy(entry = conflict.current, conflict = null, status = SaveStatus.UNSAVED) }
        insertPendingImage(atCursor = true)
        saveSoon(0)
    }

    /** Resolves the conflict with the server's entry: the text written here is dropped. */
    fun useTheirs() {
        val current = state.value.conflict?.current
        if (state.value.conflict == null) return
        state.update {
            it.copy(entry = current, body = current?.bodyMd.orEmpty(), conflict = null, editing = current == null).withStatus()
        }
        if (insertPendingImage(atCursor = false)) saveSoon(0)
    }

    private fun insertPendingImage(atCursor: Boolean): Boolean {
        val (image, at) = pendingImage ?: return false
        pendingImage = null
        state.update { it.copy(body = NoteEditorViewModel.insertImage(it.body, image, if (atCursor) at else null)).withStatus() }
        return true
    }

    /**
     * Uploads [photo] (`null`: it could not be read) and inserts its Markdown image at [at] in the body (the end when
     * `null`), on a line of its own; the entry is then saved.
     */
    fun addPhoto(
        photo: NotePhoto?,
        at: Int? = null,
    ) {
        if (photo == null) {
            state.update { it.copy(message = JournalMessage.PHOTO_UNREADABLE) }
            return
        }
        if (!state.value.editable || state.value.uploading) return
        state.update { it.copy(uploading = true, message = null) }
        viewModelScope.launch {
            when (val result = journal.uploadImage(photo)) {
                is JournalResult.Ok -> {
                    state.update { it.copy(uploading = false) }
                    when {
                        state.value.editable -> {
                            state.update { it.copy(body = NoteEditorViewModel.insertImage(it.body, result.value, at)).withStatus() }
                            saveSoon(0)
                        }
                        state.value.conflict != null -> pendingImage = result.value to at
                    }
                }
                JournalResult.Offline -> state.update { it.copy(uploading = false, message = JournalMessage.PHOTO_OFFLINE) }
                is JournalResult.Failed -> state.update { it.copy(uploading = false, message = JournalMessage.PHOTO_FAILED) }
            }
        }
    }

    fun dismissMessage() = state.update { it.copy(message = null) }

    /** The text equals the saved entry (without one: there is no text to save yet). */
    private fun JournalUiState.isSaved(): Boolean = body == entry?.bodyMd.orEmpty()

    private fun JournalUiState.withStatus() = copy(status = if (isSaved()) SaveStatus.SAVED else SaveStatus.UNSAVED)

    companion object {
        const val AUTOSAVE_DELAY_MS = 1_000L
    }
}
