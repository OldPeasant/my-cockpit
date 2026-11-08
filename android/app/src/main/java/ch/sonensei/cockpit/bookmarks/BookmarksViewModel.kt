package ch.sonensei.cockpit.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import ch.sonensei.cockpit.capture.queue.UuidV7
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Why the shown list may be outdated. */
enum class BookmarksMessage { OFFLINE, FAILED }

/** Why a change of one bookmark did not happen. */
enum class BookmarkFailure { OFFLINE, CONFLICT, FAILED }

/** A short note at the bottom of the screen. */
sealed interface BookmarksSnackbar {
    /**
     * Tells two notes with the same content apart (the view model numbers them), so each one is shown and the end of
     * one never clears another.
     */
    val id: Long

    /** [bookmark] went to the Trash; Undo takes it out again. */
    data class Trashed(
        val bookmark: Bookmark,
        override val id: Long = 0,
    ) : BookmarksSnackbar

    data class Failed(
        val failure: BookmarkFailure,
        override val id: Long = 0,
    ) : BookmarksSnackbar

    /** An Inbox task was made of a bookmark; [linked] is `false` when it could not be linked to the bookmark. */
    data class AddedToInbox(
        val linked: Boolean,
        override val id: Long = 0,
    ) : BookmarksSnackbar

    /** "To Inbox task" got no answer: the task may have been made, and trying again does not make it twice. */
    data class InboxOffline(
        override val id: Long = 0,
    ) : BookmarksSnackbar

    /** No app on the device opens web addresses. */
    data object NoBrowser : BookmarksSnackbar {
        override val id: Long get() = 0
    }
}

/** Why a new bookmark was not saved. */
enum class AddFailure {
    INVALID_URL,

    /** Too many tags, or a tag that is too long. */
    INVALID_TAGS,
    OFFLINE,
    FAILED,
}

/** The open "Add bookmark" dialog. */
data class AddBookmarkState(
    /** What the dialog starts with: the unsaved input of a save that failed. */
    val draft: NewBookmark = NewBookmark(""),
    val saving: Boolean = false,
    val failure: AddFailure? = null,
)

/** Why the readable copy is not shown. */
enum class ReaderFailure {
    OFFLINE,
    FAILED,

    /** The server could not fetch the page or found nothing readable in it. */
    UNREADABLE,
}

/** The open reader: the readable copy the server saved of [bookmark]'s page. */
data class ReaderState(
    val bookmark: Bookmark,
    val snapshot: BookmarkSnapshot? = null,
    /** The bookmark or its copy is being loaded. */
    val loading: Boolean = false,
    /** A new copy is being saved. */
    val saving: Boolean = false,
    val failure: ReaderFailure? = null,
)

data class BookmarksUiState(
    /** The bookmarks of the view and tag, newest first (before the search). */
    val bookmarks: List<Bookmark> = emptyList(),
    val view: BookmarkView = BookmarkView.QUEUE,
    /** The tag filter; `null` shows every bookmark of the view. */
    val tag: String? = null,
    val tags: List<BookmarkTag> = emptyList(),
    /** The search text; only bookmarks matching every word are shown. */
    val query: String = "",
    /** A load is running. */
    val loading: Boolean = false,
    /** The running load was started by pulling the list down. */
    val refreshing: Boolean = false,
    /** The list of this view and tag was loaded once. */
    val loaded: Boolean = false,
    val message: BookmarksMessage? = null,
    /** Bookmarks with a change running. */
    val busy: Set<UUID> = emptySet(),
    /** The bookmark being edited. */
    val editing: Bookmark? = null,
    /** What the editor starts with instead of [editing]'s values: the unsaved edit of a save that failed. */
    val draft: BookmarkDraft? = null,
    val snackbar: BookmarksSnackbar? = null,
    /** The add dialog, while it is open. */
    val adding: AddBookmarkState? = null,
    /** The reader, while it is open. */
    val reading: ReaderState? = null,
) {
    /** What the list shows: the bookmarks matching the search. */
    val shown: List<Bookmark> get() = if (query.isBlank()) bookmarks else bookmarks.filter { it.matches(query) }
}

/**
 * The bookmarks list (SPEC.md §8.4 "Views"): the read-later queue or all bookmarks, by tag, with a search over the
 * loaded ones. Read-later bookmarks shared to the app appear as soon as the capture queue has sent them.
 */
