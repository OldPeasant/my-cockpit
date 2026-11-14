package ch.sonensei.cockpit.mail.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.gtd.data.MailRefreshTriggers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Why the list may be outdated, or what did not work. */
enum class MailListMessage {
    OFFLINE,
    FAILED,
    ARCHIVE_FAILED,
    DELETE_FAILED,
    FOLDER_CHANGED,
    SPAM_FAILED,
    MOVE_FAILED,
    MARK_FAILED,
    UNDO_FAILED,
    NEXT_ACTIONS_FAILED,
}

/**
 * The answer of "Next action & archive" on the selection: [created] Next actions, and the subjects of the mails whose
 * task was made but which could not be archived ([notArchived], they stay in the list). [id] tells two answers apart.
 */
data class MailNextActionsDone(
    val created: Int,
    val notArchived: List<String> = emptyList(),
    val id: Long = 0,
)

/** Why "load older from server" showed nothing new: no connection, a refusal, or nothing older on the server. */
enum class OlderMessage { OFFLINE, FAILED, NOTHING_OLDER }

/**
 * What [action] just did to [mails] (as they were before), offered to undo: a marking action by its inverse, a move
 * (also archive, delete and spam) by moving each mail back to its folder. [positions] are the mails' places in the list
 * of [mailbox], so undoing puts them back there. [id] tells two equal actions apart.
 */
data class MailUndo(
    val action: MailListAction,
    val mails: List<MailSummary>,
    val mailbox: Mailbox,
    val positions: Map<UUID, Int> = emptyMap(),
    val id: Long = 0,
)

/** What a folder dialog does. */
enum class FolderEditKind { CREATE, RENAME, DELETE }

/** Why a folder dialog could not do it; the dialog stays open and shows it. */
enum class FolderEditError { NAME_TAKEN, INVALID_NAME, UNREACHABLE, CANNOT_DELETE, FAILED }

/**
 * An open folder dialog: a new folder in [folder] (or at the top level of [accountId] when `null`), or renaming or
 * deleting [folder]. [busy] while the server works on it.
 */
data class FolderEdit(
    val kind: FolderEditKind,
    val accountId: UUID,
    val folder: MailFolder?,
    val busy: Boolean = false,
    val error: FolderEditError? = null,
)

/**
 * The open search of the list: [query] in [mailbox] (the unified Inbox searches all folders), on the mail servers
 * too when [server]. [loaded] once an answer came; a search on the server keeps the cached results shown meanwhile.
 */
data class MailSearchState(
    val mailbox: Mailbox,
    val query: String = "",
    val server: Boolean = false,
    val results: List<MailSummary> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    val failedAccounts: List<UUID> = emptyList(),
    val message: MailListMessage? = null,
    /** Changes each time an answer replaces [results], so the list can start again at the top. */
    val version: Long = 0,
) {
    /** "Search on server" is offered once a cached search answered. */
    val canSearchServer: Boolean get() = query.isNotEmpty() && !server && !loading && loaded
}

