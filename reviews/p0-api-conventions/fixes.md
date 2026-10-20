# Fix Report

Date: 2026-10-20

Task: p0-api-conventions - API conventions: errors, ETags, pagination, OpenAPI contract file

Scope: findings CR-1, CR-2, CR-3, CR-4 and CR-5 from `reviews/p0-api-conventions/review.md`, on top of
HEAD 9c06347 (base 85d39d7). The parent worker approved fixing CR-2 and CR-5 in `server/build.gradle.kts`,
which is outside the task's `touches` list.

## Fixed findings

### CR-1 - Type-mismatch and missing-parameter 400s have no `errors` field-error list

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/web/ProblemDetailsAdvice.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/web/ConventionsTestApi.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/web/ApiConventionsWebTests.kt`

Problem:
`TypeMismatchException` (for example `?limit=abc`), `MissingServletRequestParameterException` and
`MissingRequestHeaderException` used the default `ResponseEntityExceptionHandler` handling. That returned
a 400 Problem with no `errors` list.

Fix:
Overrode `handleTypeMismatch`, `handleMissingServletRequestParameter` and
`handleServletRequestBindingException`. They now return the same validation problem as the other
validation paths, with `errors: [{field, message}]`:
- the field is the parameter name or the header name;
- the message is generic: "has an invalid value" or "is required". The raw value and conversion details
  are not echoed.

Other `ServletRequestBindingException` subclasses still go to the default handler, so
`MissingPathVariableException` keeps its 500. The test API gained a `/client` endpoint with a required
`X-Client-Name` header. New or stronger tests:
- `missing required parameter gives 400 problem with a field error`;
- `missing required header gives 400 problem with a field error`;
- `wrongly typed parameter gives 400 with a field error that does not echo the value`;
- `non-numeric limit gives 400`, which now asserts `errors[0].field == "limit"`.

Validation:
- `cd server && ./gradlew check` - Pass (ApiConventionsWebTests$Errors: 20 tests, 0 failures)

### CR-2 - Gradle up-to-date checks can skip the contract check and the `UPDATE_OPENAPI=1` regeneration

Files changed:
- `server/build.gradle.kts`

Problem:
`tasks.test` did not declare `../api/openapi.yaml` or the `UPDATE_OPENAPI` environment variable as
inputs. A changed contract file, or a requested regeneration, could therefore leave `test` UP-TO-DATE, and
`OpenApiContractTest` would not run.

Fix:
`tasks.test` now declares:
- `inputs.files("../api/openapi.yaml")`, with property name `openApiContract` and path sensitivity NONE.
  `files()` accepts a missing file, so the input is optional.
- `inputs.property("updateOpenApi", providers.environmentVariable("UPDATE_OPENAPI").orElse(""))`.

The `updateOpenApi` task is unchanged.

Validation:
- `./gradlew test` run twice with no changes - the second run was `:test UP-TO-DATE` (still cached when
  nothing changed).
- Appended a line to `api/openapi.yaml`, then ran `./gradlew test` - `:test` re-ran and
  `OpenApiContractTest` FAILED as expected. Restored the file byte for byte, re-ran `./gradlew test` -
  Pass. `git status` shows `api/openapi.yaml` unmodified.
- `./gradlew updateOpenApi` - Pass, and `api/openapi.yaml` is unchanged.
- I could not test the `UPDATE_OPENAPI` property in this sandbox, because commands with an
  environment-variable prefix were not permitted. It uses Gradle's standard provider-based input
  property.

### CR-3 - Only `ObjectOptimisticLockingFailureException` is mapped to 409

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/core/web/ProblemDetailsAdvice.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/web/ConventionsTestApi.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/core/web/ApiConventionsWebTests.kt`

Problem:
Other `OptimisticLockingFailureException` subclasses, for example
`JpaOptimisticLockingFailureException`, became a 500.

Fix:
The handler now catches the parent class, `org.springframework.dao.OptimisticLockingFailureException`.
New test: `any optimistic locking failure gives 409 without internals`. It throws a plain
`OptimisticLockingFailureException` and asserts a 409 whose body does not contain the exception message.
The existing `ObjectOptimisticLockingFailureException` test still passes.

Validation:
- `cd server && ./gradlew check` - Pass

### CR-5 - A space was lost in an unrelated build comment

Files changed:
- `server/build.gradle.kts`

Problem:
`// Coverage:./gradlew ...` had lost a space.

Fix:
Restored `// Coverage: ./gradlew ...`.

Validation:
- `git diff server/build.gradle.kts` - the comment matches the original.

## Not fixed

### CR-4 - No test for passing security exceptions through the catch-all handler

Status:
Invalid (already resolved before this fix pass)

Reason:
The test-writer's commit 9c06347 already covers this:
- `access denied stays 403 and is not turned into a 500` (`/forbidden` throws `AccessDeniedException`);
- `authentication failure raised in a controller stays 401 and is not turned into a 500`
  (`BadCredentialsException`).

Both pass in the run above. No code change was needed.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat check` - the first combined run failed `ktlintMainSourceSetCheck`
  on a one-line formatting issue, because the check ran alongside the format task. ktlintFormat fixed
  the line, and a re-run of `./gradlew check` - Pass.

Full test suite:
- `cd server && ./gradlew check` (ktlint + all server tests) - Pass. 69 tests, 0 failures, 0 skipped. This
  includes OpenApiContractTest 1, ApiConventionsWebTests Errors 20 / ETagsAndIfMatch 7 / Pagination 5,
  CursorRequestTests 8, ETagsTests 5 and ApiExceptionsTests 2.

Lint/type-check/build:
- ktlint (main, test, scripts) and Kotlin compile through `./gradlew check` - Pass.
- `cd server && ./gradlew updateOpenApi` - Pass, and `api/openapi.yaml` is unchanged, so it did not need
  to be regenerated.

`.taskcheck`:
- Not run: the sandbox did not permit running `sh scripts/check.sh` in this session. The parent worker
  must run it.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. The server `check` passes; `.taskcheck` is still to
be run by the parent worker.
