package ch.sonensei.cockpit.gtd.next

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.core.settings.SettingsUpdateResult
import ch.sonensei.cockpit.core.settings.UserSettingsChange
import ch.sonensei.cockpit.core.settings.UserSettingsRepository
import ch.sonensei.cockpit.gtd.data.Cached
import ch.sonensei.cockpit.gtd.data.ContextRepository
import ch.sonensei.cockpit.gtd.data.Creativity
import ch.sonensei.cockpit.gtd.data.Energy
import ch.sonensei.cockpit.gtd.data.GtdContext
import ch.sonensei.cockpit.gtd.data.GtdProject
import ch.sonensei.cockpit.gtd.data.GtdTask
import ch.sonensei.cockpit.gtd.data.ProjectRepository
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.gtd.data.TaskRepository
import ch.sonensei.cockpit.gtd.data.TaskView
import ch.sonensei.cockpit.gtd.lists.Live
import ch.sonensei.cockpit.gtd.projects.Area
import ch.sonensei.cockpit.gtd.projects.ProjectWorkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/**
 * The filters of the Next Actions view (SPEC.md §5.3), applied to the cached Next list so they also work offline. Like
 * the server's filters, a set filter only matches tasks that have the attribute (e.g. [maxMinutes] needs an estimate,
 * [due] a due date, [areaId] a project in that area).
 */
data class NextFilter(
    val contextId: UUID? = null,
    val energy: Energy? = null,
    val creativity: Creativity? = null,
    /** Only tasks estimated at most this many minutes. */
    val maxMinutes: Int? = null,
    /** Only tasks of a project in this area. */
    val areaId: UUID? = null,
    val projectId: UUID? = null,
    val due: DueWindow? = null,
) {
    val activeCount: Int get() = listOfNotNull(contextId, energy, creativity, maxMinutes, areaId, projectId, due).size

    /** Whether [task] matches; [today] decides the due window, [projects] the area of the task's project. */
    fun matches(
        task: GtdTask,
        today: LocalDate = LocalDate.now(),
        projects: Map<UUID, GtdProject> = emptyMap(),
    ): Boolean =
        (contextId == null || contextId in task.contextIds) &&
            (energy == null || task.energy == energy) &&
            (creativity == null || task.creativity == creativity) &&
            (maxMinutes == null || (task.timeEstimateMin != null && task.timeEstimateMin <= maxMinutes)) &&
            (projectId == null || task.projectId == projectId) &&
            (areaId == null || (task.projectId != null && projects[task.projectId]?.areaId == areaId)) &&
            (due == null || (task.dueDate != null && due.contains(task.dueDate, today)))

    companion object {
        /** The estimate quick picks (SPEC.md §5.1). */
        val ESTIMATES = listOf(5, 15, 30, 60, 120)
    }
}

/** Why a preset could not be saved or deleted. */
enum class PresetMessage {
    NAME_REQUIRED,
    TOO_MANY,

    /** The settings changed elsewhere; they were loaded again, the user may try again. */
    CONFLICT,
    OFFLINE,
    FAILED,
}

/** The open "save filter" dialog: the typed [name], why it could not be saved, and whether it is being saved. */
data class PresetEditor(
    val name: String = "",
    val message: PresetMessage? = null,
    val saving: Boolean = false,
)

/** The saved presets and what is being done with them. */
data class PresetsUiState(
    val presets: List<NextPreset> = emptyList(),
    /** The preset whose filter is the current one, if any. */
    val active: NextPreset? = null,
    /** `null` while the dialog is closed. */
    val editor: PresetEditor? = null,
    /** Why the last delete did not work. */
    val message: PresetMessage? = null,
)

data class NextUiState(
    /** The Next tasks that match [filter], in list order. */
    val tasks: List<GtdTask> = emptyList(),
    /** How many open Next tasks there are without the filter. */
    val total: Int = 0,
    val filter: NextFilter = NextFilter(),
    val contexts: List<GtdContext> = emptyList(),
    /** The areas to choose from, in their order (empty while they are not loaded). */
    val areas: List<Area> = emptyList(),
    /** The active projects to choose from: those of the chosen area if there is one. */
    val projects: List<GtdProject> = emptyList(),
    val presets: PresetsUiState = PresetsUiState(),
    val refreshedAt: Instant? = null,
    /** The server is unreachable: the cached list is shown read-only, completing is disabled. */
    val offline: Boolean = false,
    val snackbar: CompletionSnackbar? = null,
    val today: LocalDate = LocalDate.MIN,
    /** The last load of the Next list failed although the server was reachable. */
    val loadFailed: Boolean = false,
) {
    val loading: Boolean get() = refreshedAt == null && total == 0 && !offline && !loadFailed

    /** Nothing was ever loaded and the last load failed: the screen shows an error with a retry instead of a list. */
    val firstLoadFailed: Boolean get() = refreshedAt == null && total == 0 && loadFailed
}

