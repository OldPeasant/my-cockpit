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
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/** Where journal saves run: as long as the app, so leaving the Journal screen never cancels or drops a save. */
@Singleton
class JournalSaveScope
    @Inject
    constructor() : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** A one-off problem shown to the user. */
enum class JournalMessage {
    LOAD_OFFLINE,
    LOAD_FAILED,
    PHOTO_UNREADABLE,
    PHOTO_OFFLINE,
    PHOTO_FAILED,

    /** A photo was not added because another day was opened (or was being opened) while it was read or uploaded. */
    PHOTO_DAY_CHANGED,

    /** Another day was asked for, but the shown one could not be saved: it stays open so nothing is lost. */
    DAY_NOT_SAVED,
}

/**
 * The day's entry was changed elsewhere while it was written here: the server refused the save. [current] is the
 * server's entry, `null` when the day has none any more (it was deleted).
 */
data class JournalConflict(
    val current: JournalEntry?,
)

data class JournalUiState(
    val date: LocalDate,
    /** The current day: no day after it can be opened. */
    val today: LocalDate = date,
    /** The entry as last loaded or saved; `null` while the day has none on the server. */
    val entry: JournalEntry? = null,
    val body: String = "",
    /** 1 (bad) to 5 (great), `null`: none. */
    val mood: Int? = null,
    /** The tags as saved ([tagsText] parsed). */
    val tags: List<String> = emptyList(),
    /** The tags field as typed. */
    val tagsText: String = "",
    val loading: Boolean = true,
    /** The day's entry is not loaded (yet, or its load failed): nothing can be written, so nothing overwrites it. */
    val unloaded: Boolean = true,
    /** Plain-text editor instead of the rendered Markdown. */
    val editing: Boolean = false,
    val status: SaveStatus = SaveStatus.SAVED,
    val conflict: JournalConflict? = null,
    val uploading: Boolean = false,
    val message: JournalMessage? = null,
    /** The month shown in the calendar. */
    val month: YearMonth = YearMonth.from(date),
    /** The days of [month] that have an entry, each with its mood. */
    val monthDays: Map<LocalDate, Int?> = emptyMap(),
    val monthLoading: Boolean = false,
    val monthFailed: Boolean = false,
    /** The entries of this day in earlier years, newest first. */
    val onThisDay: List<JournalEntry> = emptyList(),
    /** The earlier years of the shown day are being asked for: [onThisDay] does not tell yet whether there are any. */
    val onThisDayLoading: Boolean = false,
    val onThisDayFailed: Boolean = false,
) {
    /** Writing is possible: the entry is loaded and no conflict waits for a decision. */
    val editable: Boolean get() = !loading && !unloaded && conflict == null

    val draft: JournalDraft get() = JournalDraft(body, mood, tags)

    val isToday: Boolean get() = date == today

    /** The next day can be opened: it is not in the future. */
    val canGoForward: Boolean get() = date < today

    /** The calendar can show the next month: it is not after the current one. */
    val canShowNextMonth: Boolean get() = month < YearMonth.from(today)
}

/**
 * The journal (SPEC.md §8.5): one day's entry at a time, today when the screen opens. Its text is shown as rendered
 * Markdown or in the plain editor; text, mood and tags are saved automatically [AUTOSAVE_DELAY_MS] after the last
 * change, one save at a time over the version the previous one returned. An entry is only created once it has text,
 * a mood or a tag. A save refused because the day changed elsewhere stops the autosave until the user keeps their
 * version or takes the server's. Opening another day first saves the shown one and stays on it when that fails.
 * Saves run in [saves], which outlives this view model, so leaving the screen neither cancels a running save nor
 * drops changes that wait for their autosave. Next to the entry: a month calendar marking the days with an entry,
 * and the entries of the same day in earlier years.
 */
