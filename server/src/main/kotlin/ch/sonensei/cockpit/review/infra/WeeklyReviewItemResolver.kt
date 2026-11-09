package ch.sonensei.cockpit.review.infra

import ch.sonensei.cockpit.core.items.domain.ItemResolver
import ch.sonensei.cockpit.core.items.domain.ItemType
import ch.sonensei.cockpit.core.items.domain.ResolvedItem
import ch.sonensei.cockpit.review.domain.WeeklyReviewReminders
import org.springframework.stereotype.Component
import java.util.UUID

/** The one [ItemType.WEEKLY_REVIEW] item: the weekly review as a whole, which its reminder points at. */
@Component
class WeeklyReviewItemResolver : ItemResolver {
	override val type = ItemType.WEEKLY_REVIEW

	override fun resolve(id: UUID): ResolvedItem? =
		if (id == WeeklyReviewReminders.ITEM.id) ResolvedItem(title = "Weekly review", icon = "event_repeat", deepLink = "/review") else null
}
