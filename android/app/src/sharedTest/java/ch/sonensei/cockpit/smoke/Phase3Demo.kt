package ch.sonensei.cockpit.smoke

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import ch.sonensei.cockpit.MainActivity
import ch.sonensei.cockpit.auth.LoginTags
import ch.sonensei.cockpit.capture.entry.CaptureActivity
import ch.sonensei.cockpit.capture.queue.CaptureQueue
import ch.sonensei.cockpit.capture.queue.CaptureSyncScheduler
import ch.sonensei.cockpit.capture.queue.WorkManagerCaptureSyncScheduler
import ch.sonensei.cockpit.capture.share.ShareActivity
import ch.sonensei.cockpit.capture.share.ShareTags
import ch.sonensei.cockpit.gtd.inbox.InboxActionKind
import ch.sonensei.cockpit.gtd.inbox.InboxTags
import ch.sonensei.cockpit.gtd.next.NextTags
import ch.sonensei.cockpit.gtd.next.TaskListTags
import ch.sonensei.cockpit.gtd.projects.ProjectsTags
import ch.sonensei.cockpit.gtd.today.TodayTags
import ch.sonensei.cockpit.reminders.ReminderNotifier
import ch.sonensei.cockpit.reminders.ReminderSync
import ch.sonensei.cockpit.reminders.ReminderSyncResult
import ch.sonensei.cockpit.ui.navigation.CockpitDestination
import ch.sonensei.cockpit.ui.screenTag
import dagger.hilt.android.testing.HiltAndroidRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject

/**
 * What differs between an emulator and Robolectric in the Phase 3 demo: how the network goes away and comes back,
 * where a "gallery" photo comes from, and how a reminder's alarm goes off.
 */
interface SmokeDevice {
    /** The e2e stack as the app (and this test) reach it, ending with `/`. */
    val baseUrl: String

    /** How long a reminder is set ahead and how long its notification may take after that (ms). */
    val reminderLeadMs: Long
    val alarmSlackMs: Long

    fun setUp()

    fun tearDown()

    /** Airplane mode on (`false`) or off. Off also lets jobs that wait for the network run. */
    fun setOnline(online: Boolean)

    /** Lets [ms] of uptime pass, as a user reading the screen would (Robolectric's clock only moves when told). */
    fun pause(ms: Long)

    /** Lets background jobs that wait for the network run now (a no-op where WorkManager runs by itself). */
    fun runQueuedWork()

    /** A photo in the "gallery", as a content URI another app can share. */
    fun galleryPhoto(
        name: String,
        bytes: ByteArray,
    ): Uri

    /** Lets the reminder alarm set for [at] go off (checking that it is set for exactly that time where possible). */
    fun letAlarmGoOff(at: Instant)
}

/**
 * SPEC.md §13.2 Phase 3, steps 1–5, against the e2e stack (`deploy/docker-compose.e2e.yml`): the real app (activities,
 * Hilt graph, Room, WorkManager, HTTP client) and the real server. What the web would show is read through the API.
 * Runs on an emulator (`Phase3DemoEmulatorTest`) and on Robolectric (`Phase3DemoRobolectricTest`), see
 * docs/ARCHITECTURE.md "Android demo script".
 */
abstract class Phase3Demo {
    protected abstract val device: SmokeDevice

    private val hilt = HiltAndroidRule(this)
    protected val composeRule = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(composeRule)

    @Inject lateinit var captureQueue: CaptureQueue

    @Inject lateinit var captureSync: CaptureSyncScheduler

    @Inject lateinit var reminderSync: ReminderSync

    protected val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var stack: E2eStack
    private lateinit var account: E2eAccount
    private lateinit var taxReturn: ApiTask
    private val scenarios = mutableListOf<ActivityScenario<*>>()

    @Before
    fun setUpDemo() {
        hilt.inject()
        device.setUp()
        stack = E2eStack(device.baseUrl)
    }

    @After
    fun tearDownDemo() {
        scenarios.forEach { it.close() }
        device.setOnline(true)
        device.tearDown()
    }

