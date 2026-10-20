package ch.sonensei.cockpit.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitDestinationTest {
    @Test
    fun bottomBarHoldsTodayInboxCalendarMailMoreInThatOrder() {
        assertEquals(
            listOf(
                CockpitDestination.Today,
                CockpitDestination.Inbox,
                CockpitDestination.Calendar,
                CockpitDestination.Mail,
                CockpitDestination.More,
            ),
            CockpitDestination.bottomBar,
        )
    }

    @Test
    fun moreListsTheSecondaryDestinationsInTheSpecifiedOrder() {
        assertEquals(
            listOf(
                CockpitDestination.Next,
                CockpitDestination.Projects,
                CockpitDestination.Waiting,
                CockpitDestination.Someday,
                CockpitDestination.Notes,
                CockpitDestination.Files,
                CockpitDestination.Contacts,
                CockpitDestination.Bookmarks,
                CockpitDestination.Journal,
                CockpitDestination.Habits,
                CockpitDestination.Review,
                CockpitDestination.Settings,
            ),
            CockpitDestination.moreItems,
        )
    }

    @Test
    fun everyDestinationIsInExactlyOneSection() {
        val all = CockpitDestination.bottomBar + CockpitDestination.moreItems

        assertEquals(CockpitDestination.entries.toSet(), all.toSet())
        assertEquals(CockpitDestination.entries.size, all.size)
    }

    @Test
    fun startDestinationIsTodayInTheBottomBar() {
        assertSame(CockpitDestination.Today, CockpitDestination.start)
        assertTrue(CockpitDestination.start in CockpitDestination.bottomBar)
    }

    @Test
    fun routesAreUniqueAndNonBlank() {
        val routes = CockpitDestination.entries.map { it.route }

        assertTrue(routes.all { it.isNotBlank() })
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun labelResourcesAreUnique() {
        val labels = CockpitDestination.entries.map { it.labelRes }

        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun fromRouteFindsEveryDestinationByItsRoute() {
        CockpitDestination.entries.forEach {
            assertSame(it, CockpitDestination.fromRoute(it.route))
        }
    }

    @Test
    fun fromRouteReturnsNullForNullEmptyUnknownOrDifferentlyCasedRoutes() {
        assertNull(CockpitDestination.fromRoute(null))
        assertNull(CockpitDestination.fromRoute(""))
        assertNull(CockpitDestination.fromRoute("not-a-screen"))
        assertNull(CockpitDestination.fromRoute("Inbox"))
    }

    @Test
    fun bottomBarItemForABottomBarDestinationIsItself() {
        CockpitDestination.bottomBar.forEach {
            assertSame(it, CockpitDestination.bottomBarItemFor(it))
        }
    }

    @Test
    fun bottomBarItemForAMoreDestinationIsMore() {
        CockpitDestination.moreItems.forEach {
            assertSame(CockpitDestination.More, CockpitDestination.bottomBarItemFor(it))
        }
    }

    @Test
    fun bottomBarItemForNoDestinationIsNull() {
        assertNull(CockpitDestination.bottomBarItemFor(null))
    }
}
