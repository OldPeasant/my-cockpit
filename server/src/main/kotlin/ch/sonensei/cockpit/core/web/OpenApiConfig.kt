package ch.sonensei.cockpit.core.web

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Metadata of the generated contract `api/openapi.yaml` (see `api/README.md`). The server URL is
 * fixed to `/` so the generated file does not depend on the host or port it was fetched from.
 * The `ProblemDetail` schema documents the error body of every endpoint ([ProblemDetailsAdvice]).
 */
@Configuration
class OpenApiConfig {
	@Bean
	fun cockpitOpenApi(): OpenAPI =
		OpenAPI()
			.info(Info().title("My Cockpit API").version("v1"))
			.servers(listOf(Server().url("/")))
			.components(
				Components()
					.addSchemas("FieldError", fieldErrorSchema())
					.addSchemas("ProblemDetail", problemDetailSchema()),
			)

	private fun fieldErrorSchema(): Schema<*> =
		ObjectSchema()
			.description("A validation error of one request field or parameter.")
			.addProperty("field", StringSchema())
			.addProperty("message", StringSchema())
			.required(listOf("field", "message"))

	private fun problemDetailSchema(): Schema<*> =
		ObjectSchema()
			.description("RFC 9457 Problem Details, the body of every error response.")
			.addProperty("type", StringSchema().format("uri"))
			.addProperty("title", StringSchema())
			.addProperty("status", IntegerSchema())
			.addProperty("detail", StringSchema())
			.addProperty("instance", StringSchema().format("uri"))
			.addProperty(
				"errors",
				ArraySchema()
					.items(Schema<Any>().`$ref`("#/components/schemas/FieldError"))
					.description("Field errors, only on 400 validation errors."),
			).required(listOf("title", "status"))
}
