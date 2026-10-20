package ch.sonensei.cockpit

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the unit-test toolchain from and-deps: MockK, kotlinx-coroutines-test and Turbine must be on the
 * test classpath and work together on the JVM, so feature tasks can rely on them.
 */
class TestLibrariesSmokeTest {
    private interface Greeter {
        suspend fun greet(name: String): String
    }

    @Test
    fun mockkStubsSuspendFunctionsInsideRunTest() =
        runTest {
            val greeter = mockk<Greeter>()
            coEvery { greeter.greet("Ada Example") } returns "Hello, Ada Example"

            assertEquals("Hello, Ada Example", greeter.greet("Ada Example"))
            coVerify(exactly = 1) { greeter.greet("Ada Example") }
        }

    // currentTime is still experimental in kotlinx-coroutines-test; the opt-in is its intended usage.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun runTestSkipsDelaysWithVirtualTime() =
        runTest {
            delay(60_000)

            assertEquals(60_000L, currentTime)
        }

    @Test
    fun turbineObservesStateFlowEmissionsInOrder() =
        runTest {
            val state = MutableStateFlow("inbox")

            state.test {
                assertEquals("inbox", awaitItem())
                state.value = "next"
                assertEquals("next", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
}
