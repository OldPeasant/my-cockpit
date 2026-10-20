# Code Review

Date: 2026-10-20

Task: p0-api-conventions - API conventions: errors, ETags, pagination, OpenAPI contract file

Scope: `git diff 6298ae1..8ee57aa -- . ':!tasks' ':!reviews'` (all committed task code). This is the
second review, on the final code, after the fix commits 0d9be82 and 8ee57aa.

Requirements:
- tasks/p0-api-conventions.md acceptance criteria and recorded assumptions
- SPEC.md §4.2
- docs/ARCHITECTURE.md ("API between clients and server", "Testing")
- AGENTS.md project notes (generated files, conventions, logging rules)
- docs/QUALITY.md (report contract)
- Earlier review findings CR-1..CR-5 and reviews/p0-api-conventions/fixes.md

Git range:
- Branch: main
- HEAD: 8ee57aa98c90b32c31c0fe143c1f22001be53c6f
- Base/start: 6298ae144ff0d378546f1664eb4cd53ffce4f0c8
- Commits reviewed: 4 (07ff103, 111c0e8, 0d9be82, 8ee57aa)
- Uncommitted changes: none (`git status --short` is empty)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Commands run

- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.web.*'` on the clean HEAD 8ee57aa
  -> BUILD SUCCESSFUL. JUnit XML: ApiConventionsWebTests Errors 21 / ETagsAndIfMatch 7 / Pagination 5,
  CursorRequestTests 8, ETagsTests 5, ApiExceptionsTests 2, OpenApiContractTest 1: 49 tests,
  0 failures, 0 errors, 0 skipped.
- Read-only inspection: `git log`, `git diff 6298ae1..8ee57aa`, `git diff 111c0e8..8ee57aa`,
  `git status`, and reading the changed files in full (ProblemDetailsAdvice.kt, build.gradle.kts,
  api/README.md, OpenApiContractTest.kt, ConventionsTestApi.kt, ApiConventionsWebTests.kt).

## Earlier findings: status

- **CR-1 (P2) field errors for type-mismatch / missing-parameter 400s: Resolved.**
  `ProblemDetailsAdvice` now overrides `handleTypeMismatch`, `handleMissingServletRequestParameter`
  and `handleServletRequestBindingException` (only `MissingRequestHeaderException`; other binding
  errors go to `super`). These overrides return the shared `validationProblem` with the parameter or
  header name and a generic message, and they do not echo the value. Tests cover a missing parameter,
  a missing header, a wrongly typed parameter (the body does not contain the value or "Exception"),
  `limit=abc` (asserts `errors[0].field == "limit"`), and a missing cookie that still gives a plain
  400. `MissingPathVariableException` has its own handler, so it is unaffected (it stays 500).
- **CR-2 (P2) Gradle up-to-date checks skip the contract test: Resolved.** `tasks.test` declares
  `../api/openapi.yaml` as an input file (path sensitivity NONE, and `files()` accepts a missing
  file) and `UPDATE_OPENAPI` as a provider-based input property. Gradle propagates the client
  environment to the daemon, so the property sees the variable that the README command sets.
- **CR-3 (P3) only the Hibernate optimistic-lock subclass maps to 409: Resolved.** The handler now
  catches `org.springframework.dao.OptimisticLockingFailureException`. The new test throws the base
  class and asserts 409 with no internal message.
- **CR-4 (P3) no test for security-exception passthrough: Resolved.** It was already covered by
  111c0e8: `/forbidden` (AccessDeniedException) gives 403 and `BadCredentialsException` gives 401,
  neither of them 500.
- **CR-5 (P3) lost space in the build comment: Resolved.** The comment is `// Coverage: ./gradlew ...`
  again.

## Acceptance criteria check

1. Problem Details (400 with field errors, 404, 409, 412, no leakage): met. With CR-1 fixed, query
   parameters and headers that are missing or wrongly typed now carry `errors`. For the remaining gap
   (missing multipart part), see CR-6 (P3, not part of this task's endpoints).
2. ETag/If-Match helper, 428/412 on a test controller: met. This code did not change since the first
   review, and its 7 tests pass.
3. Cursor pagination DTO and limit bounds: met (8 unit tests and 5 web tests pass).
4. OpenAPI contract test, committed file, and README on regenerating and conflicts: met. The test now
   also runs when the contract file changes (CR-2).
5. `./gradlew test --tests 'ch.sonensei.cockpit.core.web.*'` passes: met. I saw it pass on the clean
   HEAD (see "Commands run"). Persistence-backed tests use `PostgresTestBase` (Testcontainers).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-6 | A missing multipart part gives a 400 without the `errors` list**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/web/ProblemDetailsAdvice.kt:92`
    - Category: Correctness
    - Problem: The CR-1 fix covers missing or wrongly typed query parameters and headers. It does not
      cover `MissingServletRequestPartException` (a required `@RequestPart` that is missing), which
      still uses the default `handleMissingServletRequestPart` and returns a 400 Problem with no
      `errors`. That is the same kind of "required request value missing" error. SPEC.md plans upload
      endpoints (Files, attachments, backup restore). No endpoint in this task uses multipart, so this
      is low priority.
    - Why it matters: Clients that map `errors` to fields would show nothing for a missing upload
      part. It is inconsistent with the convention documented in `api/README.md`.
    - Suggested fix: Override `handleMissingServletRequestPart` and return
      `validationProblem(listOf(FieldError(ex.requestPartName, "is required")), headers)`. Or leave it
      to the first files/upload task and record that decision.

## Notes (checked, no finding)

- The fix overrides return `validationProblem` directly instead of going through
  `handleExceptionInternal`. This matches the existing validation overrides, and the status and
  content type are asserted by `isProblem(400)`.
- The `UPDATE_OPENAPI=1` run rewrites a file that is also a declared input of `test`. As a result,
  the next plain `test` run executes once more instead of being UP-TO-DATE. That is harmless, and it
  is the conservative direction.
- The new test endpoints (`/client`, `/cookie`, `/optimistic-lock-generic`) live in the
  `@TestConfiguration` controller. `OpenApiContractTest` asserts that the base path does not appear in
  the contract, and the contract file is unchanged by the fixes.
- The tests use fictitious values only. No secrets or credentials are in the diff, and nothing new is
  logged.
- `build.gradle.kts` is outside the task's `touches`. The parent worker approved the edit, and it is
  minimal (fixes.md).

## Review conclusion

No material issues found. All five earlier findings are resolved. The only new finding, CR-6, is P3
and optional for this task.
