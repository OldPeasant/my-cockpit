package ch.sonensei.cockpit.reminders

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.core.network.generated.models.ReminderItemResponse
import ch.sonensei.cockpit.ui.CockpitShell
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.screenTag
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** The permission prompt (shown in context, asking for what is missing) and opening the item of a tapped notification. */
@RunWith(AndroidJUnit4::class)
class ReminderUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val graph = remindersGraph(context)
    private val syncStarts = mutableListOf<Unit>()
    private val syncScheduler =
        object : ReminderSyncScheduler {
            override fun start() {
                syncStarts += Unit
            }

            override fun cancel() = Unit
        }

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
    }

    @After
    fun tearDown() {
        graph.reminderScheduler().clear()
        ShadowAlarmManager.reset()
        ReminderOpenRequests.requests.value?.let { ReminderOpenRequests.take(it) }
    }

    private fun viewModel() =
        ReminderPromptViewModel(
            graph.reminderStore(),
            AlarmManagerReminderAlarms(context),
            ReminderNotifier(context),
            graph.reminderScheduler(),
            syncScheduler,
        )

    private fun showPrompt(viewModel: ReminderPromptViewModel) {
        composeRule.setContent { CockpitTheme { ReminderPermissionPrompt(viewModel = viewModel) } }
        composeRule.waitForIdle()
    }

    @Test
    fun `no prompt while there are no reminders, and the sync starts`() {
        val viewModel = viewModel()
        showPrompt(viewModel)

        composeRule.onNodeWithTag(ReminderPromptTags.CARD).assertDoesNotExist()
        assertEquals(1, syncStarts.size)
    }

    @Test
    fun `with upcoming reminders the prompt asks for notifications and exact alarms`() {
        val viewModel = viewModel()
        showPrompt(viewModel)

        graph.reminderScheduler().apply(listOf(reminder(1, minutes = 30)))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ReminderPromptTags.CARD).assertIsDisplayed()
        composeRule.onNodeWithTag(ReminderPromptTags.ALLOW_NOTIFICATIONS).assertIsDisplayed()
        composeRule.onNodeWithTag(ReminderPromptTags.ALLOW_EXACT).assertIsDisplayed()
    }

    @Test
    fun `with notifications allowed the prompt only asks for exact alarms`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        graph.reminderScheduler().apply(listOf(reminder(1, minutes = 30)))
        showPrompt(viewModel())

        composeRule.onNodeWithTag(ReminderPromptTags.CARD).assertIsDisplayed()
        composeRule.onNodeWithTag(ReminderPromptTags.ALLOW_EXACT).assertIsDisplayed()
        composeRule.onNodeWithTag(ReminderPromptTags.ALLOW_NOTIFICATIONS).assertDoesNotExist()
    }

    @Test
    fun `with exact alarms allowed the prompt only asks for notifications`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        graph.reminderScheduler().apply(listOf(reminder(1, minutes = 30)))
        showPrompt(viewModel())

        composeRule.onNodeWithTag(ReminderPromptTags.ALLOW_NOTIFICATIONS).assertIsDisplayed()
        composeRule.onNodeWithTag(ReminderPromptTags.ALLOW_EXACT).assertDoesNotExist()
    }

    @Test
    @Config(sdk = [30])
    fun `before Android 13 the prompt asks only for notifications, which opens the app's notification settings`() {
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        graph.reminderScheduler().apply(listOf(reminder(1, minutes = 30)))
        showPrompt(viewModel())

        composeRule.onNodeWithTag(ReminderPromptTags.ALLOW_EXACT).assertDoesNotExist()
        composeRule.onNodeWithTag(ReminderPromptTags.ALLOW_NOTIFICATIONS).performClick()

        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
    }

    @Test
    fun `allow notifications asks for the runtime permission`() {
        graph.reminderScheduler().apply(listOf(reminder(1, minutes = 30)))
        showPrompt(viewModel())

        composeRule.onNodeWithTag(ReminderPromptTags.ALLOW_NOTIFICATIONS).performClick()
        composeRule.waitForIdle()

        val request = shadowOf(composeRule.activity).lastRequestedPermission
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in request.requestedPermissions)
        assertTrue(graph.reminderStore().wasNotificationPermissionRequested())
    }

    @Test
    fun `once asked, allow notifications opens the app's notification settings`() {
        graph.reminderScheduler().apply(listOf(reminder(1, minutes = 30)))
        graph.reminderStore().markNotificationPermissionRequested()
        showPrompt(viewModel())

        composeRule.onNodeWithTag(ReminderPromptTags.ALLOW_NOTIFICATIONS).performClick()

        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(context.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `allow exact alarms opens the system page for this app`() {
        graph.reminderScheduler().apply(listOf(reminder(1, minutes = 30)))
        showPrompt(viewModel())

        composeRule.onNodeWithTag(ReminderPromptTags.ALLOW_EXACT).performClick()

        val started = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, started.action)
        assertEquals("package:${context.packageName}", started.dataString)
    }

    @Test
    fun `granted permissions hide the prompt, and newly granted exact alarms make the alarms exact`() {
        graph.reminderScheduler().apply(listOf(reminder(1, minutes = 30)))
        val viewModel = viewModel()
        showPrompt(viewModel)

        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        viewModel.refreshPermissions()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ReminderPromptTags.CARD).assertDoesNotExist()
        assertEquals(0L, scheduledAlarms(context).single().windowLengthMs)
    }

    @Test
    fun `not now hides the prompt for good`() {
        graph.reminderScheduler().apply(listOf(reminder(1, minutes = 30)))
        val viewModel = viewModel()
        showPrompt(viewModel)

        composeRule.onNodeWithTag(ReminderPromptTags.DISMISS).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ReminderPromptTags.CARD).assertDoesNotExist()
        assertFalse(viewModel().state.value.visible)
    }

    @Test
    fun `a tapped notification opens the item's screen in the shell`() {
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            CockpitTheme { CockpitShell(navController) }
        }
        composeRule.onNodeWithTag(screenTag(CockpitDestination.Today)).assertIsDisplayed()

        val reminder = reminder(1, minutes = 0, type = ReminderItemResponse.Type.TASK).toScheduled()!!
        assertTrue(ReminderOpenRequests.offer(openItemIntent(context, reminder)))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(screenTag(CockpitDestination.Next)).assertIsDisplayed()
        assertNull("taken once", ReminderOpenRequests.requests.value)
    }

    @Test
    fun `a request made before the shell shows is opened when it appears`() {
        val event = reminder(1, minutes = 0, type = ReminderItemResponse.Type.EVENT).toScheduled()!!
        ReminderOpenRequests.offer(openItemIntent(context, event))

        composeRule.setContent { CockpitTheme { CockpitShell() } }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(screenTag(CockpitDestination.Calendar)).assertIsDisplayed()
    }

    @Test
    fun `other intents are no open request`() {
        assertFalse(ReminderOpenRequests.offer(Intent(Intent.ACTION_MAIN)))
        assertFalse(ReminderOpenRequests.offer(null))
        assertFalse(ReminderOpenRequests.offer(Intent(ReminderOpenRequests.ACTION_OPEN_ITEM)))
        assertFalse(
            ReminderOpenRequests.offer(
                Intent(ReminderOpenRequests.ACTION_OPEN_ITEM).putExtra(ReminderOpenRequests.EXTRA_ITEM_TYPE, "TASK"),
            ),
        )
        assertNull(ReminderOpenRequests.requests.value)
    }

    @Test
    fun `every item type opens the screen that lists it`() {
        val expected =
            mapOf(
                "TASK" to CockpitDestination.Next,
                "PROJECT" to CockpitDestination.Projects,
                "NOTE" to CockpitDestination.Notes,
                "EVENT" to CockpitDestination.Calendar,
                "EMAIL" to CockpitDestination.Mail,
                "FILE" to CockpitDestination.Files,
                "FOLDER" to CockpitDestination.Files,
                "CONTACT" to CockpitDestination.Contacts,
                "BOOKMARK" to CockpitDestination.Bookmarks,
                "JOURNAL_ENTRY" to CockpitDestination.Journal,
                "HABIT" to CockpitDestination.Habits,
                "SOMETHING_NEW" to CockpitDestination.Today,
            )
        expected.forEach { (type, destination) -> assertEquals(type, destination, OpenItemRequest(type, "x").destination) }
        assertEquals(ReminderItemResponse.Type.entries.size + 1, expected.size)
    }
}
