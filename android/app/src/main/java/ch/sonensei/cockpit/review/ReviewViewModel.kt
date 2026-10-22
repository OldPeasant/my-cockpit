package ch.sonensei.cockpit.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why what is shown may be outdated or missing. */
enum class ReviewMessage { OFFLINE, FAILED }

data class ReviewUiState(
    /** `null` until the first load arrived. */
    val overview: ReviewOverview? = null,
    val loading: Boolean = false,
    val message: ReviewMessage? = null,
)

/** The weekly review's progress and stalled projects, read only (SPEC.md §8.6, §9.2). */
@HiltViewModel
class ReviewViewModel
    @Inject
    constructor(
        private val reviews: ReviewRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(ReviewUiState())
        val uiState: StateFlow<ReviewUiState> = state.asStateFlow()

        private var loadJob: Job? = null

        /** Loads again; a load still running is dropped. A failed load keeps what was shown and says why. */
        fun refresh() {
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch {
                    state.update { it.copy(loading = true) }
                    val result = reviews.overview()
                    state.update {
                        it.copy(
                            overview = (result as? ReviewResult.Ok)?.value ?: it.overview,
                            loading = false,
                            message =
                                when (result) {
                                    is ReviewResult.Ok -> null
                                    ReviewResult.Offline -> ReviewMessage.OFFLINE
                                    is ReviewResult.Failed -> ReviewMessage.FAILED
                                },
                        )
                    }
                }
        }
    }