data class MailListUiState(
    val mailbox: Mailbox = Mailbox.Unified,
    val accounts: List<MailAccountFolders> = emptyList(),
    val messages: List<MailSummary> = emptyList(),
    /** The cursor of the page after the loaded ones; `null` when everything is loaded. */
    val nextCursor: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** Older messages are being loaded from the mail server ("load older from server"). */
    val loadingOlder: Boolean = false,
    /** Why the last "load older from server" added nothing; shown at the end of the list, `null` otherwise. */
    val olderMessage: OlderMessage? = null,
    /** The mailbox was loaded at least once. */
    val loaded: Boolean = false,
    val message: MailListMessage? = null,
    val folderEdit: FolderEdit? = null,
    /** The open search, shown instead of the mailbox; `null` when closed. */
    val search: MailSearchState? = null,
    /** The mails picked by a long press (multi-select); empty when not selecting. */
    val selected: Set<UUID> = emptySet(),
    /** The last action that can be undone, offered until the next one or until dismissed. */
    val undo: MailUndo? = null,
    /** The last "Next action & archive" on the selection, shown as a snackbar until dismissed. */
    val nextActions: MailNextActionsDone? = null,
    /** "Next action & archive" on the selection is waiting for the server. */
    val makingNextActions: Boolean = false,
) {
    val selecting: Boolean get() = selected.isNotEmpty()

    /** Whether mail [id] (listed or found) lies in a Drafts folder: opening it continues the draft in compose. */
    fun isDraft(id: UUID): Boolean {
        val mail = messages.firstOrNull { it.id == id } ?: search?.results?.firstOrNull { it.id == id } ?: return false
        val folderId = mail.folderId ?: return false
        return accounts.any { a -> a.folders.any { it.id == folderId && it.role == FolderRole.DRAFTS } }
    }

    /** The selected mails, in list order. */
    val selection: List<MailSummary> get() = messages.filter { it.id in selected }

    /**
     * The folders the selection can be moved to: those of its account (all selected mails must be of one account),
     * without the folder all of them are in already. Empty when the selection spans accounts.
     */
    val moveTargets: List<MailFolder>
        get() {
            val mails = selection
            val accountId = mails.firstOrNull()?.accountId ?: return emptyList()
            if (mails.any { it.accountId != accountId }) return emptyList()
            val folders = accounts.firstOrNull { it.account.id == accountId }?.folders.orEmpty()
            val shared = mails.map { it.folderId }.distinct().singleOrNull()
            return folders.filterNot { it.id == shared }
        }

    val folder: MailFolder?
        get() =
            (mailbox as? Mailbox.Folder)?.let { box ->
                accounts.firstNotNullOfOrNull { a ->
                    a.folders.firstOrNull {
                        it.id ==
                            box.folderId
                    }
                }
            }

    /** Archiving a mail that is already in the Archive does nothing: the swipe is not offered there. */
    val canArchive: Boolean get() = folder?.role != FolderRole.ARCHIVE

    /** Names the account of each row in the unified Inbox when there is more than one. */
    val showAccounts: Boolean get() = mailbox == Mailbox.Unified && accounts.size > 1

    /** Names the account of each search result when all accounts are searched and there is more than one. */
    val showSearchAccounts: Boolean get() = search?.mailbox == Mailbox.Unified && accounts.size > 1

    /** The end of a folder's cached mail is shown: "load older from server" is offered there (SPEC.md §7.2). */
    val canLoadOlder: Boolean
        get() =
            mailbox is Mailbox.Folder &&
                search == null &&
                loaded &&
                !loading &&
                nextCursor == null &&
                message != MailListMessage.OFFLINE &&
                message != MailListMessage.FAILED

    fun account(id: UUID?): MailAccount? = accounts.firstOrNull { it.account.id == id }?.account
}

/**
 * The unified Inbox or one folder, loaded page by page as it is scrolled, with the swipe actions archive and delete.
 * A swiped mail leaves the list at once and comes back if the server could not move it.
 */
