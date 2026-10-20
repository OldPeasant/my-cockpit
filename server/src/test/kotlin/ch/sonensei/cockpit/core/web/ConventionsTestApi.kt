package ch.sonensei.cockpit.core.web

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpHeaders.IF_MATCH
import org.springframework.http.ResponseEntity
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.stereotype.Service
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.ConcurrentHashMap

/**
 * A test-only API that exercises the conventions. `@TestConfiguration` keeps it out of component
 * scanning (and so out of `api/openapi.yaml`); tests opt in with `@Import(ConventionsTestApi::class)`.
 */
@TestConfiguration
class ConventionsTestApi {
	data class Item(
		val id: String,
		val name: String,
		val version: Long,
	)

	data class ItemPatch(
		@field:NotBlank
		@field:Size(max = 10)
		val name: String?,
	)

	/** A service with Bean Validation on its methods: violations surface as ConstraintViolationException. */
	@Service
	@Validated
	class Scheduler {
		fun schedule(
			@Min(1) days: Int,
		): Int = days
	}

	@RestController
	@RequestMapping(BASE)
	class Controller(
		private val scheduler: Scheduler,
	) {
		val items = ConcurrentHashMap<String, Item>()

		fun reset() {
			items.clear()
			items["a"] = Item("a", "Alpha", 1)
		}

		@GetMapping("/items/{id}")
		fun get(
			@PathVariable id: String,
		): ResponseEntity<Item> = find(id).let { ETags.ok(it, it.version) }

		@PatchMapping("/items/{id}")
		fun patch(
			@PathVariable id: String,
			@RequestHeader(IF_MATCH, required = false) ifMatch: String?,
			@Valid @RequestBody patch: ItemPatch,
		): ResponseEntity<Item> {
			val current = find(id)
			ETags.checkIfMatch(ifMatch, current.version)
			val updated = current.copy(name = patch.name!!, version = current.version + 1)
			items[id] = updated
			return ETags.ok(updated, updated.version)
		}

		@DeleteMapping("/items/{id}")
		fun delete(
			@PathVariable id: String,
			@RequestHeader(IF_MATCH, required = false) ifMatch: String?,
		): ResponseEntity<Void> {
			ETags.checkIfMatch(ifMatch, find(id).version)
			items.remove(id)
			return ResponseEntity.noContent().build()
		}

		@GetMapping("/numbers")
		fun numbers(
			@RequestParam(required = false) cursor: String?,
			@RequestParam(required = false) limit: Int?,
		): CursorPage<Int> {
			val request = CursorRequest.of(cursor, limit)
			val after = request.position?.toInt() ?: 0
			val fetched = (1..NUMBER_COUNT).filter { it > after }.take(request.limit + 1)
			return request.page(fetched) { it.toString() }
		}

		@GetMapping("/bounded")
		fun bounded(
			@RequestParam @Min(1) @Max(5) count: Int,
		): Map<String, Int> = mapOf("count" to count)

		@GetMapping("/range")
		fun range(
			@RequestParam @Min(1) to: Int,
			@RequestParam @Max(5) from: Int,
		): Map<String, Int> = mapOf("from" to from, "to" to to)

		@GetMapping("/schedule")
		fun schedule(
			@RequestParam days: Int,
		): Map<String, Int> = mapOf("days" to scheduler.schedule(days))

		@GetMapping("/forbidden")
		fun forbidden(): Nothing = throw AccessDeniedException("Not your item")

		@GetMapping("/bad-credentials")
		fun badCredentials(): Nothing = throw BadCredentialsException("Token for fictitious-user expired")

		@GetMapping("/conflict")
		fun conflict(): Nothing = throw ConflictException("Name already taken")

		@GetMapping("/optimistic-lock")
		fun optimisticLock(): Nothing = throw ObjectOptimisticLockingFailureException(Item::class.java, "a")

		@GetMapping("/optimistic-lock-generic")
		fun optimisticLockGeneric(): Nothing = throw OptimisticLockingFailureException("Row was updated by another transaction")

		@GetMapping("/client")
		fun client(
			@RequestHeader("X-Client-Name") clientName: String,
		): Map<String, String> = mapOf("client" to clientName)

		@GetMapping("/cookie")
		fun cookie(
			@CookieValue("fictitious-hint") hint: String,
		): Map<String, String> = mapOf("hint" to hint)

		@GetMapping("/invalid")
		fun invalid(): Nothing = throw InvalidRequestException("due", "must be in the future")

		@GetMapping("/boom")
		fun boom(): Nothing = throw IllegalStateException(SECRET_MESSAGE)

		private fun find(id: String) = items[id] ?: throw NotFoundException("Item not found")
	}

	companion object {
		const val BASE = "/api/v1/test-conventions"
		const val NUMBER_COUNT = 7
		const val SECRET_MESSAGE = "jdbc:postgresql://db/cockpit password=hunter2 failed"
	}
}
