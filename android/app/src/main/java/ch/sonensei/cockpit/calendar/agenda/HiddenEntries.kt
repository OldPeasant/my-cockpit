package ch.sonensei.cockpit.calendar.agenda

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The timed entries of one day column that lie entirely outside the visible part of the day or week grid.
 * Partly visible entries count as visible.
 */
data class HiddenEntries(
    /** Entries ending at or above the top edge of the viewport. */
    val above: Int,
    /** Entries starting at or below the bottom edge of the viewport. */
    val below: Int,
    /** The scroll offset that brings the nearest entry above into view; null when [above] is 0. */
    val scrollToAbove: Int?,
    /** The scroll offset that brings the nearest entry below into view; null when [below] is 0. */
    val scrollToBelow: Int?,
) {
    companion object {
        val NONE = HiddenEntries(0, 0, null, null)
    }
}

/**
 * Which of a day column's timed entries are hidden above or below the viewport.
 *
 * @param entries the column's entries; all-day ones are ignored (they are in the all-day strip).
 * @param scrollOffset how far the grid is scrolled, in px.
 * @param viewportHeight the height of the visible part of the grid, in px.
 * @param pxPerMinute the grid's scale (the hour height in px / 60).
 */
fun hiddenEntries(
    entries: List<DayEntry>,
    scrollOffset: Int,
    viewportHeight: Int,
    pxPerMinute: Float,
): HiddenEntries {
    if (viewportHeight <= 0) return HiddenEntries.NONE
    val viewTop = scrollOffset.toFloat()
    val viewBottom = (scrollOffset + viewportHeight).toFloat()
    var above = 0
    var below = 0
    var nearestAbove: Pair<Float, Float>? = null
    var nearestBelow: Pair<Float, Float>? = null
    for (entry in entries) {
        if (entry.allDay) continue
        val top = entry.startMinute * pxPerMinute
        val bottom = visibleEnd(entry) * pxPerMinute
        when {
            bottom <= viewTop -> {
                above++
                if (nearestAbove == null || bottom > nearestAbove.second) nearestAbove = top to bottom
            }
            top >= viewBottom -> {
                below++
                if (nearestBelow == null || top < nearestBelow.first) nearestBelow = top to bottom
            }
        }
    }
    // Above: its start at the top edge. Below: its end at the bottom edge, or its start at the top edge if it is
    // taller than the viewport.
    val toAbove = nearestAbove?.let { (top, _) -> top.roundToInt() }
    val toBelow = nearestBelow?.let { (top, bottom) -> min(top, bottom - viewportHeight).roundToInt() }
    return HiddenEntries(above, below, toAbove, toBelow)
}