@HiltViewModel
class MailListViewModel
    @Inject
    constructor(
        private val repository: MailRepository,
        private val mailChanges: MailRefreshTriggers = MailRefreshTriggers.NONE,
    ) : ViewModel() {
        private val state = MutableStateFlow(MailListUiState())
        val uiState: StateFlow<MailListUiState> = state.asStateFlow()

        private var loadJob: Job? = null
        private var folderJob: Job? = null
        private var searchJob: Job? = null

        /** Each search takes the next number; only the newest one's answer is shown. */
        private var searchGeneration = 0L

        /**
         * Who may still write the folder lists: each load, folder reload and applied folder change takes the next
         * number, and an answer is only stored while its number is the newest, so an older snapshot never undoes a change.
         */
        private var folderGeneration = 0L

        /** Numbers the undos, so the same action twice is offered twice. */
        private var undoCount = 0L

        /** Numbers the actions (undos included) as they start; only the newest one's answer may offer its undo. */
        private var actionCount = 0L

        /** Loads the accounts and the first page of the current mailbox again. */
        fun refresh() = load(state.value.mailbox)

        /**
         * Reloads the first page of the current mailbox (like [refresh]) on every mail change on the server while
         * collected; the screen collects it only while shown. Search results are not reloaded, a load already running is
         * not started again, and a failed reload keeps what is shown (no loading indicator, no new error message).
         */
        suspend fun followMailChanges() =
            mailChanges.changes().collect {
                if (loadJob?.isActive != true) load(state.value.mailbox, quiet = true)
            }

        /** Shows [mailbox] from its newest mail. */
        fun select(mailbox: Mailbox) {
            if (mailbox == state.value.mailbox && state.value.loaded) return
            state.update {
                it.copy(
                    mailbox = mailbox,
                    messages = emptyList(),
                    nextCursor = null,
                    loaded = false,
                    selected = emptySet(),
                    olderMessage = null,
                )
            }
            load(mailbox)
        }

        /** The list was scrolled near its end: appends the next page. */
        fun loadMore() {
            val current = state.value
            val cursor = current.nextCursor ?: return
            if (current.loading || current.loadingMore) return
            state.update { it.copy(loadingMore = true) }
            loadJob =
                viewModelScope.launch {
                    val result = repository.messages(current.mailbox, cursor)
                    state.update {
                        when (result) {
                            is MailResult.Ok ->
                                it.copy(
                                    messages = (it.messages + result.value.items).distinctBy { m -> m.id },
                                    nextCursor = result.value.nextCursor,
                                    loadingMore = false,
                                    message = null,
                                )
                            MailResult.Offline -> it.copy(loadingMore = false, message = MailListMessage.OFFLINE)
                            is MailResult.Failed -> it.copy(loadingMore = false, message = MailListMessage.FAILED)
                        }
                    }
                }
        }

        /**
         * Loads mail older than the sync window of the folder shown from the mail server, then shows the newly cached
         * mails at the end of the list. A failure, or nothing older, is shown at the end of the list; the list stays.
         */
        fun loadOlder() {
            val current = state.value
            val folder = current.mailbox as? Mailbox.Folder ?: return
            if (!current.canLoadOlder || current.loadingOlder || current.loadingMore) return
            state.update { it.copy(loadingOlder = true, olderMessage = null) }
            loadJob =
                viewModelScope.launch {
                    val added = repository.loadOlder(folder)
                    val result =
                        when (added) {
                            is MailResult.Ok ->
                                if (added.value >
                                    0
                                ) {
                                    repository.messagesUpTo(folder, current.messages.size + added.value)
                                } else {
                                    null
                                }
                            else -> null
                        }
                    state.update { s ->
                        if (s.mailbox != folder) return@update s.copy(loadingOlder = false)
                        val failure = olderFailure(added, result)
                        if (failure != null || result !is MailResult.Ok) {
                            s.copy(loadingOlder = false, olderMessage = failure)
                        } else {
                            val messages = result.value.items
                            s.copy(
                                messages = messages,
                                nextCursor = result.value.nextCursor,
                                selected = s.selected.filterTo(HashSet()) { id -> messages.any { it.id == id } },
                                loadingOlder = false,
                                olderMessage = null,
                            )
                        }
                    }
                }
        }

        /** A swipe archived or deleted [id]. */
        fun act(
            id: UUID,
            action: MailListAction,
        ) = perform(listOf(id), action)

        /** A long press: starts the multi-selection with [id], or adds or removes [id] while selecting. */
        fun toggleSelection(id: UUID) {
            if (state.value.messages.none { it.id == id }) return
            state.update { s -> s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id) }
        }

        /** Ends the multi-selection. */
        fun clearSelection() = state.update { it.copy(selected = emptySet()) }

        /** Applies [action] (not MOVE, see [moveSelection]) to the selected mails; the selection ends. */
        fun actOnSelection(action: MailListAction) {
            if (action == MailListAction.MOVE) return
            perform(state.value.selection.map { it.id }, action)
        }

        /** Moves the selected mails to [folder] (one of [MailListUiState.moveTargets]); the selection ends. */
        fun moveSelection(folder: MailFolder) {
            if (state.value.moveTargets.none { it.id == folder.id }) return
            perform(state.value.selection.map { it.id }, MailListAction.MOVE, folder.id)
        }

        /**
         * "Next action & archive" (SPEC.md §7.3) on the selected mails: one Next action per mail, then the mails are
         * archived and leave the list, which stays shown. The selection ends; a mail that could not be archived stays and
         * is named in the answer, a failed call leaves every mail in place with a message.
         */
        fun nextActionsForSelection() {
            val before = state.value
            val ids = before.selection.map { it.id }
            if (ids.isEmpty() || before.makingNextActions) return
            val subjects = before.selection.associate { it.id to it.subject }
            state.update { it.copy(selected = emptySet(), makingNextActions = true) }
            viewModelScope.launch {
                val result = repository.toNextActions(ids)
                state.update { s ->
                    when (result) {
                        is MailResult.Ok -> {
                            val archived = result.value.filter { it.archived }.mapTo(HashSet()) { it.mailId }
                            val kept =
                                result.value.filterNot { it.archived }.map {
                                    subjects[it.mailId]?.takeIf { t -> t.isNotBlank() }
                                        ?: it.title
                                }
                            s.copy(
                                messages = s.messages.filterNot { it.id in archived },
                                makingNextActions = false,
                                nextActions = MailNextActionsDone(result.value.size, kept, ++undoCount),
                            )
                        }
                        else -> s.copy(makingNextActions = false, message = MailListMessage.NEXT_ACTIONS_FAILED)
                    }
                }
            }
        }

        /** The "N next actions created" snackbar was closed. */
        fun dismissNextActions(done: MailNextActionsDone) = state.update { if (it.nextActions == done) it.copy(nextActions = null) else it }

        /** The undo was not taken: forgets it. */
        fun dismissUndo(undo: MailUndo) = state.update { if (it.undo == undo) it.copy(undo = null) else it }

        /** Undoes [undo] (when it is still the one offered). */
        fun undo(undo: MailUndo) {
            if (state.value.undo != undo) return
            state.update { it.copy(undo = null) }
            actionCount++
            val inverse = undo.action.inverse
            if (inverse != null) unmark(undo, inverse) else moveBack(undo)
        }

        /**
         * Sends [inverse] for the mails of [undo] (which the action changed), also when another mailbox is shown now; the
         * list changes the ones it shows at once, and changes them again if the server could not undo it.
         */
        private fun unmark(
            undo: MailUndo,
            inverse: MailListAction,
        ) {
            val ids = undo.mails.mapTo(HashSet()) { it.id }
            state.update { s -> s.copy(messages = s.messages.changed(ids, inverse)) }
            viewModelScope.launch {
                val done = (repository.actAll(undo.mails.map { it.id }, inverse, null) as? MailResult.Ok)?.value.orEmpty()
                val failed = ids - done
                state.update { s ->
                    // A reload that overtook the call may have brought the marked state back.
                    val messages = s.messages.changed(done, inverse).changed(failed, undo.action)
                    s.copy(messages = messages, message = if (failed.isEmpty()) s.message else MailListMessage.UNDO_FAILED)
                }
            }
        }

        /**
         * Applies [action] to [ids] at once in the list: a removing action takes the mails out, a marking action changes
         * them (only those it changes are sent, so its undo changes nothing else). Mails the server could not do it for
         * come back as they were, with a message. Afterwards the action is offered to undo when it can be undone (see
         * [undoFor]) and no newer action started meanwhile.
         */
        private fun perform(
            ids: List<UUID>,
            action: MailListAction,
            target: UUID? = null,
        ) {
            val before = state.value
            val wanted = ids.toSet()
            val listed = before.messages.filter { it.id in wanted }
            if (listed.isEmpty()) return
            if (action == MailListAction.ARCHIVE && !before.canArchive) return
            val mails = if (action.removes) listed else listed.filter { action.applyTo(it) != it }
            if (mails.isEmpty()) {
                state.update { s -> s.copy(selected = s.selected - wanted) }
                return
            }
            val generation = ++actionCount
            val failure = failureOf(action)
            val positions =
                before.messages
                    .withIndex()
                    .filter { it.value.id in wanted }
                    .associate { it.value.id to it.index }
            val mailbox = before.mailbox
            val acted = mails.mapTo(HashSet()) { it.id }
            state.update { s -> s.copy(messages = s.messages.changed(acted, action), selected = s.selected - wanted, undo = null) }
            viewModelScope.launch {
                val done = (repository.actAll(mails.map { it.id }, action, target) as? MailResult.Ok)?.value.orEmpty()
                val failed = mails.filterNot { it.id in done }
                val newest = generation == actionCount
                val undo = undoFor(action, mails.filter { it.id in done }, mailbox, positions)?.takeIf { newest && failed.isEmpty() }
                state.update { s ->
                    // A reload that overtook the call may have brought the old state back.
                    var messages = s.messages.changed(done, action)
                    // Another mailbox shown meanwhile never gets the mails back.
                    if (failed.isNotEmpty() && s.mailbox == mailbox) messages = messages.restored(failed, positions, action)
                    s.copy(
                        messages = messages,
                        message = if (failed.isEmpty()) s.message else failure,
                        undo = undo?.copy(id = ++undoCount) ?: s.undo,
                    )
                }
            }
        }

        /**
         * The undo of [action] on [mails]: its inverse for a marking action; for a move, moving each mail back to its
         * folder, unless one is in no known folder or was deleted for good (deleted in Trash).
         */
        private fun undoFor(
            action: MailListAction,
            mails: List<MailSummary>,
            mailbox: Mailbox,
            positions: Map<UUID, Int>,
        ): MailUndo? {
            if (mails.isEmpty()) return null
            if (action.removes) {
                val folders =
                    state.value.accounts
                        .flatMap { it.folders }
                        .associateBy { it.id }
                val lost =
                    mails.any { m ->
                        val folder = m.folderId?.let(folders::get)
                        folder == null || (action == MailListAction.DELETE && folder.role == FolderRole.TRASH)
                    }
                if (lost) return null
            }
            return MailUndo(action, mails, mailbox, positions)
        }

        /** Moves the mails of [undo] back to their folders: they return to the list at once, and leave it again if that fails. */
        private fun moveBack(undo: MailUndo) {
            val ids = undo.mails.mapTo(HashSet()) { it.id }
            state.update { s ->
                if (s.mailbox == undo.mailbox) s.copy(messages = s.messages.restored(undo.mails, undo.positions, undo.action)) else s
            }
            viewModelScope.launch {
                val done = HashSet<UUID>()
                undo.mails.groupBy { checkNotNull(it.folderId) }.forEach { (folderId, group) ->
                    (repository.actAll(group.map { it.id }, MailListAction.MOVE, folderId) as? MailResult.Ok)?.value?.let(done::addAll)
                }
                val failed = ids - done
                val message = MailListMessage.UNDO_FAILED.takeIf { failed.isNotEmpty() }
                if (message != null) {
                    state.update { s -> s.copy(messages = s.messages.filterNot { it.id in failed }, message = message) }
                }
                // The server may have given the returned mails new ids: the list shows its current state, still saying
                // that some could not be moved back.
                if (done.isNotEmpty()) load(state.value.mailbox, message)
            }
        }

        /** The read view marked [id] (read, unread, flagged or not); the list shows it at once. */
        fun marked(
            id: UUID,
            action: MailListAction,
        ) {
            if (action.removes) return
            state.update { s ->
                s.copy(
                    messages = s.messages.map { if (it.id == id) action.applyTo(it) else it },
                    search = s.search?.let { q -> q.copy(results = q.results.map { if (it.id == id) action.applyTo(it) else it }) },
                )
            }
        }

        /** The read view opened [id] (and marks it read on the server). */
        fun markSeen(id: UUID) =
            state.update { s ->
                s.copy(
                    messages = s.messages.map { if (it.id == id) it.copy(seen = true) else it },
                    search = s.search?.let { q -> q.copy(results = q.results.map { if (it.id == id) it.copy(seen = true) else it }) },
                )
            }

        /** The read view archived or deleted [id]. */
        fun removed(id: UUID) =
            state.update { s ->
                s.copy(
                    messages = s.messages.filterNot { it.id == id },
                    search = s.search?.let { q -> q.copy(results = q.results.filterNot { it.id == id }) },
                )
            }

        /** Opens the search box for the mailbox shown; an open search stays as it is. */
        fun openSearch() {
            if (state.value.search == null) state.update { it.copy(search = MailSearchState(it.mailbox), selected = emptySet()) }
        }

        /** Searches the cached mail for [query] (trimmed, at most [MAX_QUERY] characters); a blank query does nothing. */
        fun search(query: String) {
            val search = state.value.search ?: return
            val text = query.trim().take(MAX_QUERY)
            if (text.isEmpty()) return
            runSearch(MailSearchState(search.mailbox, query = text, loading = true, version = search.version))
        }

        /**
         * Searches [query] (the text in the box, trimmed, at most [MAX_QUERY] characters) on the mail servers too. For the
         * query shown the cached results stay until it answers; a query edited since starts from empty results.
         */
        fun searchOnServer(
            query: String =
                state.value.search
                    ?.query
                    .orEmpty(),
        ) {
            val search = state.value.search ?: return
            if (!search.canSearchServer) return
            val text = query.trim().take(MAX_QUERY)
            if (text.isEmpty()) return
            runSearch(
                if (text == search.query) {
                    search.copy(server = true, loading = true, message = null)
                } else {
                    MailSearchState(search.mailbox, query = text, server = true, loading = true, version = search.version)
                },
            )
        }

        /** Runs the shown search again after it failed. */
        fun retrySearch() {
            val search = state.value.search ?: return
            if (search.query.isEmpty() || search.loading) return
            runSearch(search.copy(loading = true, message = null))
        }

        /** Closes the search; the mailbox shows again as it was. */
        fun closeSearch() {
            searchJob?.cancel()
            searchGeneration++
            state.update { it.copy(search = null) }
        }

        private fun runSearch(search: MailSearchState) {
            searchJob?.cancel()
            val generation = ++searchGeneration
            state.update { it.copy(search = search) }
            searchJob =
                viewModelScope.launch {
                    val result = repository.search(search.query, search.mailbox, search.server)
                    if (generation != searchGeneration) return@launch
                    state.update { s ->
                        val current = s.search ?: return@update s
                        val next =
                            when (result) {
                                is MailResult.Ok ->
                                    current.copy(
                                        results = result.value.items,
                                        hasMore = result.value.hasMore,
                                        failedAccounts = result.value.failedAccounts,
                                        loading = false,
                                        loaded = true,
                                        message = null,
                                        version = generation,
                                    )
                                // A failed search keeps what was shown (the cached results of a search on the server); Retry asks again.
                                MailResult.Offline -> current.copy(loading = false, message = MailListMessage.OFFLINE)
                                is MailResult.Failed -> current.copy(loading = false, message = MailListMessage.FAILED)
                            }
                        s.copy(search = next)
                    }
                }
        }

        /** Opens the dialog for a new folder in [parent], or at the top level of [accountId]. */
        fun startCreateFolder(
            accountId: UUID,
            parent: MailFolder? = null,
        ) = state.update { it.copy(folderEdit = FolderEdit(FolderEditKind.CREATE, accountId, parent)) }

        /** Opens the rename dialog of [folder] (not offered for INBOX). */
        fun startRenameFolder(folder: MailFolder) {
            if (folder.canRename) state.update { it.copy(folderEdit = FolderEdit(FolderEditKind.RENAME, folder.accountId, folder)) }
        }

        /** Asks to confirm deleting [folder] (not offered for special folders). */
        fun startDeleteFolder(folder: MailFolder) {
            if (folder.canDelete) state.update { it.copy(folderEdit = FolderEdit(FolderEditKind.DELETE, folder.accountId, folder)) }
        }

        /** Closes the folder dialog; a call already sent still finishes, and a success still shows in the folder list. */
        fun cancelFolderEdit() = state.update { it.copy(folderEdit = null) }

        /** Does what the open folder dialog asks for; [name] is the new name (ignored when deleting). */
        fun submitFolderEdit(name: String = "") {
            val edit = state.value.folderEdit ?: return
            if (edit.busy) return
            val trimmed = name.trim()
            if (edit.kind != FolderEditKind.DELETE && trimmed.isEmpty()) {
                state.update { it.copy(folderEdit = edit.copy(error = FolderEditError.INVALID_NAME)) }
                return
            }
            val busy = edit.copy(busy = true, error = null)
            state.update { it.copy(folderEdit = busy) }
            viewModelScope.launch {
                when (edit.kind) {
                    FolderEditKind.CREATE -> {
                        val result = repository.createFolder(edit.accountId, edit.folder, trimmed)
                        finishFolderEdit(busy, result) { accounts, folder -> accounts.withFolder(folder) }
                    }
                    FolderEditKind.RENAME -> {
                        val result = repository.renameFolder(checkNotNull(edit.folder), trimmed)
                        finishFolderEdit(busy, result) { accounts, folder -> accounts.withFolder(folder) }
                    }
                    FolderEditKind.DELETE -> {
                        val folder = checkNotNull(edit.folder)
                        val removed =
                            state.value.accounts
                                .flatMap { it.folders }
                                .filter { it.id == folder.id || folder.contains(it) }
                                .mapTo(HashSet()) { it.id }
                        val result = repository.deleteFolder(folder)
                        val applied = finishFolderEdit(busy, result) { accounts, _ -> accounts.withoutFolder(folder) }
                        val shown = state.value.mailbox as? Mailbox.Folder
                        if (applied && shown != null && (shown.folderId == folder.id || shown.folderId in removed)) {
                            select(Mailbox.Unified)
                        }
                    }
                }
            }
        }

        /**
         * Applies the answer to [edit] (`true` when it succeeded). A success changes the folder list at once (and reloads
         * it for what else moved, such as renamed subfolders), also when the dialog was closed meanwhile. A stale ETag, or
         * a folder to rename or delete that is gone already, reloads the folders and says so; other failures are shown in
         * the dialog, which stays open.
         */
        private fun <T> finishFolderEdit(
            edit: FolderEdit,
            result: MailResult<T>,
            change: (List<MailAccountFolders>, T) -> List<MailAccountFolders>,
        ): Boolean {
            fun FolderEdit?.after(next: FolderEdit?) = if (this == edit) next else this
            val stale =
                result is MailResult.Failed &&
                    (result.status == PRECONDITION_FAILED || (result.status == NOT_FOUND && edit.kind != FolderEditKind.CREATE))
            when {
                result is MailResult.Ok -> {
                    folderGeneration++
                    state.update { it.copy(accounts = change(it.accounts, result.value), folderEdit = it.folderEdit.after(null)) }
                }
                stale -> state.update { it.copy(folderEdit = it.folderEdit.after(null), message = MailListMessage.FOLDER_CHANGED) }
                else ->
                    state.update {
                        it.copy(folderEdit = it.folderEdit.after(edit.copy(busy = false, error = folderError(edit.kind, result))))
                    }
            }
            if (result is MailResult.Ok || stale) reloadFolders()
            return result is MailResult.Ok
        }

        /** Loads the folder lists again, leaving the mails alone; a reload still running is dropped. */
        private fun reloadFolders() {
            folderJob?.cancel()
            val generation = ++folderGeneration
            folderJob =
                viewModelScope.launch {
                    val result = repository.accounts() as? MailResult.Ok ?: return@launch
                    if (generation == folderGeneration) state.update { it.copy(accounts = result.value) }
                }
        }

        /** Loads the accounts and the first page of [mailbox], then shows [message]; a load still running is dropped. */
        private fun load(
            mailbox: Mailbox,
            message: MailListMessage? = null,
            quiet: Boolean = false,
        ) {
            loadJob?.cancel()
            if (!quiet) {
                state.update { it.copy(loading = true, loadingMore = false, loadingOlder = false, olderMessage = null) }
            }
            loadJob =
                viewModelScope.launch {
                    val generation = ++folderGeneration
                    val accounts = repository.accounts()
                    val result = repository.messages(mailbox, null)
                    state.update { current ->
                        val withAccounts =
                            (accounts as? MailResult.Ok)
                                ?.takeIf { generation == folderGeneration }
                                ?.let { current.copy(accounts = it.value) } ?: current
                        when (result) {
                            is MailResult.Ok -> {
                                val (messages, cursor) = mergeFirstPage(current, mailbox, result.value)
                                withAccounts.copy(
                                    messages = messages,
                                    selected = current.selected.filterTo(HashSet()) { id -> messages.any { it.id == id } },
                                    nextCursor = cursor,
                                    loading = false,
                                    loaded = true,
                                    // A quiet reload keeps an action's message; only "outdated" is no longer true.
                                    message = if (quiet) current.message.takeUnless { it in STALE } else message,
                                )
                            }
                            MailResult.Offline ->
                                if (quiet) withAccounts else withAccounts.copy(loading = false, message = MailListMessage.OFFLINE)
                            is MailResult.Failed ->
                                if (quiet) withAccounts else withAccounts.copy(loading = false, message = MailListMessage.FAILED)
                        }
                    }
                }
        }

        /**
         * The mails and cursor after [page], the newest mails of [mailbox]. Reloading the mailbox shown keeps the pages
         * already loaded behind it (from the page's last mail on), so a refresh does not lose the scroll position.
         */
        private fun mergeFirstPage(
            current: MailListUiState,
            mailbox: Mailbox,
            page: MailPage,
        ): Pair<List<MailSummary>, String?> {
            val last = page.items.lastOrNull()
            val at = last?.let { l -> current.messages.indexOfFirst { it.id == l.id } } ?: -1
            if (current.mailbox != mailbox || !current.loaded || page.nextCursor == null || at < 0) {
                return page.items to page.nextCursor
            }
            val ids = page.items.mapTo(HashSet()) { it.id }
            val older = current.messages.drop(at + 1).filterNot { it.id in ids }
            return if (older.isEmpty()) page.items to page.nextCursor else (page.items + older) to current.nextCursor
        }
    }

