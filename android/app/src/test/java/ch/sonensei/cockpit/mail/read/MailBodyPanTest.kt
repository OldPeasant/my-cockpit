package ch.sonensei.cockpit.mail.read

import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.sonensei.cockpit.ui.theme.CockpitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** A drag over the mail body pans it sideways and scrolls the page in the same gesture (no axis lock). */
@RunWith(AndroidJUnit4::class)
class MailBodyPanTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class Target(
        override var panX: Int = 0,
        override val maxPanX: Int = 500,
    ) : MailPanTarget {
        override fun panBy(dx: Int) {
            panX += dx
        }
    }

    @Test
    fun `a diagonal delta pans sideways against the finger and leaves the vertical part to the page`() {
        val split = HorizontalPan().split(Offset(-30f, -40f), Target(panX = 100))

        assertEquals(PanSplit(panX = 30, pageY = -40f), split)
    }

    @Test
    fun `a mostly vertical drag keeps its small horizontal part, also below a pixel per step`() {
        val pan = HorizontalPan()
        val target = Target(panX = 100)
        repeat(4) { target.panBy(pan.split(Offset(-0.5f, -20f), target).panX) }

        assertEquals(102, target.panX)
    }

    @Test
    fun `the pan is clamped to the content range`() {
        val pan = HorizontalPan()

        assertEquals(-10, pan.step(dx = 50f, x = 10, maxX = 500))
        assertEquals(20, pan.step(dx = -50f, x = 480, maxX = 500))
        assertEquals(0, pan.step(dx = -50f, x = 500, maxX = 500))
    }

    @Test
    fun `a mail no wider than the screen does not pan sideways`() {
        val split = HorizontalPan().split(Offset(-80f, -10f), Target(maxPanX = 0))

        assertEquals(PanSplit(panX = 0, pageY = -10f), split)
        assertEquals(0, HorizontalPan().step(dx = 80f, x = 0, maxX = -5))
    }

    @Test
    fun `without a WebView yet nothing pans`() {
        assertEquals(PanSplit(0, 5f), HorizontalPan().split(Offset(-9f, 5f), null))
    }

    @Test
    fun `the edges stop a fling`() {
        assertTrue(atEdge(5f, Target(panX = 0)))
        assertTrue(atEdge(-5f, Target(panX = 500)))
        assertFalse(atEdge(-5f, Target(panX = 200)))
        assertFalse(atEdge(5f, Target(panX = 200)))
    }

    @Test
    fun `at an edge the sub-pixel carry is dropped, so the way back starts fresh`() {
        val pan = HorizontalPan()

        assertEquals(0, pan.step(dx = -0.6f, x = 500, maxX = 500))
        // With the 0.4 px carry kept this would already move back by one pixel.
        assertEquals(0, pan.step(dx = 0.3f, x = 500, maxX = 500))
        assertEquals(-1, pan.step(dx = 0.3f, x = 500, maxX = 500))
    }

    @Test
    fun `a reset forgets the sub-pixel carry of the previous drag`() {
        val pan = HorizontalPan()

        assertEquals(0, pan.step(dx = -0.4f, x = 100, maxX = 500))
        pan.reset()
        assertEquals(0, pan.step(dx = -0.4f, x = 100, maxX = 500))
        assertEquals(1, pan.step(dx = -0.4f, x = 100, maxX = 500))
    }

    @Test
    fun `a mostly horizontal delta keeps its small vertical part`() {
        assertEquals(PanSplit(panX = 40, pageY = -3f), HorizontalPan().split(Offset(-40f, -3f), Target(panX = 0)))
    }

    @Test
    fun `no horizontal movement is never an edge`() {
        assertFalse(atEdge(0f, Target(panX = 0)))
        assertFalse(atEdge(0f, Target(panX = 500)))
    }

    /** A MailWebView with a given (zoomed) content width [range] and view width [extent], as WebView reports them. */
    private class SizedWebView(
        context: Context,
        private val range: Int,
        private val extent: Int,
    ) : MailWebView(context) {
        override fun computeHorizontalScrollRange() = range

        override fun computeHorizontalScrollExtent() = extent
    }

    @Test
    fun `the WebView pans within its content width at the current zoom minus the view width`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(1200, SizedWebView(context, range = 2000, extent = 800).maxPanX)
        assertEquals(0, SizedWebView(context, range = 800, extent = 800).maxPanX)
        assertEquals(0, SizedWebView(context, range = 600, extent = 800).maxPanX)
    }

    @Test
    fun `panning the WebView scrolls it sideways only`() {
        val view = SizedWebView(ApplicationProvider.getApplicationContext(), range = 2000, extent = 800)

        view.panBy(300)
        view.panBy(-100)

        assertEquals(200, view.panX)
        assertEquals(200, view.scrollX)
        assertEquals(0, view.scrollY)
    }

    /** A WebView whose content is [WIDE] pixels wider than the view and [TALL] pixels high, as a laid-out newsletter. */
    private class WideWebView(
        context: Context,
    ) : MailWebView(context) {
        var range = WIDE

        override val maxPanX: Int get() = range

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), TALL)
        }
    }

    private val message =
        MailMessage(
            id = UUID.fromString("00000000-0000-7000-8000-000000000001"),
            subject = "Newsletter",
            from = "Anna Example <anna@example.org>",
            to = listOf("me@example.org"),
            cc = emptyList(),
            date = Instant.parse("2026-10-21T06:00:00Z"),
            seen = true,
            html = "<table width=\"800\"><tr><td>Wide</td></tr></table>",
            text = "Wide",
            remoteImagesBlocked = 0,
            attachments = emptyList(),
        )

    private var webView: WideWebView? = null

    /** The horizontal range the shown body reports: [WIDE] for a newsletter, 0 for a mail that fits the screen. */
    private var panRange = WIDE

    /**
     * Each pointer event over the body as seen after the body's own handlers (Main pass, outside them), with whether any
     * change was consumed. Consumed changes make the AndroidView interop cancel the WebView's gesture; unconsumed ones
     * reach it (links, pinch-zoom). The WebView itself cannot be asked: under Robolectric it is never laid out.
     */
    private val seen = mutableListOf<Pair<PointerEventType, Boolean>>()

    private val recorder =
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    seen += event.type to event.changes.any { it.isConsumed }
                }
            }
        }

    private fun show() =
        composeRule.setContent {
            CockpitTheme {
                MailReadContent(
                    MailReadUiState(message = message),
                    MailReadCallbacks(),
                    zone = ZoneId.of("Europe/Zurich"),
                    body = { html, modifier ->
                        MailBodyView(html, modifier.then(recorder), message.id) {
                            WideWebView(it).also { v ->
                                v.range = panRange
                                webView = v
                            }
                        }
                    },
                )
            }
        }

    private fun pageScroll(): Float =
        composeRule
            .onNodeWithTag(MailReadTags.PAGE)
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    @Test
    fun `a diagonal swipe on a wide body changes both the page scroll and the WebView's scrollX`() {
        show()
        composeRule.mainClock.autoAdvance = true

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput {
            swipe(Offset(width * 0.9f, 220f), Offset(width * 0.1f, 20f), durationMillis = 400)
        }
        composeRule.waitForIdle()

        val x = webView!!.scrollX
        assertTrue("scrollX $x", x >= 150)
        assertTrue("page ${pageScroll()}", pageScroll() >= 150f)
    }

    @Test
    fun `a fast diagonal fling keeps going in both axes and stops at the horizontal edge`() {
        show()

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput {
            swipe(Offset(width * 0.9f, 220f), Offset(width * 0.1f, 20f), durationMillis = 50)
        }
        composeRule.waitForIdle()

        assertTrue("scrollX ${webView!!.scrollX}", webView!!.scrollX > 260)
        assertTrue("scrollX ${webView!!.scrollX}", webView!!.scrollX <= WIDE)
        assertTrue("page ${pageScroll()}", pageScroll() > 200f)
    }

    @Test
    fun `a plain vertical swipe on the body scrolls the page only`() {
        show()

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput {
            swipe(Offset(centerX, 220f), Offset(centerX, 20f), durationMillis = 400)
        }
        composeRule.waitForIdle()

        assertEquals(0, webView!!.scrollX)
        assertTrue("page ${pageScroll()}", pageScroll() >= 150f)
    }

    @Test
    fun `a tap on the body is not consumed, so the WebView gets the link click`() {
        show()

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput { click(Offset(centerX, 100f)) }
        composeRule.waitForIdle()

        assertEquals(listOf(PointerEventType.Press to false, PointerEventType.Release to false), seen)
        assertEquals(0, webView!!.scrollX)
        assertEquals(0f, pageScroll())
    }

    @Test
    fun `a move below the touch slop is not consumed and scrolls nothing`() {
        show()

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput {
            down(Offset(centerX, 100f))
            moveBy(Offset(-2f, -2f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals(listOf(PointerEventType.Press, PointerEventType.Move, PointerEventType.Release), seen.map { it.first })
        assertFalse("events $seen", seen.any { it.second })
        assertEquals(0, webView!!.scrollX)
        assertEquals(0f, pageScroll())
    }

    @Test
    fun `a drag past the touch slop is consumed from then on, up to the release`() {
        show()

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput {
            swipe(Offset(width * 0.9f, 220f), Offset(width * 0.1f, 20f), durationMillis = 400)
        }
        composeRule.waitForIdle()

        assertFalse("events $seen", seen.first().second)
        assertEquals(PointerEventType.Release to true, seen.last())
        val firstTaken = seen.indexOfFirst { it.second }
        assertTrue("events $seen", firstTaken in 1 until seen.size)
        assertTrue("events $seen", seen.drop(firstTaken).all { it.second })
    }

    @Test
    fun `a second finger before the slop leaves the pinch to the WebView`() {
        show()

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput {
            down(0, Offset(centerX - 10f, 150f))
            down(1, Offset(centerX + 10f, 150f))
            repeat(10) {
                moveBy(0, Offset(-8f, 0f), delayMillis = 16)
                moveBy(1, Offset(8f, 0f), delayMillis = 0)
            }
            up(1)
            up(0)
        }
        composeRule.waitForIdle()

        assertTrue("events $seen", seen.count { it.first == PointerEventType.Move } >= 10)
        assertFalse("events $seen", seen.any { it.second })
        assertEquals(0, webView!!.scrollX)
        assertEquals(0f, pageScroll())
    }

    @Test
    fun `a second finger after the slop keeps the one-finger drag going`() {
        show()

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput {
            down(0, Offset(width * 0.8f, 200f))
            repeat(5) { moveBy(0, Offset(-10f, -10f), delayMillis = 16) }
            down(1, Offset(width * 0.5f, 100f))
            repeat(5) { moveBy(0, Offset(-10f, -10f), delayMillis = 16) }
            up(1)
            up(0)
        }
        composeRule.waitForIdle()

        assertTrue("scrollX ${webView!!.scrollX}", webView!!.scrollX >= 80)
        assertTrue("page ${pageScroll()}", pageScroll() >= 80f)
    }

    @Test
    fun `a mostly horizontal swipe keeps its small vertical part for the page`() {
        show()

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput {
            swipe(Offset(width * 0.9f, 140f), Offset(width * 0.1f, 100f), durationMillis = 400)
        }
        composeRule.waitForIdle()

        assertTrue("scrollX ${webView!!.scrollX}", webView!!.scrollX >= 150)
        assertTrue("page ${pageScroll()}", pageScroll() >= 20f)
    }

    @Test
    fun `dragging towards the top-left edges moves nothing past them`() {
        show()

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput {
            swipe(Offset(width * 0.1f, 20f), Offset(width * 0.9f, 220f), durationMillis = 50)
        }
        composeRule.waitForIdle()

        assertEquals(0, webView!!.scrollX)
        assertEquals(0f, pageScroll())
    }

    @Test
    fun `a fling stops exactly at the right edge of the content`() {
        show()
        composeRule.runOnUiThread { webView!!.scrollTo(WIDE - 50, 0) }

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput {
            swipe(Offset(width * 0.6f, 120f), Offset(width * 0.4f, 120f), durationMillis = 30)
        }
        composeRule.waitForIdle()

        assertEquals(WIDE, webView!!.scrollX)
    }

    @Test
    fun `touching the body again stops a running fling`() {
        show()
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput {
            swipe(Offset(width * 0.9f, 120f), Offset(width * 0.1f, 120f), durationMillis = 50)
        }
        composeRule.mainClock.advanceTimeBy(48)
        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput { down(Offset(centerX, 120f)) }
        composeRule.mainClock.advanceTimeBy(32)
        val stopped = webView!!.scrollX
        composeRule.mainClock.advanceTimeBy(2_000)

        assertTrue("scrollX $stopped", stopped in 1 until WIDE)
        assertEquals(stopped, webView!!.scrollX)
        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput { up() }
    }

    @Test
    fun `a mail no wider than the screen scrolls only vertically, also on a diagonal fling`() {
        panRange = 0
        show()

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput {
            swipe(Offset(width * 0.9f, 220f), Offset(width * 0.1f, 20f), durationMillis = 50)
        }
        composeRule.waitForIdle()

        assertEquals(0, webView!!.scrollX)
        assertTrue("page ${pageScroll()}", pageScroll() >= 150f)
    }

    @Test
    fun `a fast horizontal fling pans on past the finger and leaves the page where it is`() {
        show()

        composeRule.onNodeWithTag(MailReadTags.BODY).performTouchInput {
            swipe(Offset(width * 0.7f, 120f), Offset(width * 0.3f, 120f), durationMillis = 50)
        }
        composeRule.waitForIdle()

        assertTrue("scrollX ${webView!!.scrollX}", webView!!.scrollX > 200)
        assertEquals(0f, pageScroll())
    }

    @Test
    fun `dragging the headers still scrolls the page and leaves the body alone`() {
        show()

        composeRule.onNodeWithTag(MailReadTags.HEADERS).performTouchInput {
            swipe(Offset(centerX, centerY), Offset(centerX - 100f, centerY - 300f), durationMillis = 400)
        }
        composeRule.waitForIdle()

        assertTrue("page ${pageScroll()}", pageScroll() >= 150f)
        assertEquals(0, webView!!.scrollX)
        assertTrue("body events $seen", seen.isEmpty())
    }

    private companion object {
        const val WIDE = 1200
        const val TALL = 6000
    }
}
