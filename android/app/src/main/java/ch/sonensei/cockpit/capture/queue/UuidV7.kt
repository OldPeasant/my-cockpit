package ch.sonensei.cockpit.capture.queue

import java.security.SecureRandom
import java.util.Random
import java.util.UUID

/** RFC 9562 UUIDv7 ids for captures, strictly increasing within the process (see [UuidV7Generator]). */
object UuidV7 {
    private val generator = UuidV7Generator()

    fun generate(epochMs: Long): UUID = generator.generate(epochMs)
}

/**
 * RFC 9562 UUIDv7: 48-bit Unix milliseconds, version 7, 12-bit counter (`rand_a`), 62 random bits. The ids one
 * generator returns are strictly increasing (RFC 9562 §6.2 method 1): a new millisecond starts the counter at a random
 * value in its lower half, a repeated (or earlier) millisecond increments it, and an exhausted counter moves on to the
 * next millisecond. So an id generated later always sorts later, even within one millisecond or when the clock steps
 * back.
 */
internal class UuidV7Generator(
    private val random: Random = SecureRandom(),
) {
    private var lastMs = Long.MIN_VALUE
    private var lastCounter = 0

    @Synchronized
    fun generate(epochMs: Long): UUID {
        val ms = epochMs and TIMESTAMP_MASK
        if (ms > lastMs) {
            lastMs = ms
            lastCounter = random.nextInt(COUNTER_START_BOUND)
        } else if (lastCounter < COUNTER_MAX) {
            lastCounter++
        } else {
            lastMs++
            lastCounter = random.nextInt(COUNTER_START_BOUND)
        }
        val msb = (lastMs shl 16) or (0x7L shl 12) or lastCounter.toLong()
        val lsb = (random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL) or Long.MIN_VALUE
        return UUID(msb, lsb)
    }

    private companion object {
        const val TIMESTAMP_MASK = 0xFFFF_FFFF_FFFFL
        const val COUNTER_MAX = 0xFFF
        const val COUNTER_START_BOUND = 0x800
    }
}
