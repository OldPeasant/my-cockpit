package ch.sonensei.cockpit.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import ch.sonensei.cockpit.gtd.data.ProjectStatus
import ch.sonensei.cockpit.habits.Habit
import ch.sonensei.cockpit.habits.HabitsRepository
import ch.sonensei.cockpit.habits.HabitsResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/** Why what is shown may be outdated or missing, or why a change was not saved. */
enum class ReviewMessage {
    /** The review could not be loaded: no connection. */
    OFFLINE,

    /** The review could not be loaded: the server refused it. */
    FAILED,

    /** A start, move or completion was not saved: no connection. */
    SAVE_OFFLINE,

    /** A start, move or completion was refused. */
    SAVE_FAILED,

    /** The review was changed on another device; what is shown now is the server's state. */
    CONFLICT,

    /** The reflection could not be saved in the journal, so the review was not completed. */
    REFLECTION_FAILED,

    /** The quick-add capture was not accepted (too long). */
    CAPTURE_FAILED,

    /** A decision on an item of a step was refused. */
    ACTION_FAILED,

    /** The item was changed or removed elsewhere; the step was loaded again. */
    ITEM_CHANGED,
}

/** What happened to the item decided last in a step, announced above the step's list. */
data class ReviewDecision(
    val kind: Kind,
    /** The item's title; for [Kind.FOLLOWED_UP] the new next action's, for [Kind.NEXT_STEP_ADDED] the new step's. */
    val title: String,
    /** The project of a [Kind.NEXT_STEP_ADDED]. */
    val project: String? = null,
    /** The new status of a [Kind.STATUS_CHANGED]. */
    val status: ProjectStatus? = null,
) {
    enum class Kind { STILL_WAITING, FOLLOWED_UP, COMPLETED, KEPT, MOVED_TO_SOMEDAY, DELETED, ACTIVATED, NEXT_STEP_ADDED, STATUS_CHANGED }
}

/** Data a step loads when it is shown. */
data class StepData<out T>(
    /** `null` until the first load arrived. */
    val value: T? = null,
    val loading: Boolean = false,
    /** The last load failed; [value] is what was loaded before, if anything. */
    val failed: Boolean = false,
)

data class ReviewUiState(
    /** `null` until the first load arrived. */
    val overview: ReviewOverview? = null,
    val loading: Boolean = false,
    val message: ReviewMessage? = null,
    /** The user is in the wizard (only shown while a review is open). */
    val wizard: Boolean = false,
    /** A start, move or completion is running. */
    val saving: Boolean = false,
    /** The review was just completed on this phone. */
    val completed: Boolean = false,
    val pastEvents: StepData<List<ReviewEvent>> = StepData(),
    val upcomingEvents: StepData<List<ReviewEvent>> = StepData(),
    val habits: StepData<List<Habit>> = StepData(),
    /** The quick-add field of the calendar steps. */
    val captureText: String = "",
    /** A quick-add capture is being stored. */
    val capturing: Boolean = false,
    /** The title last added to the inbox from the calendar steps. */
    val captured: String? = null,
    /** The reflection of the last step, saved in today's journal entry when the review is completed. */
    val reflection: String = "",
    /** Steps 4 to 7: the items still to decide on (a decided item leaves its list). */
    val waiting: StepData<List<ReviewTask>> = StepData(),
    val projectList: StepData<List<ReviewProject>> = StepData(),
    val staleNext: StepData<List<ReviewTask>> = StepData(),
    val someday: StepData<List<SomedayItem>> = StepData(),
    /** Items whose decision is being saved. */
    val busy: Set<UUID> = emptySet(),
    /** The last decision of the shown step. */
    val decision: ReviewDecision? = null,
    /** The next step typed for each stalled project. */
    val nextStepDrafts: Map<UUID, String> = emptyMap(),
) {
    val current: OpenReview? get() = overview?.current

    /** The open review the wizard shows; `null` shows the overview. */
    val wizardReview: OpenReview? get() = current?.takeIf { wizard }
}

/**
 * The weekly review (SPEC.md §8.6, §9.2): the overview, and the wizard that starts or continues the server's review
 * session (the same one the web uses), moves between its steps and completes it.
 */
