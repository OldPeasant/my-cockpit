package ch.sonensei.cockpit.dashboard

/** A stored layout showing [shown] in this order, every other widget hidden after them. */
internal fun layoutOf(vararg shown: WidgetId): List<WidgetSlot> =
    shown.map { WidgetSlot(it, visible = true) } + WidgetId.entries.filter { it !in shown }.map { WidgetSlot(it, visible = false) }