/** The messages saying the list may be outdated: a successful reload ends them. */
private val STALE = setOf(MailListMessage.OFFLINE, MailListMessage.FAILED)

/** Why "load older" added nothing: [added] failed or was zero, or reading the list afterwards ([listed]) failed. */
private fun olderFailure(
    added: MailResult<Int>,
    listed: MailResult<MailPage>?,
): OlderMessage? =
    when {
        added is MailResult.Offline || listed is MailResult.Offline -> OlderMessage.OFFLINE
        added is MailResult.Failed || listed is MailResult.Failed -> OlderMessage.FAILED
        added is MailResult.Ok && added.value == 0 -> OlderMessage.NOTHING_OLDER
        else -> null
    }

/** The message shown when the server could not do [action]. */
private fun failureOf(action: MailListAction): MailListMessage =
    when (action) {
        MailListAction.ARCHIVE -> MailListMessage.ARCHIVE_FAILED
        MailListAction.DELETE -> MailListMessage.DELETE_FAILED
        MailListAction.SPAM -> MailListMessage.SPAM_FAILED
        MailListAction.MOVE -> MailListMessage.MOVE_FAILED
        MailListAction.READ, MailListAction.UNREAD, MailListAction.FLAG, MailListAction.UNFLAG -> MailListMessage.MARK_FAILED
    }