@HiltViewModel
class ReviewViewModel
    @Inject
    constructor(
        private val reviews: ReviewRepository,
        private val captures: CaptureQueue,
        private val habits: HabitsRepository,
        private val reflections: ReflectionSaver,
        private val actions: ReviewActions,
        private val drafts: ReviewDrafts = ReviewDrafts(),
    ) : ViewModel() {
        private val state = MutableStateFlow(ReviewUiState())
        val uiState: StateFlow<ReviewUiState> = state.asStateFlow()

        private var loadJob: Job? = null
        private val stepJobs = mutableMapOf<ReviewStep, Job>()

        /** The items decided on the server while their step loads: the load may still hold them. */
        private val decidedDuringLoad = mutableMapOf<ReviewStep, MutableSet<UUID>>()

        /** Counts the saved starts, moves and completions; a load begun before the last one must not undo it. */
        private var saves = 0

        /** Loads again; a load still running is dropped. A failed load keeps what was shown and says why. */
        fun refresh() = reload(onOk = null)

        /**
         * Loads again; [onOk] is the message once it is loaded (a conflict says why the review changed). A review no
         * longer open closes the wizard.
         */
        private fun reload(onOk: ReviewMessage?) {
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch {
                    state.update { it.copy(loading = true) }
                    val savesBefore = saves
                    val result = reviews.overview()
                    state.update {
                        val loaded = (result as? ReviewResult.Ok)?.value
                        val overview =
                            when {
                                loaded == null -> it.overview
                                // A change saved meanwhile is newer than the review this load read.
                                saves != savesBefore && it.overview != null ->
                                    loaded.copy(current = it.current, daysSinceLastReview = it.overview.daysSinceLastReview)
                                else -> loaded
                            }
                        val current = overview?.current
                        it.copy(
                            overview = overview,
                            loading = false,
                            // The wizard and draft outlive this screen (a tab switch), so they come back with the review.
                            wizard = current != null && (it.wizard || drafts.wizardFor == current.id),
                            reflection = current?.let { open -> drafts.reflection(open.id) } ?: it.reflection,
                            message =
                                when (result) {
                                    is ReviewResult.Ok -> onOk
                                    ReviewResult.Offline -> ReviewMessage.OFFLINE
                                    is ReviewResult.Failed -> ReviewMessage.FAILED
                                },
                        )
                    }
                }
        }

        /** Continues the open review, or starts one. */
        fun start() {
            val current = state.value.current
            if (current != null) {
                drafts.wizardFor = current.id
                state.update { it.copy(wizard = true, completed = false, message = null) }
                return
            }
            save {
                when (val result = reviews.start()) {
                    is ReviewResult.Ok -> {
                        saves++
                        drafts.wizardFor = result.value.id
                        state.update {
                            it.copy(
                                overview = (it.overview ?: ReviewOverview(null, null, emptyList())).copy(current = result.value),
                                wizard = true,
                                completed = false,
                                reflection = drafts.reflection(result.value.id),
                            )
                        }
                        null
                    }
                    else -> result
                }
            }
        }

        /** Back to the overview; the review stays open on the server. */
        fun closeWizard() {
            drafts.wizardFor = null
            state.update { it.copy(wizard = false) }
        }

        /** Saves [step] as the current step: any step, also an earlier one. */
        fun goTo(step: ReviewStep) {
            val review = state.value.current ?: return
            if (review.step == step || state.value.saving) return
            save {
                when (val result = reviews.move(review, step)) {
                    is ReviewResult.Ok -> {
                        saves++
                        state.update { it.copy(overview = it.overview?.copy(current = result.value)) }
                        null
                    }
                    else -> result
                }
            }
        }

        /** Marks the current step done: on to the next one. */
        fun next() {
            state.value.current
                ?.step
                ?.after(1)
                ?.let(::goTo)
        }

        fun previous() {
            state.value.current
                ?.step
                ?.after(-1)
                ?.let(::goTo)
        }

        /** Saves the reflection (if any) in today's journal entry, then completes the review. */
        fun complete() {
            val review = state.value.current ?: return
            if (state.value.saving) return
            save {
                val text = state.value.reflection.trim()
                if (text.isNotEmpty()) {
                    when (reflections.save(text)) {
                        // Saved: a retry after a failed completion must not append it a second time.
                        ReflectionOutcome.SAVED -> {
                            drafts.setReflection(review.id, "")
                            state.update { it.copy(reflection = "") }
                        }
                        ReflectionOutcome.OFFLINE -> return@save ReviewResult.Offline
                        ReflectionOutcome.FAILED -> {
                            state.update { it.copy(message = ReviewMessage.REFLECTION_FAILED) }
                            return@save null
                        }
                    }
                }
                when (val result = reviews.complete(review)) {
                    is ReviewResult.Ok -> {
                        saves++
                        drafts.clear(review.id)
                        state.update {
                            it.copy(
                                overview = it.overview?.copy(current = null, daysSinceLastReview = 0),
                                wizard = false,
                                completed = true,
                            )
                        }
                        null
                    }
                    else -> result
                }
            }
        }

        fun setReflection(text: String) {
            state.value.current?.let { drafts.setReflection(it.id, text) }
            state.update { it.copy(reflection = text) }
        }

        fun setCaptureText(text: String) = state.update { it.copy(captureText = text) }

        /** Adds the quick-add text to the inbox; offline it is queued and sent later (SPEC.md §9.3). */
        fun capture() {
            val text = state.value.captureText.trim()
            if (text.isEmpty() || state.value.capturing) return
            state.update { it.copy(capturing = true) }
            viewModelScope.launch {
                try {
                    captures.capture(text)
                    // Text typed while it was stored stays in the field.
                    state.update { it.copy(captureText = if (it.captureText.trim() == text) "" else it.captureText, captured = text) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IllegalArgumentException) {
                    state.update { it.copy(message = ReviewMessage.CAPTURE_FAILED) }
                } catch (_: IOException) {
                    state.update { it.copy(message = ReviewMessage.CAPTURE_FAILED) }
                } finally {
                    state.update { it.copy(capturing = false) }
                }
            }
        }

        fun dismissMessage() = state.update { it.copy(message = null) }

        /** Loads what [step] shows (its events, items or the habits); called each time the step is shown and to retry. */
        fun loadStep(step: ReviewStep) {
            // The last decision belongs to the step it was made in.
            state.update { it.copy(decision = null) }
            when (step) {
                ReviewStep.GET_CLEAR -> Unit
                ReviewStep.PAST_CALENDAR -> loadEvents(step, upcoming = false)
                ReviewStep.UPCOMING_CALENDAR -> loadEvents(step, upcoming = true)
                ReviewStep.WAITING_FOR ->
                    loadItems(step, { actions.waitingFor() }, { it.id }) { ui, update -> ui.copy(waiting = update(ui.waiting)) }
                ReviewStep.PROJECTS ->
                    loadItems(step, { actions.projects() }, { it.id }) { ui, update -> ui.copy(projectList = update(ui.projectList)) }
                ReviewStep.NEXT_ACTIONS ->
                    loadItems(step, { actions.staleNextActions() }, { it.id }) { ui, update -> ui.copy(staleNext = update(ui.staleNext)) }
                ReviewStep.SOMEDAY ->
                    loadItems(step, { actions.someday() }, { it.id }) { ui, update -> ui.copy(someday = update(ui.someday)) }
                ReviewStep.REFLECTION -> loadHabits()
            }
        }

        /** Waiting for: still waiting (nothing changes on the server). */
        fun keepWaiting(task: ReviewTask) =
            decide(ReviewStep.WAITING_FOR, task.id, ReviewDecision(ReviewDecision.Kind.STILL_WAITING, task.title), null)

        /** Waiting for: adds a next action titled [title] to chase it; the waiting task itself stays as it is. */
        fun followUp(
            task: ReviewTask,
            title: String,
        ) = decide(
            ReviewStep.WAITING_FOR,
            task.id,
            ReviewDecision(ReviewDecision.Kind.FOLLOWED_UP, title),
        ) { actions.followUp(task, title) }

        /** Waiting for: it arrived, the task is completed. */
        fun completeWaiting(task: ReviewTask) =
            decide(ReviewStep.WAITING_FOR, task.id, ReviewDecision(ReviewDecision.Kind.COMPLETED, task.title)) { actions.complete(task) }

        /** Projects: the project looks good. */
        fun keepProject(project: ReviewProject) =
            decide(ReviewStep.PROJECTS, project.id, ReviewDecision(ReviewDecision.Kind.KEPT, project.title), null)

        /** Projects: a new status; the project leaves the list. */
        fun setProjectStatus(
            project: ReviewProject,
            status: ProjectStatus,
        ) {
            if (status == project.status) return
            decide(ReviewStep.PROJECTS, project.id, ReviewDecision(ReviewDecision.Kind.STATUS_CHANGED, project.title, status = status)) {
                actions.setStatus(project, status)
            }
        }

        fun setNextStepDraft(
            project: ReviewProject,
            text: String,
        ) = state.update { it.copy(nextStepDrafts = it.nextStepDrafts + (project.id to text)) }

        /** Projects: the typed next step becomes a next action of the project and its next step; the project stays listed. */
        fun addNextStep(project: ReviewProject) {
            val title =
                state.value.nextStepDrafts[project.id]
                    ?.trim()
                    .orEmpty()
            if (title.isEmpty() || project.id in state.value.busy) return
            setBusy(project.id, true)
            viewModelScope.launch {
                val outcome =
                    try {
                        actions.addNextStep(project, title)
                    } finally {
                        setBusy(project.id, false)
                    }
                // Created: submitting again must not add it a second time.
                if (outcome.taskCreated) state.update { it.copy(nextStepDrafts = it.nextStepDrafts - project.id) }
                when (val result = outcome.result) {
                    is ReviewResult.Ok ->
                        state.update {
                            it.copy(
                                projectList = it.projectList.map { list -> list.map { p -> if (p.id == project.id) result.value else p } },
                                decision = ReviewDecision(ReviewDecision.Kind.NEXT_STEP_ADDED, title, project = project.title),
                            )
                        }
                    else -> failed(ReviewStep.PROJECTS, result)
                }
            }
        }

        /** Next actions: keep it as it is. */
        fun keepNextAction(task: ReviewTask) =
            decide(ReviewStep.NEXT_ACTIONS, task.id, ReviewDecision(ReviewDecision.Kind.KEPT, task.title), null)

        fun moveToSomeday(task: ReviewTask) =
            decide(ReviewStep.NEXT_ACTIONS, task.id, ReviewDecision(ReviewDecision.Kind.MOVED_TO_SOMEDAY, task.title)) {
                actions.moveToSomeday(task)
            }

        /** Next actions: moves it to the trash. */
        fun deleteNextAction(task: ReviewTask) =
            decide(ReviewStep.NEXT_ACTIONS, task.id, ReviewDecision(ReviewDecision.Kind.DELETED, task.title)) { actions.delete(task) }

        /** Someday: a task becomes a next action, a project becomes active. */
        fun activate(item: SomedayItem) =
            decide(ReviewStep.SOMEDAY, item.id, ReviewDecision(ReviewDecision.Kind.ACTIVATED, item.title)) { actions.activate(item) }

        fun keepSomeday(item: SomedayItem) = decide(ReviewStep.SOMEDAY, item.id, ReviewDecision(ReviewDecision.Kind.KEPT, item.title), null)

        /** Someday: moves the task or project to the trash. */
        fun deleteSomeday(item: SomedayItem) =
            decide(ReviewStep.SOMEDAY, item.id, ReviewDecision(ReviewDecision.Kind.DELETED, item.title)) { actions.delete(item) }

        /**
         * Runs [action] on the item [id] of [step] (`null`: nothing to save); once it succeeded the item leaves the list
         * and [decision] is announced. A failure leaves the item to try again and says why.
         */
        private fun decide(
            step: ReviewStep,
            id: UUID,
            decision: ReviewDecision,
            action: (suspend () -> ReviewResult<*>)?,
        ) {
            if (id in state.value.busy) return
            if (action == null) {
                removeItem(step, id, decision)
                return
            }
            setBusy(id, true)
            viewModelScope.launch {
                val result =
                    try {
                        action()
                    } finally {
                        setBusy(id, false)
                    }
                if (result is ReviewResult.Ok) {
                    decidedDuringLoad[step]?.add(id)
                    removeItem(step, id, decision)
                } else {
                    failed(step, result)
                }
            }
        }

        private fun removeItem(
            step: ReviewStep,
            id: UUID,
            decision: ReviewDecision,
        ) = state.update {
            val shown =
                when (step) {
                    ReviewStep.WAITING_FOR -> it.copy(waiting = it.waiting.map { list -> list.filterNot { t -> t.id == id } })
                    ReviewStep.PROJECTS -> it.copy(projectList = it.projectList.map { list -> list.filterNot { p -> p.id == id } })
                    ReviewStep.NEXT_ACTIONS -> it.copy(staleNext = it.staleNext.map { list -> list.filterNot { t -> t.id == id } })
                    ReviewStep.SOMEDAY -> it.copy(someday = it.someday.map { list -> list.filterNot { i -> i.id == id } })
                    else -> it
                }
            shown.copy(decision = decision)
        }

        /** A refused decision: changed or removed elsewhere loads the step again, other failures say why. */
        private fun failed(
            step: ReviewStep,
            result: ReviewResult<*>,
        ) {
            val message =
                when {
                    result == ReviewResult.Offline -> ReviewMessage.SAVE_OFFLINE
                    result is ReviewResult.Failed && result.status in CONFLICTS -> ReviewMessage.ITEM_CHANGED
                    else -> ReviewMessage.ACTION_FAILED
                }
            if (message == ReviewMessage.ITEM_CHANGED) {
                val decision = state.value.decision
                loadStep(step)
                state.update { it.copy(decision = decision) }
            }
            state.update { it.copy(message = message) }
        }

        private fun setBusy(
            id: UUID,
            busy: Boolean,
        ) = state.update { it.copy(busy = if (busy) it.busy + id else it.busy - id) }

        /** Loads the items of [step]; one decided while it loads is left out (the load may have read it before). */
        private fun <T> loadItems(
            step: ReviewStep,
            load: suspend () -> ReviewResult<List<T>>,
            id: (T) -> UUID,
            field: (ReviewUiState, (StepData<List<T>>) -> StepData<List<T>>) -> ReviewUiState,
        ) = loadInto(step, {
            val decided = mutableSetOf<UUID>().also { decidedDuringLoad[step] = it }
            load().toStep()?.filterNot { id(it) in decided }
        }, field)

        private fun loadEvents(
            step: ReviewStep,
            upcoming: Boolean,
        ) = loadInto(step, { reviews.events(upcoming).toStep() }) { ui, update ->
            if (upcoming) ui.copy(upcomingEvents = update(ui.upcomingEvents)) else ui.copy(pastEvents = update(ui.pastEvents))
        }

        private fun loadHabits() =
            loadInto(ReviewStep.REFLECTION, {
                when (val result = habits.list()) {
                    is HabitsResult.Ok -> result.value
                    else -> null
                }
            }) { ui, update -> ui.copy(habits = update(ui.habits)) }

        /** Runs [load] for [step] (dropping one still running) and stores its value, `null` meaning it failed. */
        private fun <T> loadInto(
            step: ReviewStep,
            load: suspend () -> T?,
            field: (ReviewUiState, (StepData<T>) -> StepData<T>) -> ReviewUiState,
        ) {
            stepJobs[step]?.cancel()
            stepJobs[step] =
                viewModelScope.launch {
                    state.update { ui -> field(ui) { it.copy(loading = true) } }
                    val value = load()
                    state.update { ui ->
                        field(ui) { StepData(value ?: it.value, loading = false, failed = value == null) }
                    }
                }
        }

        /**
         * Runs a review change. It returns `null` when done, or the failure: a conflict (another device moved or
         * completed the review) loads the review again, other failures leave the state as it was and say why.
         */
        private fun save(change: suspend () -> ReviewResult<*>?) {
            if (state.value.saving) return
            state.update { it.copy(saving = true, message = null) }
            viewModelScope.launch {
                val failure =
                    try {
                        change()
                    } finally {
                        state.update { it.copy(saving = false) }
                    }
                when (failure) {
                    null, is ReviewResult.Ok -> Unit
                    ReviewResult.Offline -> state.update { it.copy(message = ReviewMessage.SAVE_OFFLINE) }
                    is ReviewResult.Failed ->
                        if (failure.status in CONFLICTS) {
                            reload(onOk = ReviewMessage.CONFLICT)
                        } else {
                            state.update { it.copy(message = ReviewMessage.SAVE_FAILED) }
                        }
                }
            }
        }

        private companion object {
            /** Not found (deleted), conflict (completed or not open), precondition failed (moved elsewhere). */
            val CONFLICTS = setOf(404, 409, 412)
        }
    }

private fun <T> ReviewResult<T>.toStep(): T? = (this as? ReviewResult.Ok)?.value

/** The same data with its loaded value changed by [transform]. */
private fun <T> StepData<T>.map(transform: (T) -> T): StepData<T> = copy(value = value?.let(transform))
