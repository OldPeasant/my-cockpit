# Code Review

Date: 2026-10-20

Task: p0-api-conventions - API conventions: errors, ETags, pagination, OpenAPI contract file

Scope: `git diff 85d39d7..fad7501 -- . ':!tasks' ':!reviews'` (committed task code only; fad7501 and
the current HEAD d2b291e have identical code, they differ only in `tasks/`)

Requirements:
- tasks/p0-api-conventions.md acceptance criteria and recorded assumptions (`python tasks.py assumptions`)
- SPEC.md §4.2
- docs/ARCHITECTURE.md ("API between clients and server", "Testing")
- AGENTS.md project notes (generated files, conventions, logging rules)
- docs/QUALITY.md (report contract)

Git range:
- Branch: main
- HEAD: d2b291ed2f9aa65c6e30d1155c66e9f4f7fefbf7
- Base/start: 85d39d7b152188d6934fb0e50bbe050dc725009d
- Commits reviewed: 1 (fad7501, rebased as d2b291e)

Summary: 0 P0, 0 P1, 2 P2, 3 P3

## Commands run

- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.web.*'` -> Gradle ended with
  `java.io.EOFException` (KryoException: Buffer underflow) while reading test results, apparently
  because a second Gradle build was running at the same time (the test-writer was changing and running
  the tests in the working tree). The JUnit XML reports from this run (written 10:54:02) show 0 failures
  and 0 errors: ETagsTests 5, CursorRequestTests 8, ApiConventionsWebTests Errors 15 / ETagsAndIfMatch 7 /
  Pagination 5, OpenApiContractTest 1. These runs included the test-writer's uncommitted test changes.
  The production code was unchanged. I could not run the committed tests on their own: exporting the
  tree to a scratch directory was not permitted in this environment.
- Read-only inspection: `git diff`, `git log`, `git status`, and reading the changed files and tests.

## Acceptance criteria check

1. Problem Details: met. `ProblemDetailsAdvice` extends `ResponseEntityExceptionHandler` and maps
   400/404/409/412/428/500. Tests show that the 500 path does not leak the message or stack trace, and
   that malformed JSON does not leak parser internals. Partial gap: see CR-1.
2. ETag/If-Match: met. `ETags.ok` and `ETags.checkIfMatch` work. A test controller covers 428 and 412
   for PATCH and DELETE, plus the happy path.
3. Cursor pagination: met. `CursorPage`, `CursorRequest.of` (default 50, 1..200, else 400) and
   `page()` with limit+1 have unit and web tests.
4. OpenAPI contract: met. `OpenApiContractTest` fetches `/v3/api-docs.yaml`, writes the file when
   `UPDATE_OPENAPI=1`, and otherwise compares. The file is committed, and the README explains how to
   regenerate it and that conflicts are resolved by regenerating. Gradle caveat: see CR-2.