/** The list after [action] on [ids]: without them for a removing action, else with them changed. */
private fun List<MailSummary>.changed(
    ids: Set<UUID>,
    action: MailListAction,
): List<MailSummary> = if (action.removes) filterNot { it.id in ids } else map { if (it.id in ids) action.applyTo(it) else it }

/**
 * The list with [mails] back as they were: a removed mail returns to its place in [positions] (unless a reload brought
 * it back already), a marked one gets its old state again.
 */
private fun List<MailSummary>.restored(
    mails: List<MailSummary>,
    positions: Map<UUID, Int>,
    action: MailListAction,
): List<MailSummary> {
    if (!action.removes) {
        val old = mails.associateBy { it.id }
        return map { old[it.id] ?: it }
    }
    val result = toMutableList()
    val present = mapTo(HashSet()) { it.id }
    val returning = mails.filter { it.id !in present }.sortedBy { positions[it.id] ?: Int.MAX_VALUE }
    val back = returning.mapTo(HashSet()) { it.id }
    returning.forEach { m ->
        val at = positions[m.id]
        // The mails that were above it and stay away (moved for good) no longer take a place.
        val index = if (at == null) result.size else at - positions.count { (id, p) -> p < at && id !in back && id !in present }
        result.add(index.coerceIn(0, result.size), m)
    }
    return result
}

