package ch.sonensei.cockpit.calendar.agenda

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import java.time.LocalTime

/** Minutes a long press in the hour grid is rounded down to. */
internal const val SLOT_MINUTES = 15

/** The start of the slot under [y] (px from the top of a 24-hour column): rounded down to [SLOT_MINUTES]. */
internal fun slotStart(
    y: Float,
    pxPerMinute: Float,
): LocalTime {
    val minute = (y / pxPerMinute).toInt().coerceIn(0, MINUTES_PER_DAY - 1)
    val slot = minute - minute % SLOT_MINUTES
    return LocalTime.of(slot / 60, slot % 60)
}

/**
 * A long press (the platform long-press timeout) anywhere on the element, also on children that are clickable
 * themselves: [onPress] is called with the position once it is recognised, [onRelease] when the finger is lifted
 * after that, [onCancel] when the gesture is taken away. The callbacks are captured until [key] changes, so pass
 * values that change in between through `rememberUpdatedState`. A finger that moves beyond the touch slop before the timeout
 * is a scroll or a swipe and never becomes a long press; a tap stays a tap. Once recognised, the rest of the gesture
 * is consumed, so neither a child's click nor a scroll or the pager sees it.
 *
 * The events are observed in the initial pass, before the children and the scroll containers, so a clickable event
 * block does not hide the press from the grid.
 */
internal fun Modifier.longPressAt(
    key: Any?,
    onPress: (Offset) -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
): Modifier =
    pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val slop = viewConfiguration.touchSlop
            // Null when the timeout ran out while the finger stayed down and in place: a long press.
            val ended =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var over = false
                    while (!over) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        over = change == null ||
                            !change.pressed ||
                            event.changes.size > 1 ||
                            (change.position - down.position).getDistance() > slop ||
                            change.isConsumed
                    }
                    true
                }
            if (ended != null) return@awaitEachGesture
            onPress(down.position)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    onCancel()
                    return@awaitEachGesture
                }
                // Nothing consumes before the initial pass, so an up that arrives consumed is Compose's synthetic
                // cancel event (ACTION_CANCEL, the gesture was taken away), not a lifted finger.
                val cancelled = !change.pressed && change.isConsumed
                event.changes.forEach { it.consume() }
                if (cancelled) {
                    onCancel()
                    return@awaitEachGesture
                }
                if (!change.pressed) {
                    onRelease()
                    return@awaitEachGesture
                }
            }
        }
    }
