package ch.sonensei.cockpit

import ch.sonensei.cockpit.support.PostgresTestBase
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class CockpitServerApplicationTests : PostgresTestBase() {
	@Test
	fun contextLoads() {
	}
}