/** The longest query the server takes. */
internal const val MAX_QUERY = 200

private const val PRECONDITION_FAILED = 412
private const val BAD_REQUEST = 400
private const val CONFLICT = 409
private const val NOT_FOUND = 404

private fun folderError(
    kind: FolderEditKind,
    result: MailResult<*>,
): FolderEditError =
    when {
        result is MailResult.Offline -> FolderEditError.UNREACHABLE
        result is MailResult.Failed && result.status == BAD_REQUEST -> FolderEditError.INVALID_NAME
        result is MailResult.Failed && result.status == CONFLICT ->
            if (kind == FolderEditKind.DELETE) FolderEditError.CANNOT_DELETE else FolderEditError.NAME_TAKEN
        else -> FolderEditError.FAILED
    }

/** The folder lists with [folder] added or replaced (by id), in the list's order. */
internal fun List<MailAccountFolders>.withFolder(folder: MailFolder): List<MailAccountFolders> =
    map { entry ->
        if (entry.account.id != folder.accountId) {
            entry
        } else {
            entry.copy(folders = (entry.folders.filterNot { it.id == folder.id } + folder).sortedWith(folderOrder))
        }
    }

/** The folder lists without [folder] and its subfolders. */
internal fun List<MailAccountFolders>.withoutFolder(folder: MailFolder): List<MailAccountFolders> =
    map { entry -> entry.copy(folders = entry.folders.filterNot { it.id == folder.id || folder.contains(it) }) }