    @Test
    fun phase3Demo() {
        step1LogInWithPasswordAndTotp()
        step2CaptureThreeThingsInAirplaneMode()
        step3NetworkBackSendsThemOnceWithThePhoto()
        step4ProcessTheInboxAndCompareTheViewsWithTheWeb()
        step5ReminderFiresALocalNotification()
    }

    /** 1. Log in on the phone with password + TOTP. The web already has a task due today. */
    private fun step1LogInWithPasswordAndTotp() {
        stack.reset()
        account = stack.setUpOwner()
        stack.logIn(account)
        taxReturn =
            stack.createTask(
                buildJsonObject {
                    put("title", "File tax return")
                    put("list", "NEXT")
                    put("dueDate", LocalDate.now().toString())
                },
            )

        val main = launch<MainActivity>(Intent(context, MainActivity::class.java))
        awaitTag(LoginTags.SERVER_URL)
        composeRule.onNodeWithTag(LoginTags.SERVER_URL).performTextReplacement(device.baseUrl)
        composeRule.onNodeWithTag(LoginTags.USERNAME).performTextReplacement(account.username)
        composeRule.onNodeWithTag(LoginTags.PASSWORD).performTextReplacement(account.password)
        val (code, step) = Totp.unusedCode(account.totpSecret)
        composeRule.onNodeWithTag(LoginTags.CODE).performScrollTo().performTextReplacement(code)
        composeRule.onNodeWithTag(LoginTags.SUBMIT).performScrollTo().performClick()
        Totp.used(step)

        awaitTag(screenTag(CockpitDestination.Today), "the shell after login")
        composeRule.onNodeWithTag(LoginTags.SCREEN).assertDoesNotExist()
        close(main)
    }

    /** 2. Airplane mode: share a URL and a photo, capture "Pay rent" with the widget. 3 captures wait. */
    private fun step2CaptureThreeThingsInAirplaneMode() {
        device.setOnline(false)

        val link =
            share(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Holiday ideas")
                    .putExtra(Intent.EXTRA_TEXT, "https://example.org/holiday"),
            )
        composeRule.onNodeWithTag(ShareTags.SAVE).performClick()
        awaitClosed(link)

        val photo =
            share(
                Intent(Intent.ACTION_SEND)
                    .setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, device.galleryPhoto("receipt.png", PHOTO))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        await("the shared photo") { composeRule.onAllNodesWithTag(ShareTags.FILE).fetchSemanticsNodes().size == 1 }
        composeRule.onNodeWithTag(ShareTags.TEXT).performTextReplacement("Receipt")
        composeRule.onNodeWithTag(ShareTags.SAVE).performClick()
        awaitClosed(photo)

        // What the widget's pill starts (CaptureWidget): the capture sheet on its own.
        val widget = launch<CaptureActivity>(Intent(context, CaptureActivity::class.java))
        awaitTag(ShareTags.TEXT)
        composeRule.onNodeWithTag(ShareTags.TEXT).performTextReplacement("Pay rent")
        composeRule.onNodeWithTag(ShareTags.SAVE).performClick()
        awaitClosed(widget)

        assertEquals("pending count (widget and sheet show it)", 3, pendingCount())
        assertTrue("nothing reached the server offline", stack.tasks(list = "INBOX").isEmpty())
    }

    /** 3. Network back: within a minute all 3 are in the Inbox, the photo attached. Syncing again adds nothing. */
    private fun step3NetworkBackSendsThemOnceWithThePhoto() {
        device.setOnline(true)

        val inbox = eventually(SYNC_WAIT_MS, "all 3 captures in the Inbox") { stack.tasks(list = "INBOX").takeIf { it.size >= 3 } }
        assertEquals(listOf("Holiday ideas", "Receipt", "Pay rent"), inbox.map { it.title })
        val photoFile = stack.linkedFiles(inbox.single { it.title == "Receipt" }.id).single()
        assertArrayEquals("the photo as shared", PHOTO, stack.fileContent(photoFile))
        eventually(WAIT_MS, "queue empty") { pendingCount().takeIf { it == 0 } }

        runBlocking { captureSync.schedule() }
        device.runQueuedWork()
        eventually(SYNC_WAIT_MS, "second sync finished") { captureWorkFinished().takeIf { it } }
        assertEquals("no duplicates after another sync", inbox.map { it.id }, stack.tasks(list = "INBOX").map { it.id })
        assertEquals("no other new tasks", 4, stack.tasks().size)
    }

