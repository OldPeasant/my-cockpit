package ch.sonensei.cockpit.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.capture.queue.CaptureQueueStatus
import ch.sonensei.cockpit.core.network.ServerUrlValidation
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val first = Distributor("org.example.first", "First push")
    private val second = Distributor("org.example.second", "Second push")

    private val state =
        SettingsUiState(
            serverUrl = "https://cockpit.example.org/".toHttpUrl(),
            user = UserName.Known("alex"),
            permissions = PermissionState(notifications = true, exactAlarms = false, exactAlarmsSettingExists = true),
            distributors = DistributorState(listOf(first, second), first),
            capture = CaptureQueueStatus(3, "Server error (HTTP 503)"),
            appVersion = AppVersion("0.1.0", 42),
        )

    private fun show(
        state: SettingsUiState,
        actions: SettingsActions = SettingsActions(),
    ) = composeRule.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { DeviceSettingsSections(state, actions) } }

    @Test
    fun `shows server, user, permissions, push, queue and version`() {
        show(state)

        composeRule.onNodeWithText("https://cockpit.example.org/").assertIsDisplayed()
        composeRule.onNodeWithText("alex").assertIsDisplayed()
        composeRule.onNodeWithText("Notifications are allowed").assertExists()
        composeRule.onNodeWithText("Reminders may be a few minutes late").assertExists()
        composeRule.onNodeWithText("Push through First push").assertExists()
        composeRule.onNodeWithText("3 captures waiting to sync").assertExists()
        composeRule.onNodeWithText("Last attempt failed: Server error (HTTP 503)").assertExists()
        composeRule.onNodeWithText("Version 0.1.0 (42)").assertExists()
    }

    @Test
    fun `every button calls its action`() {
        val calls = mutableListOf<String>()
        show(
            state,
            SettingsActions(
                onChangeServer = { calls += "server" },
                onOpenNotificationSettings = { calls += "notifications" },
                onOpenExactAlarmSettings = { calls += "exact" },
                onChooseDistributor = { calls += "push" },
                onSyncNow = { calls += "sync" },
                onDownload = { calls += it },
            ),
        )

        listOf(
            SettingsTags.SERVER_CHANGE,
            SettingsTags.NOTIFICATIONS_OPEN,
            SettingsTags.EXACT_OPEN,
            SettingsTags.PUSH_CHOOSE,
            SettingsTags.CAPTURE_SYNC,
            SettingsTags.ABOUT_DOWNLOAD,
        ).forEach { composeRule.onNodeWithTag(it).performScrollTo().performClick() }

        assertEquals(
            listOf("server", "notifications", "exact", "push", "sync", "https://cockpit.example.org/download/android"),
            calls,
        )
    }

    @Test
    fun `without a distributor the button leads to finding one`() {
        var opened = 0
        show(state.copy(distributors = DistributorState(emptyList(), null)), SettingsActions(onGetDistributor = { opened++ }))

        composeRule.onNodeWithTag(SettingsTags.PUSH_CHOOSE).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTags.PUSH_GET).performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `no exact alarm button where Android has no such setting`() {
        show(state.copy(permissions = PermissionState(notifications = false, exactAlarms = true, exactAlarmsSettingExists = false)))

        composeRule.onNodeWithTag(SettingsTags.EXACT_OPEN).assertDoesNotExist()
        composeRule.onNodeWithText("Notifications are turned off").assertExists()
        composeRule.onNodeWithText("Reminders go off on the minute").assertExists()
    }

    @Test
    fun `an empty queue reads as synced, a sync request is confirmed`() {
        show(state.copy(capture = CaptureQueueStatus(0, null), syncRequested = true))

        composeRule.onNodeWithText("Everything you captured is on the server.").assertExists()
        composeRule.onNodeWithText("Sync started. It runs as soon as the phone is online.").assertExists()
    }

    @Test
    fun `the server dialog edits, saves and shows why an address is refused`() {
        val typed = mutableListOf<String>()
        var saves = 0
        show(
            state.copy(
                serverForm =
                    ServerForm(
                        "http://cockpit.example.org",
                        error = ServerUrlValidation.Invalid(ServerUrlValidation.Reason.HTTPS_REQUIRED, "http://cockpit.example.org"),
                    ),
            ),
            SettingsActions(onServerAddressChange = { typed += it }, onSaveServer = { saves++ }),
        )

        composeRule.onNodeWithTag(SettingsTags.SERVER_ERROR).assertExists()
        composeRule.onNodeWithTag(SettingsTags.SERVER_FIELD).performTextReplacement("https://other.example.org")
        composeRule.onNodeWithTag(SettingsTags.SERVER_SAVE).performClick()

        assertEquals(listOf("https://other.example.org"), typed)
        assertEquals(1, saves)
    }

    @Test
    fun `the distributor picker lists the installed ones and reports the choice`() {
        val chosen = mutableListOf<String>()
        show(state.copy(distributorPickerOpen = true), SettingsActions(onDistributorChosen = { chosen += it }))

        assertEquals(2, composeRule.onAllNodesWithTag(SettingsTags.PUSH_OPTION).fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Second push").performClick()
        assertEquals(listOf("org.example.second"), chosen)
    }

    @Test
    fun `an unreachable server says so and Retry asks for the user again`() {
        var retries = 0
        show(state.copy(user = UserName.Unknown), SettingsActions(onRetryUser = { retries++ }))

        composeRule.onNodeWithText("The server cannot be reached right now.").assertExists()
        composeRule.onNodeWithText("Retry").performScrollTo().performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `while the user loads it says so, without a server address it says none is set and offers no download`() {
        show(state.copy(serverUrl = null, user = UserName.Loading))

        composeRule.onNodeWithText("Checking…").assertExists()
        composeRule.onNodeWithText("No server address set").assertExists()
        composeRule.onNodeWithTag(SettingsTags.ABOUT_DOWNLOAD).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTags.SERVER_CHANGE).assertExists()
    }

    @Test
    fun `installed distributors without a chosen one offer the picker`() {
        var opened = 0
        show(state.copy(distributors = DistributorState(listOf(first), null)), SettingsActions(onChooseDistributor = { opened++ }))

        composeRule.onNodeWithText("No push app chosen. The app checks for news every few minutes.").assertExists()
        composeRule.onNodeWithTag(SettingsTags.PUSH_GET).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsTags.PUSH_CHOOSE).performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `one waiting capture without an error reads singular and waits for the network`() {
        show(state.copy(capture = CaptureQueueStatus(1, null)))

        composeRule.onNodeWithText("1 capture waiting to sync").assertExists()
        composeRule.onNodeWithText("Sends as soon as the phone is online.").assertExists()
    }

    @Test
    fun `a failed sync request is shown`() {
        show(state.copy(syncFailed = true))

        composeRule.onNodeWithText("The sync could not be started. Try again.").assertExists()
        composeRule.onNodeWithText("Sync started. It runs as soon as the phone is online.").assertDoesNotExist()
    }

    @Test
    fun `the server dialog can be cancelled`() {
        var closes = 0
        show(state.copy(serverForm = ServerForm("https://cockpit.example.org/")), SettingsActions(onCloseServerForm = { closes++ }))

        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, closes)
    }

    @Test
    fun `while the server is saved the dialog buttons are disabled`() {
        show(state.copy(serverForm = ServerForm("https://other.example.org/", saving = true)))

        composeRule.onNodeWithTag(SettingsTags.SERVER_SAVE).assertIsNotEnabled()
        composeRule.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test
    fun `a malformed address is explained with the address as understood`() {
        show(
            state.copy(
                serverForm =
                    ServerForm(
                        "my cockpit?x",
                        error = ServerUrlValidation.Invalid(ServerUrlValidation.Reason.MALFORMED, "https://mycockpit?x"),
                    ),
            ),
        )

        composeRule.onNodeWithTag(SettingsTags.SERVER_ERROR).assertTextContains("https://mycockpit?x", substring = true)
    }

    @Test
    fun `the distributor picker marks the one in use and can be closed without a choice`() {
        var closes = 0
        val chosen = mutableListOf<String>()
        show(
            state.copy(distributorPickerOpen = true),
            SettingsActions(onCloseDistributorPicker = { closes++ }, onDistributorChosen = { chosen += it }),
        )

        val options = composeRule.onAllNodesWithTag(SettingsTags.PUSH_OPTION)
        options[0].assertIsSelected()
        options[1].assertIsNotSelected()
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, closes)
        assertEquals(emptyList<String>(), chosen)
    }

    @Test
    @Config(qualifiers = "de")
    fun `the sections are in German on a German phone`() {
        show(state.copy(capture = CaptureQueueStatus(1, null)))

        composeRule.onNodeWithText("Angemeldet als").assertExists()
        composeRule.onNodeWithText("Benachrichtigungen sind erlaubt").assertExists()
        composeRule.onNodeWithText("Push über First push").assertExists()
        composeRule.onNodeWithText("1 Erfassung wartet auf die Synchronisierung").assertExists()
        composeRule.onNodeWithText("Jetzt synchronisieren").assertExists()
        composeRule.onNodeWithText("Version 0.1.0 (42)").assertExists()
        composeRule.onNodeWithText("Neueste App herunterladen").assertExists()
    }

    @Test
    fun `outside a session only the session-free parts show, the placeholder is gone`() {
        composeRule.setContent { SettingsScreen() }

        composeRule.onNodeWithTag(SettingsTags.SERVER).assertDoesNotExist()
        composeRule.onNodeWithText("Settings").assertDoesNotExist()
    }
}
