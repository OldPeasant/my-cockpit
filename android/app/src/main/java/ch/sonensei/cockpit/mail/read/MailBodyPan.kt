package ch.sonensei.cockpit.mail.read

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.splineBasedDecay
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a drag over the mail body pans sideways: the WebView, scrolled within its (zoomed) content width. */
interface MailPanTarget {
    /** The current horizontal scroll, `0..maxScrollX`. */
    val panX: Int

    /** The largest horizontal scroll: content width (at the current zoom) minus view width; 0 when the mail fits. */
    val maxPanX: Int

    fun panBy(dx: Int)
}

/**
 * The horizontal part of a drag: how far the target scrolls when the finger moves [dx] pixels (left is negative, so the
 * content follows the finger), clamped to `0..maxX` from [x]. Sub-pixel parts are carried to the next step, so a slow
 * mostly vertical drag still moves sideways; at an edge the carry is dropped.
 */
internal class HorizontalPan {
    private var carry = 0f

    fun step(
        dx: Float,
        x: Int,
        maxX: Int,
    ): Int {
        val wanted = carry - dx
        val whole = wanted.roundToInt()
        val target = (x + whole).coerceIn(0, maxOf(maxX, 0))
        carry = if (target == x + whole) wanted - whole else 0f
        return target - x
    }

    fun reset() {
        carry = 0f
    }
}

/** A drag delta split into its parts: [panX] scrolls the body sideways, [pageY] is left for the page (finger delta). */
internal data class PanSplit(
    val panX: Int,
    val pageY: Float,
)

/** Splits a finger [delta] over the body: the horizontal part pans [target] (clamped), the vertical part goes to the page. */
internal fun HorizontalPan.split(
    delta: Offset,
    target: MailPanTarget?,
): PanSplit = PanSplit(target?.let { step(delta.x, it.panX, it.maxPanX) } ?: 0, delta.y)

/**
 * One drag handler for both axes over the mail body (no axis lock): once a single finger has moved past the touch slop
 * in any direction, the drag is taken from the WebView and the page's own scroll; each move pans [target] sideways by
 * its horizontal part and scrolls the page (the enclosing vertical scroll, through nested scrolling) by its vertical
 * part. On release both axes fling with the release velocity and stop at their edges. Taps (links), and gestures with
 * a second finger (pinch-zoom) before the slop, are left to the WebView untouched.
 */
internal fun Modifier.mailBodyPan(target: () -> MailPanTarget?): Modifier =
    composed {
        val dispatcher = remember { NestedScrollDispatcher() }
        val density = LocalDensity.current
        val decay = remember(density) { splineBasedDecay<Float>(density) }
        val maxVelocity = LocalViewConfiguration.current.maximumFlingVelocity
        val fling = remember { arrayOfNulls<Job>(1) }
        val scope = rememberCoroutineScope()
        this.nestedScroll(NoConnection, dispatcher).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitPointerEvent(PointerEventPass.Initial).changes.singleOrNull { it.pressed && !it.previousPressed }
                    if (down == null) continue
                    fling[0]?.cancel()
                    val pan = HorizontalPan()
                    val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                    var moved = Offset.Zero
                    var dragging = false
                    var lastUp: PointerInputChange? = null
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!dragging && event.changes.count { it.pressed } > 1) break // pinch: the WebView's
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            lastUp = change
                            if (dragging) change.consume()
                            break
                        }
                        val delta = change.positionChange()
                        velocity.addPosition(change.uptimeMillis, change.position)
                        if (!dragging) {
                            moved += delta
                            if (moved.getDistance() < viewConfiguration.touchSlop) continue
                            dragging = true
                        }
                        event.changes.forEach { it.consume() }
                        val split = pan.split(delta, target())
                        if (split.panX != 0) target()?.panBy(split.panX)
                        if (split.pageY != 0f) {
                            val page = Offset(0f, split.pageY)
                            val pre = dispatcher.dispatchPreScroll(page, NestedScrollSource.UserInput)
                            dispatcher.dispatchPostScroll(Offset.Zero, page - pre, NestedScrollSource.UserInput)
                        }
                    }
                    if (dragging && lastUp != null) {
                        val v = velocity.calculateVelocity(Velocity(maxVelocity, maxVelocity))
                        fling[0] = scope.launch { flingBoth(v, target, dispatcher, decay) }
                    }
                }
            }
        }
    }

private suspend fun flingBoth(
    velocity: Velocity,
    target: () -> MailPanTarget?,
    dispatcher: NestedScrollDispatcher,
    decay: DecayAnimationSpec<Float>,
) {
    coroutineScope {
        if (abs(velocity.x) > 0f) {
            launch {
                val pan = HorizontalPan()
                var last = 0f
                AnimationState(0f, velocity.x).animateDecay(decay) {
                    val delta = value - last
                    last = value
                    val t = target()
                    val moved = t?.let { pan.step(delta, it.panX, it.maxPanX) } ?: 0
                    if (moved != 0) t?.panBy(moved)
                    // At an edge (nothing left to pan this way): stop.
                    if (t == null || (moved == 0 && atEdge(delta, t))) cancelAnimation()
                }
            }
        }
        if (abs(velocity.y) > 0f) {
            launch {
                val page = Velocity(0f, velocity.y)
                val pre = dispatcher.dispatchPreFling(page)
                dispatcher.dispatchPostFling(Velocity.Zero, page - pre)
            }
        }
    }
}

/** Whether a finger delta [dx] pushes [t] against the edge it can no longer pan past. */
internal fun atEdge(
    dx: Float,
    t: MailPanTarget,
): Boolean = (dx > 0f && t.panX <= 0) || (dx < 0f && t.panX >= t.maxPanX)

/** The body only dispatches to the page; it consumes nothing its children scroll. */
private val NoConnection = object : NestedScrollConnection {}