    /** 4. Process the Inbox on the phone; Next, Today and Projects show what the web shows. */
    private fun step4ProcessTheInboxAndCompareTheViewsWithTheWeb() {
        val main = launch<MainActivity>(Intent(context, MainActivity::class.java))
        tab("Inbox").performClick()
        await("the synced captures in the Inbox") { composeRule.onAllNodes(hasText("Pay rent")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag(InboxTags.PROCESS).performClick()

        repeat(3) {
            awaitTag(InboxTags.CURRENT)
            val title = listOf("Holiday ideas", "Receipt", "Pay rent").single { current(it) }
            when (title) {
                "Holiday ideas" -> {
                    click(InboxTags.action(InboxActionKind.PROJECT))
                    composeRule.onNodeWithTag(InboxTags.PROMPT_TEXT).performScrollTo().performTextReplacement("Holiday")
                    click(InboxTags.PROMPT_CONFIRM)
                }
                else -> click(InboxTags.action(InboxActionKind.NEXT))
            }
            await("$title processed") { !current(title) }
        }
        await("the Inbox processed") { composeRule.onAllNodesWithTag(InboxTags.CURRENT).fetchSemanticsNodes().isEmpty() }
        eventually(WAIT_MS, "the web's Inbox is empty") { stack.tasks(list = "INBOX").takeIf { it.isEmpty() } }

        val next = stack.tasks(list = "NEXT")
        assertEquals(setOf("File tax return", "Holiday ideas", "Receipt", "Pay rent"), next.map { it.title }.toSet())
        openMoreItem("Next")
        assertRowsShown(NextTags.LIST, next.map { it.id }, "Next")

        // The web's Today: overdue and due today, then Next tasks scheduled for today or earlier.
        val today = LocalDate.now()
        val todayIds =
            (
                stack.tasks(due = "overdue") + stack.tasks(due = "today") +
                    next.filter { it.scheduledDate?.let { d -> !d.isAfter(today) } == true }
            ).map { it.id }
                .distinct()
        assertEquals(listOf(taxReturn.id), todayIds)
        tab("Today").performClick()
        assertRowsShown(TodayTags.LIST, todayIds, "Today")

        val projects = stack.projects().filter { it.status != "COMPLETED" && it.status != "DROPPED" }
        assertEquals(listOf("Holiday"), projects.map { it.title })
        openMoreItem("Projects")
        projects.forEach { project ->
            val tag = ProjectsTags.project(UUID.fromString(project.id))
            await("project ${project.title}") { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        }
        assertEquals(
            "projects on the phone",
            projects.map { ProjectsTags.project(UUID.fromString(it.id)) }.toSet(),
            shownTags { it.matches(PROJECT_TAG) },
        )
        close(main)
    }

    /** 5. A task with a reminder (set on the web) fires a local notification at the due time. */
    private fun step5ReminderFiresALocalNotification() {
        val plants =
            stack.createTask(
                buildJsonObject {
                    put("title", "Water plants")
                    put("list", "NEXT")
                },
            )
        // Whole seconds: the alarm is compared with the reminder time to the millisecond.
        val at = Instant.now().plusMillis(device.reminderLeadMs).truncatedTo(ChronoUnit.SECONDS)
        stack.createReminder(plants.id, at)

        assertEquals(ReminderSyncResult.SYNCED, runBlocking { reminderSync.sync() })
        assertTrue("no notification before the due time", reminderNotifications().isEmpty())
        device.letAlarmGoOff(at)

        val shown = eventually(device.alarmSlackMs, "the reminder notification") { reminderNotifications().takeIf { it.isNotEmpty() } }
        assertEquals(listOf("Water plants"), shown.map { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() })
    }

    private inline fun <reified A : Activity> launch(intent: Intent): ActivityScenario<A> =
        ActivityScenario.launch<A>(intent).also { scenarios += it }

    private fun share(intent: Intent): ActivityScenario<ShareActivity> {
        val scenario = launch<ShareActivity>(intent.setClass(context, ShareActivity::class.java))
        awaitTag(ShareTags.SAVE)
        return scenario
    }

    private fun close(scenario: ActivityScenario<*>) {
        scenario.close()
        scenarios -= scenario
    }

    private fun awaitClosed(scenario: ActivityScenario<*>) {
        composeRule.waitUntil(WAIT_MS) {
            scenario.state == Lifecycle.State.DESTROYED ||
                run {
                    var finishing = false
                    scenario.onActivity { finishing = it.isFinishing }
                    finishing
                }
        }
        close(scenario)
    }

    private fun awaitTag(
        tag: String,
        what: String = tag,
    ) = await("shown: $what") { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** Waits for [condition] on the UI; a timeout reports what the screen showed. */
    private fun await(
        what: String,
        condition: () -> Boolean,
    ) {
        try {
            composeRule.waitUntil(WAIT_MS, condition)
        } catch (e: ComposeTimeoutException) {
            val roots = composeRule.onAllNodes(isRoot())
            val screen = (0 until roots.fetchSemanticsNodes().size).joinToString("\n") { roots[it].printToString() }
            throw AssertionError("Timed out waiting for: $what. The screen:\n$screen", e)
        }
    }

    /**
     * Clicks [tag] once it is enabled: the actions stay disabled while the previous one is saved, and the Inbox drops
     * taps right after an action (a double tap must not process the next item), so the user reads the item first.
     */
    private fun click(tag: String) {
        await("$tag enabled") { composeRule.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        device.pause(READING_MS)
        composeRule.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun current(title: String): Boolean =
        composeRule.onAllNodes(hasText(title) and hasAnyAncestor(hasTestTag(InboxTags.CURRENT))).fetchSemanticsNodes().isNotEmpty()

    private fun tab(label: String): SemanticsNodeInteraction = composeRule.onNode(hasText(label) and isSelectable())

    private fun openMoreItem(label: String) {
        tab("More").performClick()
        composeRule.onNodeWithTag(screenTag(CockpitDestination.More)).performScrollToNode(hasText(label))
        composeRule.onNodeWithText(label).performClick()
        composeRule.waitForIdle()
    }

    /** Every one of [ids] has a row in [list], and no other task has one. */
    private fun assertRowsShown(
        list: String,
        ids: List<String>,
        view: String,
    ) {
        val expected = ids.map { TaskListTags.row(UUID.fromString(it)) }
        val seen = mutableSetOf<String>()
        expected.forEach { tag ->
            awaitTag(list, "the $view list")
            composeRule.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
            seen += shownTags { it.startsWith(ROW_PREFIX) }
        }
        assertEquals("$view on the phone", expected.toSet(), seen)
    }

    private fun shownTags(filter: (String) -> Boolean): Set<String> =
        composeRule
            .onAllNodes(SemanticsMatcher("has a test tag") { it.config.getOrNull(SemanticsProperties.TestTag) != null })
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            .filter(filter)
            .toSet()

    private fun pendingCount(): Int = runBlocking { captureQueue.status.first().pendingCount }

    private fun captureWorkFinished(): Boolean =
        WorkManager
            .getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerCaptureSyncScheduler.WORK_NAME)
            .get()
            .all { it.state.isFinished }

    private fun reminderNotifications(): List<Notification> =
        context
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .map { it.notification }
            .filter { it.channelId == ReminderNotifier.CHANNEL_ID }

    /** Polls [probe] until it returns a value (not null), for up to [timeoutMs]. */
    private fun <T : Any> eventually(
        timeoutMs: Long,
        what: String,
        probe: () -> T?,
    ): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            probe()?.let { return it }
            if (System.nanoTime() > deadline) throw AssertionError("Timed out after $timeoutMs ms waiting for: $what")
            composeRule.waitForIdle()
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val SYNC_WAIT_MS = 60_000L
        const val POLL_MS = 250L
        const val READING_MS = 600L
        const val ROW_PREFIX = "task-row-"
        val PROJECT_TAG = Regex("project-[0-9a-f-]{36}")

        /** A 1×1 PNG (fictitious receipt). */
        val PHOTO: ByteArray =
            Base64.decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
                Base64.DEFAULT,
            )
    }
}
