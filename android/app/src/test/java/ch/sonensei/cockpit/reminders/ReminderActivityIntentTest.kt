package ch.sonensei.cockpit.reminders

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.core.network.generated.models.ReminderItemResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * The real activity hands the item of a tapped reminder notification to the shell: on a cold start (onCreate) and while
 * the app is open (onNewIntent). Without stored tokens the login screen shows, so the request stays pending for the shell.
 */
@RunWith(AndroidJUnit4::class)
class ReminderActivityIntentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<MainActivity>? = null

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        ReminderOpenRequests.requests.value?.let { ReminderOpenRequests.take(it) }
    }

    private fun openIntent(
        n: Int,
        type: ReminderItemResponse.Type,
    ): Intent = openItemIntent(context, reminder(n, minutes = 0, type = type).toScheduled()!!)

    @Test
    fun `a cold start from a reminder notification keeps the open request for the shell`() {
        controller = Robolectric.buildActivity(MainActivity::class.java, openIntent(1, ReminderItemResponse.Type.NOTE)).setup()

        assertEquals(OpenItemRequest("NOTE", itemId(1).toString()), ReminderOpenRequests.requests.value)
    }

    @Test
    fun `a notification tapped while the app is open is handed on and becomes the activity's intent`() {
        controller = Robolectric.buildActivity(MainActivity::class.java, Intent(context, MainActivity::class.java)).setup()
        assertNull(ReminderOpenRequests.requests.value)
        val tapped = openIntent(2, ReminderItemResponse.Type.PROJECT)

        controller!!.newIntent(tapped)

        assertEquals(OpenItemRequest("PROJECT", itemId(2).toString()), ReminderOpenRequests.requests.value)
        assertEquals(tapped, controller!!.get().intent)
    }

    @Test
    fun `a recreated activity does not open the item of its old intent again`() {
        controller = Robolectric.buildActivity(MainActivity::class.java, openIntent(3, ReminderItemResponse.Type.TASK)).setup()
        // The shell took the request.
        ReminderOpenRequests.take(ReminderOpenRequests.requests.value!!)

        controller!!.recreate()

        assertNull(ReminderOpenRequests.requests.value)
    }

    @Test
    fun `a relaunch from Recents does not open the item of the old notification again`() {
        val replayed = openIntent(4, ReminderItemResponse.Type.TASK).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)

        controller = Robolectric.buildActivity(MainActivity::class.java, replayed).setup()

        assertNull(ReminderOpenRequests.requests.value)
    }

    @Test
    fun `a normal launch makes no open request`() {
        controller =
            Robolectric
                .buildActivity(
                    MainActivity::class.java,
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(context, MainActivity::class.java),
                ).setup()

        assertNull(ReminderOpenRequests.requests.value)
    }
}
