package ch.sonensei.cockpit.core.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class ApiExceptionsTests {
	private val advice = ProblemDetailsAdvice()

	@Test
	fun `each exception maps to its status with a client-safe default detail`() {
		val expected =
			mapOf(
				NotFoundException() to HttpStatus.NOT_FOUND,
				ConflictException() to HttpStatus.CONFLICT,
				PreconditionFailedException() to HttpStatus.PRECONDITION_FAILED,
				PreconditionRequiredException() to HttpStatus.PRECONDITION_REQUIRED,
				InvalidRequestException("title", "must not be blank") to HttpStatus.BAD_REQUEST,
			)
		for ((exception, status) in expected) {
			val response = advice.handleApiException(exception)
			response.statusCode shouldBe status
			response.body!!.status shouldBe status.value()
			response.body!!.detail shouldBe exception.message
		}
		NotFoundException().message shouldBe "Resource not found"
	}

	@Test
	fun `only validation problems carry the errors extension`() {
		val invalid = advice.handleApiException(InvalidRequestException("title", "must not be blank")).body!!
		invalid.properties!!["errors"] shouldBe listOf(FieldError("title", "must not be blank"))

		advice.handleApiException(NotFoundException()).body!!.properties shouldBe null
	}
}
