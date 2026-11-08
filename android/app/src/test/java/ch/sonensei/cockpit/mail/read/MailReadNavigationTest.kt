package ch.sonensei.cockpit.mail.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/** A mail's place in the list it was opened from: position, count and neighbours, `null` when it is not in the list. */
class MailReadNavigationTest {
    private fun id(n: Int): UUID = UUID.fromString("00000000-0000-7000-8000-%012d".format(n))

    private val ids = listOf(id(1), id(2), id(3))

    private val opened = mutableListOf<UUID>()

    private fun of(
        list: List<UUID>,
        id: UUID,
    ) = MailReadNavigation.of(list, id) { opened += it }

    @Test
    fun `a mail in the middle has both neighbours`() {
        val navigation = of(ids, id(2))!!

        assertEquals(1, navigation.position)
        assertEquals(3, navigation.count)
        assertEquals(id(1), navigation.previous)
        assertEquals(id(3), navigation.next)
    }

    @Test
    fun `the first mail has no previous`() {
        val navigation = of(ids, id(1))!!

        assertEquals(0, navigation.position)
        assertNull(navigation.previous)
        assertEquals(id(2), navigation.next)
    }

    @Test
    fun `the last mail has no next`() {
        val navigation = of(ids, id(3))!!

        assertEquals(2, navigation.position)
        assertEquals(3, navigation.count)
        assertEquals(id(2), navigation.previous)
        assertNull(navigation.next)
    }

    @Test
    fun `a single mail has no neighbours`() {
        val navigation = of(listOf(id(7)), id(7))!!

        assertEquals(0, navigation.position)
        assertEquals(1, navigation.count)
        assertNull(navigation.previous)
        assertNull(navigation.next)
    }

    @Test
    fun `a mail that is not in the list has no navigation`() {
        assertNull(of(ids, id(9)))
    }

    @Test
    fun `an empty list has no navigation`() {
        assertNull(of(emptyList(), id(1)))
    }

    @Test
    fun `open shows the mail it is given`() {
        val navigation = of(ids, id(2))!!

        navigation.open(navigation.next!!)
        navigation.open(navigation.previous!!)

        assertEquals(listOf(id(3), id(1)), opened)
    }
}