@HiltViewModel
class BookmarksViewModel internal constructor(
    private val bookmarks: BookmarksRepository,
    /** The number of shares still waiting to be sent (`CaptureQueue.status`). */
    pendingShares: Flow<Int>,
) : ViewModel() {
    @Inject
    constructor(bookmarks: BookmarksRepository, queue: CaptureQueue) : this(bookmarks, queue.status.map { it.pendingCount })

    private val state = MutableStateFlow(BookmarksUiState())
    val uiState: StateFlow<BookmarksUiState> = state.asStateFlow()

    private var loadJob: Job? = null
    private var readerJob: Job? = null

    /** The ids of Inbox tasks whose create got no answer (offline), by bookmark: a retry sends the same id. */
    private val inboxTaskIds = mutableMapOf<UUID, UUID>()

    /** The number of the last snackbar shown. */
    private var snackbars = 0L

    init {
        viewModelScope.launch {
            var before: Int? = null
            pendingShares.collect { pending ->
                // A share left the queue: it is on the server now (or was given up), so the list may have grown.
                if (before.let { it != null && pending < it } && state.value.loaded) refresh()
                before = pending
            }
        }
    }

    /** Loads the list for the current view and tag; a load still running for an earlier filter is dropped. */
    fun refresh(pulled: Boolean = false) {
        loadJob?.cancel()
        val view = state.value.view
        val tag = state.value.tag
        state.update { it.copy(loading = true, refreshing = pulled) }
        loadJob =
            viewModelScope.launch {
                val tags = async { bookmarks.tags() }
                val list = bookmarks.list(view, tag)
                val tagList = tags.await()
                // A newer load replaced this one meanwhile: its list is outdated.
                ensureActive()
                state.update {
                    it.copy(
                        bookmarks = (list as? BookmarksResult.Ok)?.value ?: it.bookmarks,
                        tags = (tagList as? BookmarksResult.Ok)?.value ?: it.tags,
                        loading = false,
                        refreshing = false,
                        loaded = it.loaded || list is BookmarksResult.Ok,
                        message =
                            when (list) {
                                is BookmarksResult.Ok -> null
                                BookmarksResult.Offline -> BookmarksMessage.OFFLINE
                                is BookmarksResult.Failed -> BookmarksMessage.FAILED
                            },
                    )
                }
            }
    }

    fun selectView(view: BookmarkView) {
        if (view == state.value.view) return
        // The bookmarks of the old view would be wrong for the new one: they are cleared until the new list arrives.
        state.update { it.copy(view = view, bookmarks = emptyList(), loaded = false) }
        refresh()
    }

    /** Shows only the bookmarks with [tag] (`null`: all); choosing the active tag again clears the filter. */
    fun selectTag(tag: String?) {
        val next = tag?.takeUnless { it.equals(state.value.tag, ignoreCase = true) }
        if (next == state.value.tag) return
        state.update { it.copy(tag = next, bookmarks = emptyList(), loaded = false) }
        refresh()
    }

    fun search(query: String) = state.update { it.copy(query = query) }

    /** Marks [bookmark] as read, or as unread again. */
    fun toggleRead(bookmark: Bookmark) = change(bookmark) { bookmarks.setRead(it, read = !it.read) }

    /** Puts [bookmark] into the read-later list, or takes it out. */
    fun toggleReadLater(bookmark: Bookmark) = change(bookmark) { bookmarks.setReadLater(it, readLater = !it.readLater) }

    /** Opens the add dialog; read later is preset in the read-later view. */
    fun startAdd() =
        state.update {
            if (it.adding != null) it else it.copy(adding = AddBookmarkState(NewBookmark("", readLater = it.view == BookmarkView.QUEUE)))
        }

    fun cancelAdd() = state.update { if (it.adding?.saving == true) it else it.copy(adding = null) }

    /** Saves [bookmark]; the dialog closes when it is saved and stays open with the reason when it is not. */
    fun add(bookmark: NewBookmark) {
        val adding = state.value.adding ?: return
        if (adding.saving) return
        if (!isWebAddress(bookmark.url)) {
            state.update { it.copy(adding = AddBookmarkState(bookmark, failure = AddFailure.INVALID_URL)) }
            return
        }
        if (!tagsWithinLimits(bookmark.tags)) {
            state.update { it.copy(adding = AddBookmarkState(bookmark, failure = AddFailure.INVALID_TAGS)) }
            return
        }
        state.update { it.copy(adding = AddBookmarkState(bookmark, saving = true)) }
        viewModelScope.launch {
            when (val result = bookmarks.create(bookmark)) {
                is BookmarksResult.Ok -> {
                    state.update { s ->
                        val added = result.value
                        val list =
                            if (s.belongs(added) &&
                                s.loaded
                            ) {
                                (s.bookmarks.filterNot { it.id == added.id } + added).sortedWith(bookmarkOrder)
                            } else {
                                s.bookmarks
                            }
                        s.copy(adding = null, bookmarks = list)
                    }
                    reloadIfLoading()
                    reloadTags()
                }
                else -> {
                    val refused = (result as? BookmarksResult.Failed)?.takeIf { it.status == BAD_REQUEST }
                    // A 400 marks the field its Problem Details name; one naming no known field is a general failure.
                    val failure =
                        when {
                            result == BookmarksResult.Offline -> AddFailure.OFFLINE
                            refused != null && "url" in refused.fields -> AddFailure.INVALID_URL
                            refused != null && "tags" in refused.fields -> AddFailure.INVALID_TAGS
                            else -> AddFailure.FAILED
                        }
                    state.update { it.copy(adding = AddBookmarkState(bookmark, failure = failure)) }
                    // The answer may be lost after the server saved it: a fresh list shows whether it is there.
                    if (failure == AddFailure.OFFLINE && state.value.loaded) refresh()
                }
            }
        }
    }

    /**
     * Makes an Inbox task of [bookmark], linked to it (SPEC.md §4.5). The task's id is kept while the server's answer
     * is unknown (offline), so trying again after a lost answer returns the task made before instead of a duplicate.
     */
    fun toInbox(bookmark: Bookmark) =
        run(bookmark) { current ->
            val taskId = inboxTaskIds.getOrPut(current.id) { UuidV7.generate(System.currentTimeMillis()) }
            val result = bookmarks.toInbox(current, taskId)
            if (result != BookmarksResult.Offline) inboxTaskIds.remove(current.id)
            when (result) {
                is BookmarksResult.Ok -> show(BookmarksSnackbar.AddedToInbox(result.value, nextSnackbar()))
                // The task may exist already; trying again sends the same id, so it is never made twice.
                BookmarksResult.Offline -> show(BookmarksSnackbar.InboxOffline(nextSnackbar()))
                else -> show(BookmarksSnackbar.Failed(failure(result), nextSnackbar()))
            }
        }

    /** Opens the reader on [bookmark]: loads its latest version, then the readable copy, if the server saved one. */
    fun openReader(bookmark: Bookmark) {
        state.update { it.copy(reading = ReaderState(bookmark, loading = true)) }
        loadReader(bookmark)
    }

    /** Loads the bookmark of the open reader and its copy again. */
    fun retryReader() {
        val reading = state.value.reading ?: return
        if (reading.loading || reading.saving) return
        state.update { it.copy(reading = reading.copy(loading = true, failure = null)) }
        loadReader(reading.bookmark)
    }

    fun closeReader() {
        readerJob?.cancel()
        state.update { it.copy(reading = null) }
    }

    /**
     * Asks the server to fetch the page of the open reader again and save a new readable copy. Like the other changes
     * of a bookmark it is one at a time, so two writes never go out with the same version. Leaving the reader does not
     * stop it: the server may have saved the copy already, so its answer still updates the list (with the new version).
     */
    fun saveSnapshot() {
        val reading = state.value.reading ?: return
        if (reading.loading || reading.saving) return
        val id = reading.bookmark.id
        if (id in state.value.busy) return
        state.update { it.copy(reading = reading.copy(saving = true, failure = null), busy = it.busy + id) }
        viewModelScope.launch {
            try {
                saveSnapshot(reading.bookmark)
            } finally {
                state.update { it.copy(busy = it.busy - id) }
            }
        }
    }

    private suspend fun saveSnapshot(bookmark: Bookmark) {
        val id = bookmark.id
        when (val result = bookmarks.saveSnapshot(bookmark)) {
            is BookmarksResult.Ok -> {
                val saved = result.value
                if (saved == null) {
                    updateReader(id) { it.copy(saving = false, failure = ReaderFailure.UNREADABLE) }
                } else {
                    put(saved)
                    // The new copy is loaded only into a reader still open on the bookmark; leaving it stops the load.
                    if (state.value.reading.let { it == null || it.bookmark.id != id }) return
                    updateReader(id) { it.copy(saving = false, loading = true) }
                    readerJob?.cancel()
                    readerJob = viewModelScope.launch { loadSnapshot(saved) }
                }
            }
            else -> {
                val latest = failed(bookmark, result)
                if (latest == null) {
                    state.update { s -> if (s.reading?.bookmark?.id == id) s.copy(reading = null) else s }
                } else {
                    updateReader(id) { it.copy(bookmark = latest, saving = false) }
                }
            }
        }
    }

    private fun loadReader(bookmark: Bookmark) {
        readerJob?.cancel()
        readerJob =
            viewModelScope.launch {
                val latest =
                    when (val fresh = bookmarks.get(bookmark.id)) {
                        is BookmarksResult.Ok -> fresh.value.also { put(it) }
                        is BookmarksResult.Failed -> {
                            if (fresh.status == BookmarksRepository.NOT_FOUND) {
                                remove(bookmark.id)
                                closeReader()
                                return@launch
                            }
                            updateReader(bookmark.id) { it.copy(loading = false, failure = ReaderFailure.FAILED) }
                            return@launch
                        }
                        BookmarksResult.Offline -> {
                            updateReader(bookmark.id) { it.copy(loading = false, failure = ReaderFailure.OFFLINE) }
                            return@launch
                        }
                    }
                loadSnapshot(latest)
            }
    }

    /** Loads the readable copy of [bookmark] into the reader (none: the reader says so). */
    private suspend fun loadSnapshot(bookmark: Bookmark) {
        val fileId = bookmark.snapshotFileId
        if (fileId == null) {
            updateReader(bookmark.id) { it.copy(snapshot = null, loading = false) }
            return
        }
        val result = bookmarks.snapshot(fileId)
        updateReader(bookmark.id) {
            when (result) {
                is BookmarksResult.Ok -> it.copy(snapshot = result.value, loading = false, failure = null)
                BookmarksResult.Offline -> it.copy(loading = false, failure = ReaderFailure.OFFLINE)
                is BookmarksResult.Failed -> it.copy(loading = false, failure = ReaderFailure.FAILED)
            }
        }
    }

    /** Changes the reader, if it is still open on bookmark [id]. */
    private fun updateReader(
        id: UUID,
        change: (ReaderState) -> ReaderState,
    ) = state.update { s -> s.reading?.takeIf { it.bookmark.id == id }?.let { s.copy(reading = change(it)) } ?: s }

    fun edit(bookmark: Bookmark) = state.update { it.copy(editing = bookmark, draft = null) }

    fun cancelEdit() = state.update { it.copy(editing = null, draft = null) }

    /**
     * Saves the edited title, tags and notes; the dialog closes once the change is sent. If the save fails, the
     * dialog opens again with [draft] (after a conflict on the reloaded version), so nothing typed is lost.
     */
    fun save(draft: BookmarkDraft) {
        val bookmark = state.value.editing ?: return
        state.update { it.copy(editing = null, draft = null) }
        change(bookmark, reloadTags = true, onFailure = { latest -> reopen(latest, draft) }) { bookmarks.save(it, draft) }
    }

    /** Moves [bookmark] to the Trash; the snackbar offers to undo it. */
    fun delete(bookmark: Bookmark) =
        run(bookmark) { current ->
            when (val result = bookmarks.delete(current)) {
                is BookmarksResult.Ok -> {
                    remove(current.id)
                    show(BookmarksSnackbar.Trashed(current, nextSnackbar()))
                    reloadTags()
                }
                else -> failed(current, result)
            }
        }

    /** Takes the bookmark of [trashed] out of the Trash again and loads the list with it. */
    fun undo(trashed: BookmarksSnackbar.Trashed) {
        dismiss(trashed)
        viewModelScope.launch {
            when (val result = bookmarks.restore(trashed.bookmark.id)) {
                is BookmarksResult.Ok -> refresh()
                else -> show(BookmarksSnackbar.Failed(failure(result), nextSnackbar()))
            }
        }
    }

    fun noBrowser() = show(BookmarksSnackbar.NoBrowser)

    /** The snackbar was shown and is gone; a newer one stays. */
    fun dismiss(snackbar: BookmarksSnackbar) = state.update { if (it.snackbar == snackbar) it.copy(snackbar = null) else it }

    /** Runs [action] on [bookmark]; [onFailure] gets its latest known version when it failed (`null`: it is gone). */
    private fun change(
        bookmark: Bookmark,
        reloadTags: Boolean = false,
        onFailure: (Bookmark?) -> Unit = {},
        action: suspend (Bookmark) -> BookmarksResult<Bookmark>,
    ) = run(bookmark) { current ->
        when (val result = action(current)) {
            is BookmarksResult.Ok -> {
                put(result.value)
                if (reloadTags) reloadTags()
            }
            else -> onFailure(failed(current, result))
        }
    }

    /** Opens the editor again with the unsaved [draft], unless the bookmark is gone or another one is being edited. */
    private fun reopen(
        latest: Bookmark?,
        draft: BookmarkDraft,
    ) {
        if (latest == null) return
        state.update {
            if (it.editing != null) it else it.copy(editing = latest, draft = draft)
        }
    }

    /** Runs a change of [bookmark] (its latest loaded version); one change per bookmark at a time. */
    private fun run(
        bookmark: Bookmark,
        action: suspend (Bookmark) -> Unit,
    ) {
        if (bookmark.id in state.value.busy) return
        val current =
            state.value.bookmarks.find { it.id == bookmark.id }
                ?: state.value.reading
                    ?.bookmark
                    ?.takeIf { it.id == bookmark.id } ?: bookmark
        state.update { it.copy(busy = it.busy + bookmark.id) }
        viewModelScope.launch {
            try {
                action(current)
            } finally {
                state.update { it.copy(busy = it.busy - bookmark.id) }
            }
        }
    }

    /** Shows the new state of a bookmark: replaced in place, or dropped when it left the view. */
    private fun put(bookmark: Bookmark) {
        state.update {
            val stays = it.belongs(bookmark)
            it.copy(
                bookmarks = it.bookmarks.mapNotNull { b -> if (b.id != bookmark.id) b else bookmark.takeIf { stays } },
                reading = it.reading?.let { r -> if (r.bookmark.id == bookmark.id) r.copy(bookmark = bookmark) else r },
            )
        }
        reloadIfLoading()
    }

    /** Drops a bookmark that is gone from the view. */
    private fun remove(id: UUID) {
        state.update { it.copy(bookmarks = it.bookmarks.filterNot { b -> b.id == id }) }
        reloadIfLoading()
    }

    /**
     * A load that started before a local change would bring back the old state of the changed bookmark (a deleted or
     * read one again): it is replaced by a new load.
     */
    private fun reloadIfLoading() {
        if (loadJob?.isActive == true) refresh(pulled = state.value.refreshing)
    }

    /**
     * A refused change: a bookmark gone meanwhile is dropped, an outdated one is loaded again for the next try.
     * Returns the latest known version of [current], or `null` when it is gone.
     */
    private suspend fun failed(
        current: Bookmark,
        result: BookmarksResult<*>,
    ): Bookmark? {
        val status = (result as? BookmarksResult.Failed)?.status
        if (status == BookmarksRepository.NOT_FOUND) {
            remove(current.id)
            return null
        }
        show(BookmarksSnackbar.Failed(failure(result), nextSnackbar()))
        if (status !in BookmarksRepository.CONFLICTS) return current
        return when (val fresh = bookmarks.get(current.id)) {
            is BookmarksResult.Ok -> fresh.value.also { put(it) }
            is BookmarksResult.Failed -> {
                if (fresh.status != BookmarksRepository.NOT_FOUND) return current
                remove(current.id)
                null
            }
            BookmarksResult.Offline -> current
        }
    }

    private fun failure(result: BookmarksResult<*>): BookmarkFailure =
        when {
            result == BookmarksResult.Offline -> BookmarkFailure.OFFLINE
            (result as? BookmarksResult.Failed)?.status in BookmarksRepository.CONFLICTS -> BookmarkFailure.CONFLICT
            else -> BookmarkFailure.FAILED
        }

    private fun show(snackbar: BookmarksSnackbar) = state.update { it.copy(snackbar = snackbar) }

    private fun nextSnackbar() = ++snackbars

    private companion object {
        const val BAD_REQUEST = 400
    }

    private fun reloadTags() {
        viewModelScope.launch {
            val tags = bookmarks.tags()
            if (tags is BookmarksResult.Ok) state.update { it.copy(tags = tags.value) }
        }
    }
}

/** Whether [bookmark] is shown in the current view and tag. */
private fun BookmarksUiState.belongs(bookmark: Bookmark): Boolean =
    (view == BookmarkView.ALL || bookmark.inQueue) &&
        (tag == null || bookmark.tags.any { it.equals(tag, ignoreCase = true) })