5. Test command passes: the reports from the run above show it passes (see "Commands run" for the
   caveat).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Type-mismatch and missing-parameter 400s have no `errors` field-error list**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/web/ProblemDetailsAdvice.kt:62`
    - Category: Correctness
    - Problem: Only `MethodArgumentNotValidException`, `HandlerMethodValidationException`,
      `ConstraintViolationException` and `InvalidRequestException` produce `errors: [{field, message}]`.
      Some request-validation failures fall through to the default `ResponseEntityExceptionHandler`
      handling, which returns a plain 400 Problem with no `errors`:
      - `MethodArgumentTypeMismatchException` / `TypeMismatchException`, for example `?limit=abc` on
        every paginated endpoint;
      - `MissingServletRequestParameterException`;
      - `MissingRequestHeaderException`.
      The test `non-numeric limit gives 400` only checks the status and content type.
    - Why it matters: The criterion says "validation → 400 with field errors". `api/README.md` and the
      `ProblemDetail` schema promise `errors` on 400 validation errors. Clients (p0-web-api-client,
      and-api-client) map field errors from that list, so a wrong-type or missing parameter would show
      no field.
    - Suggested fix: Override `handleTypeMismatch` and `handleMissingServletRequestParameter` (and the
      missing-header case, if wanted) to call `validationProblem` with the parameter name and a generic
      message such as "must be a number" / "is required". Do not echo the raw value. Add an assertion
      on `$.errors[0].field` to the `limit=abc` test.

- [ ] **CR-2 | Gradle up-to-date checks can skip the contract check and the `UPDATE_OPENAPI=1` regeneration**
    - File: `server/build.gradle.kts:90` (with `api/README.md:13`)
    - Category: Integration
    - Problem: `../api/openapi.yaml` is not declared as an input of `tasks.test`. Gradle does not track
      the client's environment (`UPDATE_OPENAPI`) as a Test input either. So:
      - after `api/openapi.yaml` changes without a server code change (a merge that takes one side of
        a conflict, a hand edit, a checkout), `./gradlew test` / `check` can be UP-TO-DATE and
        `OpenApiContractTest` does not run, so the drift goes unnoticed locally and in `.taskcheck`;
      - `UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`, the primary command in the
        README and in the test's failure message, is a silent no-op if the same filtered run already
        passed and nothing changed since.
      Only the separate `updateOpenApi` task avoids this, through `outputs.upToDateWhen { false }`.
    - Why it matters: The criterion and the README's conflict procedure ("take either side, then
      regenerate") depend on this test running whenever the committed file may differ.
    - Suggested fix: In `tasks.test` declare `inputs.file("../api/openapi.yaml")` (or
      `rootProject.file(...)`, with an optional/normalised path sensitivity), and
      `inputs.property("updateOpenApi", providers.environmentVariable("UPDATE_OPENAPI").orElse(""))`.
      Alternatively, make the README and the failure message recommend `./gradlew updateOpenApi` first.

## P3 Low

- [ ] **CR-3 | Only `ObjectOptimisticLockingFailureException` is mapped to 409**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/web/ProblemDetailsAdvice.kt:39`
    - Category: Correctness
    - Problem: The handler catches the Hibernate-specific subclass. A
      `JpaOptimisticLockingFailureException` is the other `OptimisticLockingFailureException` subclass
      that Spring produces when a JPA `OptimisticLockException` cannot be translated by the dialect.
      It, and any other subclass, would become a generic 500 instead of the 409 the assumption
      describes.
    - Why it matters: A concurrent-update conflict would look like a server error to clients.
    - Suggested fix: Handle `org.springframework.dao.OptimisticLockingFailureException`, the common
      parent.

- [ ] **CR-4 | No test for passing security exceptions through the catch-all handler**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/web/ProblemDetailsAdvice.kt:56`
    - Category: Tests
    - Problem: `handleUnexpected` rethrows `AccessDeniedException` / `AuthenticationException` so that
      Spring Security returns 401/403. No test covers this. If a future refactor dropped the rethrow,
      every authorization failure raised inside a controller or service (method security) would become
      a 500 and be logged at ERROR.
    - Why it matters: This is a security behaviour that is easy to regress, and the auth tasks will
      depend on it.
    - Suggested fix: Add a test-controller endpoint that throws `AccessDeniedException`, and assert
      403 (not 500).

- [ ] **CR-5 | A space was lost in an unrelated build comment**
    - File: `server/build.gradle.kts:121`
    - Category: Quality
    - Problem: `// Coverage: ./gradlew ...` became `// Coverage:./gradlew ...`, an incidental edit outside
      the intended change.
    - Why it matters: Small noise in a shared build file that many tasks touch, which makes merge
      conflicts more likely.
    - Suggested fix: Restore the original line.

## Notes (checked, no finding)

- Leakage: the 500 detail is generic, and parser and optimistic-lock internals are not exposed.
  Unknown routes give a 404 Problem.
- Spring MVC's automatic conditional handling only applies to GET/HEAD. It does not interfere with
  the PATCH flow that returns a new ETag (the test confirms 200 with `"2"`).
- The test-only controller stays out of the contract (`@TestConfiguration`, asserted in
  `OpenApiContractTest`).
- The contract is deterministic: keys are sorted (`springdoc.writer-with-order-by-keys`) and the
  server URL is fixed.
- 401/403 responses produced by the security filter chain are not Problem Details. That belongs to
  p0-auth-login, so it is not reported here.

## Review conclusion

Material issues found; fix before completing the task. No P0 or P1 findings: CR-1 and CR-2 are P2
and should be fixed or explicitly dispositioned before `done`.
