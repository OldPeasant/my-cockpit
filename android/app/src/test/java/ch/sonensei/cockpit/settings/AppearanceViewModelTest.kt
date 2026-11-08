package ch.sonensei.cockpit.settings

import ch.sonensei.cockpit.core.settings.SettingsUpdateResult
import ch.sonensei.cockpit.core.settings.ThemeSetting
import ch.sonensei.cockpit.core.settings.UserSettings
import ch.sonensei.cockpit.core.settings.UserSettingsChange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val settings = MutableStateFlow<UserSettings?>(UserSettings(theme = ThemeSetting.SYSTEM))
    private val sent = mutableListOf<UserSettingsChange>()
    private var answer = CompletableDeferred<SettingsUpdateResult>()

    private fun viewModel() =
        AppearanceViewModel(settings) { change ->
            sent += change
            answer.await().also { if (it == SettingsUpdateResult.Updated) settings.value = UserSettings(theme = change.theme!!) }
        }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `choosing a theme shows it while saving and sends it`() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            assertEquals(ThemeSetting.SYSTEM, vm.uiState.value.shown)

            vm.choose(ThemeSetting.DARK)
            advanceUntilIdle()
            assertEquals(ThemeSetting.DARK, vm.uiState.value.shown)
            assertFalse(vm.uiState.value.enabled)
            assertEquals(listOf(UserSettingsChange(theme = ThemeSetting.DARK)), sent)

            answer.complete(SettingsUpdateResult.Updated)
            advanceUntilIdle()
            assertEquals(ThemeSetting.DARK, vm.uiState.value.theme)
            assertTrue(vm.uiState.value.enabled)
            assertNull(vm.uiState.value.message)
        }

    @Test
    fun `a conflict shows the reloaded theme and says so`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.choose(ThemeSetting.LIGHT)
            answer.complete(SettingsUpdateResult.Conflict)
            advanceUntilIdle()

            assertEquals(ThemeSetting.SYSTEM, vm.uiState.value.shown)
            assertEquals(AppearanceMessage.CONFLICT, vm.uiState.value.message)
        }

    @Test
    fun `offline and rejected answers keep the old theme`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.choose(ThemeSetting.LIGHT)
            answer.complete(SettingsUpdateResult.Offline)
            advanceUntilIdle()
            assertEquals(AppearanceMessage.OFFLINE, vm.uiState.value.message)

            answer = CompletableDeferred(SettingsUpdateResult.Rejected)
            vm.choose(ThemeSetting.LIGHT)
            advanceUntilIdle()
            assertEquals(AppearanceMessage.FAILED, vm.uiState.value.message)
            assertEquals(ThemeSetting.SYSTEM, vm.uiState.value.shown)
        }

    @Test
    fun `nothing is sent while the settings are unknown or for the current theme`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.choose(ThemeSetting.SYSTEM)
            settings.value = null
            advanceUntilIdle()
            assertFalse(vm.uiState.value.enabled)
            vm.choose(ThemeSetting.DARK)
            advanceUntilIdle()

            assertTrue(sent.isEmpty())
        }
}
