package ch.sonensei.cockpit.core.testsupport

import ch.sonensei.cockpit.core.auth.domain.LoginThrottle
import io.swagger.v3.oas.annotations.Hidden
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * End-to-end test hooks (profile `e2e` only, not part of the API contract):
 * - `POST /api/test/clock {"now": "2026-03-01T08:00:00Z"}` sets the server clock, which runs on from there;
 * - `POST /api/test/reset` empties every table (except Flyway's history), resets the clock to real time and clears
 *   the in-memory login throttle, so the stack is as fresh as after the first start (setup required again).
 */
@RestController
@Profile("e2e")
@Hidden
@RequestMapping(TestSupportConfiguration.TEST_PATH)
class TestSupportController(
	private val clock: ShiftableClock,
	private val jdbc: JdbcTemplate,
	private val throttle: LoginThrottle,
) {
	private val log = LoggerFactory.getLogger(javaClass)

	@PostMapping("/clock")
	fun setClock(
		@Valid @RequestBody request: ClockRequest,
	): ClockResponse {
		clock.set(request.now!!)
		log.info("Test clock set to {}", request.now)
		return ClockResponse(clock.instant())
	}

	@PostMapping("/reset")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Transactional
	fun reset() {
		val tables =
			jdbc.queryForList(
				"select quote_ident(tablename) from pg_tables where schemaname = current_schema() and tablename <> ?",
				String::class.java,
				FLYWAY_HISTORY,
			)
		if (tables.isNotEmpty()) jdbc.execute("truncate table ${tables.joinToString()} restart identity cascade")
		clock.reset()
		throttle.recordSuccess()
		log.info("Test reset: {} tables emptied", tables.size)
	}

	private companion object {
		const val FLYWAY_HISTORY = "flyway_schema_history"
	}
}

data class ClockRequest(
	@field:NotNull
	val now: Instant?,
)

data class ClockResponse(
	val now: Instant,
)
