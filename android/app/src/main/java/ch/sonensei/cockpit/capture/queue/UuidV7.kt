package ch.sonensei.cockpit.capture.queue

import java.security.SecureRandom
import java.util.UUID

/** RFC 9562 UUIDv7: 48-bit Unix milliseconds, version 7, 74 random bits; ids of later captures sort later. */
object UuidV7 {
    private val random = SecureRandom()

    fun generate(epochMs: Long): UUID {
        val randA = random.nextInt() and 0xFFF
        val msb = (epochMs and 0xFFFF_FFFF_FFFFL shl 16) or (0x7L shl 12) or randA.toLong()
        val lsb = (random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL) or Long.MIN_VALUE
        return UUID(msb, lsb)
    }
}
