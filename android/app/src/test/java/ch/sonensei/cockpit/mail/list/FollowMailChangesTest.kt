package ch.sonensei.cockpit.mail.list

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** The mail views follow the change events only while started (shown, app in the foreground), never in the background. */
@RunWith(AndroidJUnit4::class)
class FollowMailChangesTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The lifecycle the screen sees; a test moves it to the background and back. */
    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private val starts = AtomicInteger()
    private val stops = AtomicInteger()

    private fun show(owner: TestOwner) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                FollowMailChanges(key = "screen") {
                    starts.incrementAndGet()
                    try {
                        awaitCancellation()
                    } finally {
                        stops.incrementAndGet()
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a shown screen follows the changes, stops in the background and follows again when back`() {
        val owner = TestOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
        show(owner)
        assertEquals(1, starts.get())
        assertEquals(0, stops.get())

        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        composeRule.waitForIdle()
        assertEquals(1, stops.get())

        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.waitForIdle()
        assertEquals(2, starts.get())
        assertEquals(1, stops.get())
    }

    @Test
    fun `a screen that is not started yet does not follow the changes`() {
        val owner = TestOwner().apply { registry.currentState = Lifecycle.State.CREATED }
        show(owner)
        assertEquals(0, starts.get())

        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        composeRule.waitForIdle()
        assertEquals(1, starts.get())
    }
}