/** Where the presets are kept: the shared `nextActionPresets` setting. */
class PresetSettings(
    /** The stored preset texts; `null` while the settings are not loaded. */
    val texts: Flow<List<String>?>,
    /** Loads the settings unless they are loaded. */
    val ensureLoaded: suspend () -> Unit,
    /** The stored texts right now; `null` while the settings are not loaded. */
    val current: () -> List<String>?,
    /** Writes the whole list. */
    val store: suspend (List<String>) -> SettingsUpdateResult,
) {
    companion object {
        /** No presets and no way to store them (screens tested without settings). */
        val NONE = PresetSettings(flowOf(emptyList()), {}, { null }, { SettingsUpdateResult.NotLoaded })

        fun of(repository: UserSettingsRepository) =
            PresetSettings(
                texts = repository.settings.map { it?.nextActionPresets },
                ensureLoaded = { if (repository.settings.value == null) repository.refresh() },
                current = { repository.settings.value?.nextActionPresets },
                store = { repository.update(UserSettingsChange(nextActionPresets = it)) },
            )
    }
}

/** The Next Actions view: the cached Next list, filtered, with completion, undo and saved filters (presets). */
@HiltViewModel
class NextViewModel internal constructor(
    private val tasks: TaskRepository,
    contexts: ContextRepository,
    completion: TaskCompletionRepository,
    private val clock: Clock,
    projects: Flow<Cached<List<GtdProject>>> = emptyFlow(),
    areas: Flow<Live<List<Area>>> = emptyFlow(),
    private val presetSettings: PresetSettings = PresetSettings.NONE,
) : ViewModel() {
    @Inject
    constructor(
        tasks: TaskRepository,
        contexts: ContextRepository,
        completion: TaskCompletionRepository,
        projects: ProjectRepository,
        work: ProjectWorkRepository,
        settings: UserSettingsRepository,
    ) : this(
        tasks,
        contexts,
        completion,
        Clock.systemDefaultZone(),
        projects.observe(),
        work.observeAreas(emptyFlow()),
        PresetSettings.of(settings),
    )

    private val completer = TaskCompleter(viewModelScope, completion)
    private val filter = MutableStateFlow(NextFilter())
    private val editor = MutableStateFlow<PresetEditor?>(null)
    private val presetMessage = MutableStateFlow<PresetMessage?>(null)

    private val references: Flow<References> =
        combine(
            contexts.observe(),
            projects.map { it.data }.onStart { emit(emptyList()) },
            areas.map { it.data.orEmpty() }.onStart { emit(emptyList()) },
        ) { c, p, a -> References(c.data, p, a) }

    private val presetParts: Flow<PresetParts> =
        combine(presetSettings.texts.map { NextPresets.read(it.orEmpty()) }, editor, presetMessage, ::PresetParts)

    val uiState: StateFlow<NextUiState> =
        combine(tasks.observe(TaskView.NEXT), references, filter, completer.state, presetParts) { next, refs, f, done, p ->
            val open = next.data.filterNot(done::hides)
            // A context deleted elsewhere can no longer be chosen or cleared, so its filter is dropped.
            val shown = if (f.contextId != null && refs.contexts.none { it.id == f.contextId }) f.copy(contextId = null) else f
            val today = LocalDate.now(clock)
            val byId = refs.projects.associateBy { it.id }
            NextUiState(
                tasks = open.filter { shown.matches(it, today, byId) },
                total = open.size,
                filter = shown,
                contexts = refs.contexts,
                areas = refs.areas,
                projects =
                    refs.projects.filter {
                        it.status == ProjectStatus.ACTIVE && (shown.areaId == null || it.areaId == shown.areaId)
                    },
                presets = PresetsUiState(p.presets, p.presets.firstOrNull { it.filter == shown }, p.editor, p.message),
                refreshedAt = next.refreshedAt,
                offline = next.offline,
                snackbar = done.snackbar,
                today = today,
                loadFailed = next.failure != null,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NextUiState(today = LocalDate.now(clock)))

    init {
        viewModelScope.launch { catching { presetSettings.ensureLoaded() } }
    }

    /** Chooses [id] as the context filter, or clears it when it already is. */
    fun toggleContext(id: UUID) = filter.update { it.copy(contextId = if (it.contextId == id) null else id) }

    fun toggleEnergy(energy: Energy) = filter.update { it.copy(energy = if (it.energy == energy) null else energy) }

    fun toggleCreativity(creativity: Creativity) =
        filter.update { it.copy(creativity = if (it.creativity == creativity) null else creativity) }

    fun toggleMaxMinutes(minutes: Int) = filter.update { it.copy(maxMinutes = if (it.maxMinutes == minutes) null else minutes) }

    fun toggleDue(due: DueWindow) = filter.update { it.copy(due = if (it.due == due) null else due) }

    /** Any due date (or none): the due filter is off. */
    fun clearDue() = filter.update { it.copy(due = null) }

    /** Chooses the area ([id] `null`: any); a chosen project of another area is cleared, it would show nothing. */
    fun chooseArea(id: UUID?) {
        val known = uiState.value.projects
        filter.update { current ->
            val project = current.projectId?.let { pid -> known.firstOrNull { it.id == pid } }
            val keepProject = id == null || project == null || project.areaId == id
            current.copy(areaId = id, projectId = if (keepProject) current.projectId else null)
        }
    }

    /** Chooses the project ([id] `null`: any). */
    fun chooseProject(id: UUID?) = filter.update { it.copy(projectId = id) }

    fun clearFilter() = filter.update { NextFilter() }

    /** Applies [preset]'s filter, or clears the filter when it is the active preset (like the web app). */
    fun applyPreset(preset: NextPreset) {
        presetMessage.value = null
        filter.update { if (it == preset.filter) NextFilter() else preset.filter }
    }

    /** Opens the "save filter" dialog, with the active preset's name if there is one. */
    fun openSavePreset() {
        presetMessage.value = null
        editor.value =
            PresetEditor(
                name =
                    uiState.value.presets.active
                        ?.name
                        .orEmpty(),
            )
    }

    fun editPresetName(name: String) = editor.update { it?.copy(name = name.take(NextPresets.MAX_NAME), message = null) }

    fun cancelSavePreset() {
        editor.value = null
    }

    /** Saves the current filter under the typed name; the dialog closes when it worked. */
    fun savePreset() {
        val open = editor.value ?: return
        if (open.saving) return
        val name = open.name.trim()
        if (name.isEmpty()) {
            editor.value = open.copy(message = PresetMessage.NAME_REQUIRED)
            return
        }
        editor.value = open.copy(saving = true, message = null)
        val current = filter.value
        viewModelScope.launch {
            val message =
                write { texts ->
                    val known = NextPresets.read(texts)
                    if (known.size >= NextPresets.MAX_PRESETS && known.none { NextPresets.sameName(it.name, name) }) {
                        null
                    } else {
                        NextPresets.saved(texts, NextPreset(name, current))
                    }
                }
            editor.update { if (message == null) null else it?.copy(saving = false, message = message) }
        }
    }

    /** Deletes [preset]. */
    fun removePreset(preset: NextPreset) {
        presetMessage.value = null
        viewModelScope.launch { presetMessage.value = write { texts -> NextPresets.removed(texts, preset.name) } }
    }

    /**
     * Writes the list [change] makes of the stored texts (`null`: too many presets). The settings are loaded first: a
     * list built from unloaded settings would wipe the stored presets. Gives `null` when it worked.
     */
    private suspend fun write(change: (List<String>) -> List<String>?): PresetMessage? {
        var tooMany = false
        val result =
            catching {
                presetSettings.ensureLoaded()
                val texts = presetSettings.current()
                val updated = texts?.let(change)
                when {
                    texts == null -> SettingsUpdateResult.NotLoaded
                    updated == null -> {
                        tooMany = true
                        SettingsUpdateResult.Rejected
                    }
                    updated == texts -> SettingsUpdateResult.Updated
                    else -> presetSettings.store(updated)
                }
            } ?: SettingsUpdateResult.Rejected
        return when {
            tooMany -> PresetMessage.TOO_MANY
            result == SettingsUpdateResult.Updated -> null
            result == SettingsUpdateResult.Conflict -> PresetMessage.CONFLICT
            result == SettingsUpdateResult.Offline || result == SettingsUpdateResult.NotLoaded -> PresetMessage.OFFLINE
            else -> PresetMessage.FAILED
        }
    }

    /** Loads the Next list again after a failed load; the screen shows it loading meanwhile. */
    fun retry() {
        viewModelScope.launch { tasks.refresh(TaskView.NEXT) }
    }

    fun complete(task: GtdTask) {
        if (!uiState.value.offline) completer.complete(task)
    }

    fun undo(offer: CompletionSnackbar.Undo) = completer.undo(offer)

    fun dismiss(shown: CompletionSnackbar) = completer.dismiss(shown)

    private data class References(
        val contexts: List<GtdContext>,
        val projects: List<GtdProject>,
        val areas: List<Area>,
    )

    private data class PresetParts(
        val presets: List<NextPreset>,
        val editor: PresetEditor?,
        val message: PresetMessage?,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** [block]'s result, or `null` when it threw (cancellation is passed on). */
private suspend fun <T> catching(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
