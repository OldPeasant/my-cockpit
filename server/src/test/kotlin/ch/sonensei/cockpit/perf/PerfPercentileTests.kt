package ch.sonensei.cockpit.perf

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The nearest-rank percentile behind the p95 assertion of [PerfBaselineTests]; plain unit test, no Spring, no database. */
class PerfPercentileTests {
	private val oneToFifty = List(50) { it + 1.0 }

	@Test
	fun `p95 of 50 samples is the 48th smallest, so the two slowest calls are outliers`() {
		PerfBaselineTests.percentile(oneToFifty, 95.0) shouldBe 48.0
	}

	@Test
	fun `p50 of 50 samples is the 25th smallest`() {
		PerfBaselineTests.percentile(oneToFifty, 50.0) shouldBe 25.0
	}

	@Test
	fun `p100 is the maximum and a tiny percentile the minimum`() {
		PerfBaselineTests.percentile(oneToFifty, 100.0) shouldBe 50.0
		PerfBaselineTests.percentile(oneToFifty, 0.0) shouldBe 1.0
		PerfBaselineTests.percentile(oneToFifty, 1.0) shouldBe 1.0
	}

	@Test
	fun `a rank between two samples rounds up to the next sample`() {
		// 95 % of 10 = 9.5 -> rank 10; 95 % of 20 = 19 -> rank 19
		PerfBaselineTests.percentile(List(10) { it + 1.0 }, 95.0) shouldBe 10.0
		PerfBaselineTests.percentile(List(20) { it + 1.0 }, 95.0) shouldBe 19.0
	}

	@Test
	fun `a single sample is every percentile`() {
		PerfBaselineTests.percentile(listOf(7.5), 50.0) shouldBe 7.5
		PerfBaselineTests.percentile(listOf(7.5), 95.0) shouldBe 7.5
	}
}
