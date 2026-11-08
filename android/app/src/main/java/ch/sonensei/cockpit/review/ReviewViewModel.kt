package ch.sonensei.cockpit.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.capture.queue.CaptureQueue
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
        private val drafts: ReviewDrafts = ReviewDrafts(),
    ) : ViewModel() {
        private val state = MutableStateFlow(ReviewUiState())
        val uiState: StateFlow<ReviewUiState> = state.asStateFlow()

        private var loadJob: Job? = null
        private val stepJobs = mutableMapOf<ReviewStep, Job>()

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

        /** Loads what [step] shows (its events or the habits); called each time the step is shown and to retry. */
        fun loadStep(step: ReviewStep) {
            when (step) {
                ReviewStep.PAST_CALENDAR -> loadEvents(step, upcoming = false)
                ReviewStep.UPCOMING_CALENDAR -> loadEvents(step, upcoming = true)
                ReviewStep.REFLECTION -> loadHabits()
                else -> Unit
            }
        }

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