@HiltViewModel
class JournalViewModel internal constructor(
    private val journal: JournalRepository,
    images: NoteImages,
    private val saves: CoroutineScope,
    private val clock: Clock,
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
    private var loadJob: Job? = null
    private var monthJob: Job? = null
    private var onThisDayJob: Job? = null
    private var navigation: Job? = null

    /**
     * Entries saved since the newest month request started: its answer may have been computed before them, so they
     * are marked again on it (like `savedSince` on the web).
     */
    private val savedSince = mutableMapOf<LocalDate, JournalEntry>()

    /** Image Markdown uploaded while a conflict came up: added once the user decided. */
    private var pendingImage: Pair<String, Int?>? = null

    init {
        loadJob = viewModelScope.launch { load() }
        loadMonth()
    }

    /** Loads the entry again after a failed load. */
    fun retryLoad() {
        if (state.value.loading || !state.value.unloaded) return
        state.update { it.copy(loading = true, message = null) }
        loadJob = viewModelScope.launch { load() }
    }

    /** The shown day's entry and its earlier years. */
    private suspend fun load() {
        val date = state.value.date
        // Only the newest request counts: the same day asked for again must not get an older, late answer.
        onThisDayJob?.cancel()
        state.update { if (it.date == date) it.copy(onThisDayLoading = true) else it }
        onThisDayJob = viewModelScope.launch { loadOnThisDay(date) }
        val result = journal.get(date)
        state.update {
            if (it.date != date) return@update it
            when (result) {
                is JournalResult.Ok -> {
                    val entry = result.value
                    it.copy(
                        entry = entry,
                        body = entry?.bodyMd.orEmpty(),
                        mood = entry?.mood,
                        tags = entry?.tags.orEmpty(),
                        tagsText = entry?.tags.orEmpty().joinToString(", "),
                        loading = false,
                        unloaded = false,
                        // A day without text opens in the editor, ready to write.
                        editing = entry?.bodyMd.isNullOrEmpty(),
                    )
                }
                JournalResult.Offline -> it.copy(loading = false, message = JournalMessage.LOAD_OFFLINE)
                is JournalResult.Failed -> it.copy(loading = false, message = JournalMessage.LOAD_FAILED)
            }
        }
    }

    private suspend fun loadOnThisDay(date: LocalDate) {
        val result = journal.onThisDay(date)
        state.update {
            when {
                it.date != date -> it
                result is JournalResult.Ok -> it.copy(onThisDay = result.value, onThisDayLoading = false, onThisDayFailed = false)
                else -> it.copy(onThisDay = emptyList(), onThisDayLoading = false, onThisDayFailed = true)
            }
        }
    }

    /** Shows [month] in the calendar (not after the current month). */
    fun showMonth(month: YearMonth) {
        val shown = minOf(month, YearMonth.from(LocalDate.now(clock)))
        if (shown == state.value.month && !state.value.monthFailed) return
        state.update { it.copy(month = shown, monthDays = emptyMap()) }
        loadMonth()
    }

    private fun loadMonth() {
        val month = state.value.month
        state.update { it.copy(monthLoading = true, monthFailed = false) }
        monthJob?.cancel()
        savedSince.clear()
        monthJob =
            viewModelScope.launch {
                val result = journal.days(month.atDay(1), month.atEndOfMonth())
                val saved =
                    savedSince.values
                        .filter { YearMonth.from(it.date) == month }
                        .associate { it.date to it.mood }
                state.update {
                    when {
                        it.month != month -> it
                        result is JournalResult.Ok -> it.copy(monthDays = result.value + saved, monthLoading = false)
                        else -> it.copy(monthLoading = false, monthFailed = true)
                    }
                }
            }
    }

    fun previousDay() = openDay(state.value.date.minusDays(1))

    fun nextDay() = openDay(state.value.date.plusDays(1))

    fun openToday() = openDay(LocalDate.now(clock))

    /**
     * Opens the entry of [date] (today at the latest). Changes of the shown day are saved first; when that fails (or
     * a conflict waits for a decision) the shown day stays open with [JournalMessage.DAY_NOT_SAVED]. Ignored while a
     * photo uploads, so it lands in the day it was taken for.
     */
    fun openDay(date: LocalDate) {
        val today = LocalDate.now(clock)
        state.update { it.copy(today = today) }
        val target = minOf(date, today)
        if (state.value.uploading || (target == state.value.date && navigation?.isActive != true)) return
        autosave?.cancel()
        autosave = null
        navigation?.cancel()
        navigation =
            viewModelScope.launch {
                val before = state.value
                if (!before.unloaded && !before.isSaved() && before.conflict == null) saves.launch { save() }.join()
                val after = state.value
                if (!after.unloaded && (after.conflict != null || !after.isSaved())) {
                    state.update { it.copy(message = JournalMessage.DAY_NOT_SAVED) }
                    return@launch
                }
                if (target != after.date) show(target)
            }
    }

    private fun show(date: LocalDate) {
        loadJob?.cancel()
        pendingImage = null
        val before = state.value
        val month = YearMonth.from(date)
        state.value =
            JournalUiState(
                date = date,
                today = before.today,
                month = month,
                monthDays = if (month == before.month) before.monthDays else emptyMap(),
                monthLoading = month == before.month && before.monthLoading,
                monthFailed = month == before.month && before.monthFailed,
            )
        if (month != before.month) loadMonth()
        loadJob = viewModelScope.launch { load() }
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

    /** Sets the day's mood (1 to 5); the mood already set clears it. */
    fun setMood(mood: Int) {
        if (!state.value.editable || mood !in MOODS) return
        state.update { it.copy(mood = if (it.mood == mood) null else mood).withStatus() }
        saveSoon(AUTOSAVE_DELAY_MS)
    }

    /** The tags field changed: [text] is a comma-separated list. */
    fun setTags(text: String) {
        if (!state.value.editable) return
        state.update { it.copy(tagsText = text, tags = JournalDraft.parseTags(text)).withStatus() }
        saveSoon(AUTOSAVE_DELAY_MS)
    }

    /** Leaving the tags field shows them as they are saved. */
    fun tidyTags() = state.update { it.copy(tagsText = it.tags.joinToString(", ")) }

    /** Saves now, e.g. after a failed save. The returned job ends once the changes are saved (or the save failed). */
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

    /** The screen is gone: changes still waiting for their autosave are saved now, in [saves]. */
    override fun onCleared() {
        autosave?.cancel()
        autosave = null
        saves.launch { save() }
    }

    private suspend fun save() =
        saving.withLock {
            val before = state.value
            if (before.conflict != null || before.loading || before.unloaded || before.isSaved()) return@withLock
            state.update { if (it.date == before.date) it.copy(status = SaveStatus.SAVING) else it }
            val result = journal.saveEntry(before.date, before.entry, before.draft)
            if (result is JournalSaveResult.Saved) savedSince[result.entry.date] = result.entry
            state.update {
                if (it.date != before.date) return@update it
                when (result) {
                    is JournalSaveResult.Saved -> it.copy(entry = result.entry).withStatus().marked(result.entry)
                    is JournalSaveResult.Conflict ->
                        it.copy(conflict = JournalConflict(result.current), status = SaveStatus.UNSAVED, editing = false)
                    JournalSaveResult.Offline -> it.copy(status = SaveStatus.OFFLINE)
                    is JournalSaveResult.Failed -> it.copy(status = SaveStatus.FAILED)
                }
            }
        }

    /** Resolves the conflict with the changes made here: they are saved over the server's entry (or as a new one). */
    fun keepMine() {
        val conflict = state.value.conflict ?: return
        state.update { it.copy(entry = conflict.current, conflict = null).withStatus() }
        insertPendingImage(atCursor = true)
        saveSoon(0)
    }

    /** Resolves the conflict with the server's entry: the changes made here are dropped. */
    fun useTheirs() {
        val current = state.value.conflict?.current
        if (state.value.conflict == null) return
        state.update {
            it
                .copy(
                    entry = current,
                    body = current?.bodyMd.orEmpty(),
                    mood = current?.mood,
                    tags = current?.tags.orEmpty(),
                    tagsText = current?.tags.orEmpty().joinToString(", "),
                    conflict = null,
                    editing = current == null,
                ).withStatus()
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
     * `null`), on a line of its own; the entry is then saved. The photo belongs to [date], the day it was picked for:
     * while another day is shown or being opened it is not added, and [JournalMessage.PHOTO_DAY_CHANGED] says so.
     */
    fun addPhoto(
        photo: NotePhoto?,
        at: Int? = null,
        date: LocalDate = state.value.date,
    ) {
        if (photo == null) {
            state.update { it.copy(message = JournalMessage.PHOTO_UNREADABLE) }
            return
        }
        if (date != state.value.date || navigation?.isActive == true) {
            state.update { it.copy(message = JournalMessage.PHOTO_DAY_CHANGED) }
            return
        }
        if (!state.value.editable || state.value.uploading) return
        state.update { it.copy(uploading = true, message = null) }
        viewModelScope.launch {
            val result = journal.uploadImage(photo)
            if (state.value.date != date) {
                state.update { it.copy(message = JournalMessage.PHOTO_DAY_CHANGED) }
                return@launch
            }
            when (result) {
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

    /** The changes equal the saved entry (without one: nothing written, no mood, no tags yet). */
    private fun JournalUiState.isSaved(): Boolean = draft.sameAs(entry.draft)

    private fun JournalUiState.withStatus() = copy(status = if (isSaved()) SaveStatus.SAVED else SaveStatus.UNSAVED)

    /** The calendar marks the saved entry's day (with its mood). */
    private fun JournalUiState.marked(entry: JournalEntry) =
        if (YearMonth.from(entry.date) == month) copy(monthDays = monthDays + (entry.date to entry.mood)) else this

    companion object {
        const val AUTOSAVE_DELAY_MS = 1_000L

        /** The moods to pick from, 1 (bad) to 5 (great). */
        val MOODS = 1..5
    }
}
