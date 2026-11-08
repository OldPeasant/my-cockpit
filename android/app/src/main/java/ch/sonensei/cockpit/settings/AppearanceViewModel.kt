package ch.sonensei.cockpit.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.sonensei.cockpit.core.settings.SettingsUpdateResult
import ch.sonensei.cockpit.core.settings.ThemeSetting
import ch.sonensei.cockpit.core.settings.UserSettings
import ch.sonensei.cockpit.core.settings.UserSettingsChange
import ch.sonensei.cockpit.core.settings.UserSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why the last theme change did not happen. */
enum class AppearanceMessage {
    /** The settings changed elsewhere; they were loaded again, so the user sees the current theme and may choose again. */
    CONFLICT,
    OFFLINE,
    FAILED,
}

data class AppearanceUiState(
    /** The synced theme; `null` while the settings are not known (the choice is then disabled). */
    val theme: ThemeSetting? = null,
    /** The theme being saved; shown as chosen until the server answered. */
    val saving: ThemeSetting? = null,
    val message: AppearanceMessage? = null,
) {
    val shown: ThemeSetting? get() = saving ?: theme
    val enabled: Boolean get() = theme != null && saving == null
}

/** The synced `theme` setting (light, dark, system); the only synced setting the app edits (docs/PARITY.md). */
@HiltViewModel
class AppearanceViewModel internal constructor(
    private val settings: StateFlow<UserSettings?>,
    private val update: suspend (UserSettingsChange) -> SettingsUpdateResult,
) : ViewModel() {
    @Inject
    constructor(repository: UserSettingsRepository) : this(repository.settings, repository::update)

    private val saving = MutableStateFlow<ThemeSetting?>(null)
    private val message = MutableStateFlow<AppearanceMessage?>(null)

    val uiState: StateFlow<AppearanceUiState> =
        combine(settings, saving, message) { current, pending, msg -> AppearanceUiState(current?.theme, pending, msg) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppearanceUiState(settings.value?.theme))

    fun choose(theme: ThemeSetting) {
        if (saving.value != null || settings.value == null || theme == settings.value?.theme) return
        saving.value = theme
        message.value = null
        viewModelScope.launch {
            val result =
                try {
                    update(UserSettingsChange(theme = theme))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    SettingsUpdateResult.Rejected
                }
            message.value =
                when (result) {
                    SettingsUpdateResult.Updated -> null
                    SettingsUpdateResult.Conflict -> AppearanceMessage.CONFLICT
                    SettingsUpdateResult.Offline -> AppearanceMessage.OFFLINE
                    SettingsUpdateResult.Rejected, SettingsUpdateResult.NotLoaded -> AppearanceMessage.FAILED
                }
            saving.value = null
        }
    }
}
