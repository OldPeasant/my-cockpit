# Test Report

Date: 2026-10-20

Task: p0-api-conventions - API conventions: errors, ETags, pagination, OpenAPI contract file

Scope: second pass on the final code after fixes. Branch `main`, immutable base
`6298ae144ff0d378546f1664eb4cd53ffce4f0c8` (merge-base with origin/main, given by the parent),
measured HEAD `0d9be82de448badaf17b5e19b6451aa49f9d0c3b`. Task-owned commits
(`git log 6298ae1..HEAD`): `07ff103` (implementation), `111c0e8` (tests and first reports),
`0d9be82` (review fixes CR-1, CR-2, CR-3, CR-5). Diff examined:
`git diff 6298ae1..HEAD -- . ':!tasks' ':!reviews'`. The `fixes.md` report names the older
pre-rebase hashes `85d39d7`/`9c06347`. The content is the same, rebased onto `6298ae1`. The working
tree also holds the uncommitted test changes listed below. There are no production changes.

Requirements:
- `tasks/p0-api-conventions.md` acceptance criteria and its recorded assumptions (limit 1..200,
  default 50, out of range is 400; missing If-Match 428, stale 412, any JPA/optimistic-lock failure
  409; strong comparison, `*` matches)
- `reviews/p0-api-conventions/review.md` findings CR-1..CR-5 and `fixes.md` (fixed behavior to verify)
- `SPEC.md` §4.2 (via the task), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- Parent instructions: add tests only for real gaps, test files only, and test-only controllers
  stay inside `@TestConfiguration`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/web/ApiConventionsWebTests.kt` (1 test added,
  1 strengthened)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/web/ConventionsTestApi.kt` (test-only endpoint
  `/cookie` with a required `@CookieValue("fictitious-hint")`, inside the existing
  `@TestConfiguration` controller)

## Tests added or updated

Count: 2 (1 new, 1 strengthened)

The fix commit already tested most of the fixed behavior:
- CR-1:
  - `missing required parameter gives 400 problem with a field error`;
  - `missing required header gives 400 problem with a field error`;
  - `wrongly typed parameter gives 400 with a field error that does not echo the value`;
  - `non-numeric limit gives 400`, which now checks `errors[0].field == "limit"`.
- CR-3: `any optimistic locking failure gives 409 without internals`.

Real gaps found and closed:
- New: `ApiConventionsWebTests.Errors.other request binding errors keep the default 400 problem`.
  The fix added an `else` branch in `handleServletRequestBindingException`: a binding exception
  that is not a missing header goes to Spring's default handler. No test covered that branch. A
  regression there (for example, mapping it wrongly or throwing) would turn such requests into a
  500 or a non-Problem response. The test sends a request without a required cookie
  (`MissingRequestCookieException`). It asserts a 400 Problem Details response with no
  trace/exception fields and no `errors` list.
- Strengthened: `any optimistic locking failure gives 409 without internals` now also asserts
  `detail == "The resource was changed concurrently"`. The 409 must use the client-safe conflict
  detail, not just any 409 body that happens to leave out the exception message.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Problem Details: validation 400 with field errors (body, params, service, code validation; plus CR-1: missing param, missing header, wrong type, non-numeric limit) | `ApiConventionsWebTests.Errors`: `body validation gives 400 with field errors`, `parameter validation gives 400 with field errors`, `several invalid parameters give one 400 ...`, `validation in a service gives 400 ...`, `validation done in code gives 400 ...`, `missing required parameter gives 400 problem with a field error`, `missing required header gives 400 problem with a field error`, `wrongly typed parameter gives 400 with a field error that does not echo the value`, `other request binding errors keep the default 400 problem`, `malformed JSON gives 400 without parser internals`; `Pagination.non-numeric limit gives 400` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.web.*'` | Pass |
| AC1 404 / 409 (incl. CR-3: any `OptimisticLockingFailureException`) / 412; no stack traces or internal messages | `unknown entity gives 404`, `unknown route gives 404 problem`, `conflict gives 409`, `optimistic locking failure gives 409 without entity internals`, `any optimistic locking failure gives 409 without internals`, `unexpected exception gives a generic 500 without message or stack trace`, `access denied stays 403 ...`, `authentication failure ... stays 401 ...`, `unauthenticated request stays 401 ...`, `unsupported method gives 405 problem`, `unsupported content type gives 415 problem`; `ApiExceptionsTests` (2) | same | Pass |
| AC2 ETag/If-Match: GET ETag from version; PATCH/DELETE without If-Match 428, stale 412 | `ApiConventionsWebTests.ETagsAndIfMatch` (7), `ETagsTests` (5) | same | Pass |
| AC3 Cursor pagination DTO + `?cursor=&limit=` with limit bounds | `ApiConventionsWebTests.Pagination` (5), `CursorRequestTests` (8) | same | Pass |
| AC4 `OpenApiContractTest` compares `/v3/api-docs.yaml` with `api/openapi.yaml`; `UPDATE_OPENAPI=1` rewrites it | `OpenApiContractTest` (compare mode) | same | Pass. The literal `UPDATE_OPENAPI=1 ...` and `env UPDATE_OPENAPI=0 ...` invocations are Not run: env-var prefixes are blocked in this sandbox. `api/README.md` checked by reading only (documentation). |
| CR-2 `api/openapi.yaml` and `UPDATE_OPENAPI` are `test` inputs | Build script, not unit-testable. Observed `./gradlew test --tests '*OpenApiContractTest'` re-ran after test sources changed, then reported `:test UP-TO-DATE` with no changes. Proving that the env var or contract file invalidates the cache needs an env-var prefix or an edit of a non-test file, so it was not repeated here (the fixer did the file part) | `cd server && ./gradlew test --tests '*OpenApiContractTest' --console=plain` (twice) | Partial: caching confirmed; invalidation Not run (sandbox) |
| AC5 `./gradlew test --tests 'ch.sonensei.cockpit.core.web.*'` passes (Postgres via Testcontainers) | all 49 core.web tests | same | Pass (49 tests, 0 failures, 0 skipped) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.web.*'`

Result:

Pass. BUILD SUCCESSFUL, 49 tests, 0 failures, 0 errors, 0 skipped:
- ApiConventionsWebTests$Errors 21;
- ApiConventionsWebTests$ETagsAndIfMatch 7;
- ApiConventionsWebTests$Pagination 5;
- CursorRequestTests 8;
- ETagsTests 5;
- ApiExceptionsTests 2;
- OpenApiContractTest 1.

## Full test execution

Command:

`cd server && ./gradlew check`, then `cd server && ./gradlew check --rerun-tasks --console=plain`
(this forces ktlint on main, test and scripts, plus compile and all tests)

Result:

Pass. BUILD SUCCESSFUL, 70 server tests, 0 failures, 0 skipped (69 before this pass, plus 1 new).
ktlint passed for the main source set, the test source set and the Kotlin scripts.

## .taskcheck

Result:

Not run. This sub-agent was denied permission to run `sh scripts/check.sh`. The parent worker must
run it. The server part (lint and unit tests) passes via `./gradlew check`. Web and Android were not
changed by this task.

## Bugs found

None. The fixed behavior (CR-1, CR-3) works as described in `fixes.md`. The new test passed on the
first run against HEAD `0d9be82`.

## Coverage

Status: verified (fresh run, exit 0, on the final test snapshot; production code = HEAD `0d9be82`,
which includes all fixes).

Metric: whole-file coverage of task-changed production files (JaCoCo counters; includes hits from
all server tests).
Changed-line coverage: not measured separately. All five Kotlin files are new in this task
(`git diff --name-status 6298ae1..HEAD -- server/src/main` shows them as added), so every
executable line is a changed line, and whole-file coverage equals changed-line coverage.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source:
  - Task `p0-api-conventions`.
  - Base `6298ae144ff0d378546f1664eb4cd53ffce4f0c8`, from the parent.
  - HEAD `0d9be82de448badaf17b5e19b6451aa49f9d0c3b`.
  - Task commits: `07ff103`, `111c0e8`, `0d9be82` (`git log 6298ae1..HEAD`; all are p0-api-conventions commits).
  - File scope: `git diff --stat 6298ae1..HEAD -- server/src/main`.
- Source/test snapshot identifier (sha256, taken before the run and identical after it):
  - Production at HEAD: ApiExceptions.kt `2982005457f9...`, CursorPage.kt `3b3a009177e6...`,
    ETags.kt `98174feb0001...`, OpenApiConfig.kt `440bfa6c28c6...`,
    ProblemDetailsAdvice.kt `6bc9490be1b1...`, `api/openapi.yaml` `c8e9834abe73...`,
    `server/build.gradle.kts` `c9c3c3bc3f46...`.
  - Uncommitted tests: ApiConventionsWebTests.kt `80b1e84ca81b...`, ConventionsTestApi.kt `662faa7b72bd...`.
  - Unchanged committed tests: ApiExceptionsTests.kt `f634f7e6378f...`,
    CursorRequestTests.kt `4bd939b27b20...`, ETagsTests.kt `f4b3dbd21601...`,
    OpenApiContractTest.kt `9fdd5eeac054...`.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/web/{ApiExceptions,CursorPage,ETags,OpenApiConfig,ProblemDetailsAdvice}.kt`.
  Excluded:
  - `server/src/main/resources/application.properties` (configuration; its springdoc properties are
    exercised indirectly by `OpenApiContractTest`);
  - `server/build.gradle.kts` (build script; see CR-2 above);
  - `api/openapi.yaml` (generated);
  - `api/README.md` and `docs/ARCHITECTURE.md` (docs).

  No renamed or deleted files. The JaCoCo XML also lists an empty `Comparisons.kt` sourcefile under
  `core/web`, with no counters. It is a Kotlin inline-function source mapping, not a task file.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin,
  default tool version of Gradle 9.8.1, no `toolVersion` override); `./gradlew cleanTest test jacocoTestReport --console=plain`
  in `server/`; full server `test` task (70 tests).
- Run start/completion time and exit status: 2026-10-20 about 11:04:50 to 11:05:03 +0200, BUILD
  SUCCESSFUL (exit 0). `:test` and `:jacocoTestReport` both executed (neither was up to date).
- Generated report paths and report hashes/run identifier:
  - `server/build/reports/jacoco/test/jacocoTestReport.xml` (mtime 11:05:03.015): sha256
    `62c77f51d6b91c6ae8e6964b49918480aeebd01c285edfed02014c49106d8315`;
  - `server/build/jacoco/test.exec` (mtime 11:05:02.391): sha256
    `3e3571b3c62533a84c703155b58001263100d42b7acd559d75a3234e5a868257`.
- Evidence outputs are fresh and inputs stayed unchanged: `cleanTest` forced the test run. Both
  artifacts were rewritten during this run (their mtimes fall inside the run window, and their
  hashes differ from the first-pass report). The source and test hashes were re-checked after the
  run and were unchanged.
- Final run after latest test additions and production fixes: yes. The run includes the fix commit
  `0d9be82` and both test changes from this pass.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.
JaCoCo has no separate statement counter; "Statements" shows INSTRUCTION counters.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/web/ProblemDetailsAdvice.kt` | 287/320 (89.7%) | 14/24 (58.3%) | 11/11 (100%) | 35/35 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/web/ETags.kt` | 88/88 (100%) | 12/12 (100%) | 3/3 (100%) | 6/6 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/web/ApiExceptions.kt` | 119/119 (100%) | N/A (0 branches) | 17/17 (100%) | 23/23 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/web/OpenApiConfig.kt` | 140/140 (100%) | N/A (0 branches) | 4/4 (100%) | 27/27 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/web/CursorPage.kt` | 167/167 (100%) | 18/18 (100%) | 10/10 (100%) | 27/27 (100%) |
| **Aggregate (summed counters, all 5 scoped files measured, none missing or excluded)** | 801/834 (96.0%) | 44/54 (81.5%) | 45/45 (100%) | 118/118 (100%) |

Compared with the first pass (before the fixes): ProblemDetailsAdvice grew from 251 to 320
instructions and from 14 to 24 branches, because of the three new overrides. All new methods and
lines are covered, including both sides of the missing-header and other-binding-exception branch.
The remaining 10 missed branches are null and type fallbacks.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ProblemDetailsAdvice.kt:116-121` (`handleServletRequestBindingException` else branch) | non-header binding errors must stay a 400 Problem, not a 500 | Covered by the new `other request binding errors keep the default 400 problem` |
| `ProblemDetailsAdvice.kt:100` (5 of 8 branches missed) | `handleTypeMismatch` field name: `as? MethodArgumentTypeMismatchException` false, `name`/`propertyName` null, `"parameter"` fallback | Left uncovered. These are defensive fallbacks: in Spring MVC every controller-argument conversion failure is a `MethodArgumentTypeMismatchException` with a non-null name (the tests show `count` and `limit`). `ConversionNotSupportedException` has its own handler, and data-binder mismatches arrive as `MethodArgumentNotValidException`. Low risk: the fallback still yields a 400 with a generic field |
| `ProblemDetailsAdvice.kt:73-74` (3 branches): `defaultMessage ?: "is invalid"` and `bindingResult.globalErrors` entries | class-level (cross-field) Bean Validation constraint on a request body | Left uncovered (unchanged from the first pass). No class-level constraint exists in the codebase yet, and testing one needs a custom constraint and validator in tests. Same `validationProblem` path as field errors. Worth a test when the first cross-field constraint is added |
| `ProblemDetailsAdvice.kt:86-87` (1 branch each): `parameterName ?: "parameter" + index`, `defaultMessage ?: "is invalid"` | fallbacks when the parameter name or the constraint message is missing | Left uncovered: defensive. Kotlin parameter-name discovery works, and standard constraints always resolve a message |

## Unable to test

- `.taskcheck` (`sh scripts/check.sh`): this helper was denied permission. The parent must run it.
- `UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`, and proof that the
  `UPDATE_OPENAPI` input invalidates the `test` cache (CR-2): env-var prefixes (including `env ...`)
  are blocked in this sandbox. The fixer already showed that a changed `api/openapi.yaml` re-runs
  the test. The regeneration path itself was exercised in the first pass via `./gradlew updateOpenApi`.
  Follow-up: optional. The parent can run `UPDATE_OPENAPI=0 ./gradlew test --tests '*OpenApiContractTest'`
  after a cached run and confirm that `:test` is not UP-TO-DATE.
- Class-level (global) body validation errors: see the coverage gaps. Optional follow-up when the
  first cross-field constraint appears.
- `api/README.md` content: documentation, checked by reading only.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The fixed behavior
(CR-1 field errors for missing parameter, missing header and type mismatch; CR-3 409 for any
optimistic-lock failure) is tested. This pass added 1 test for the untested
`handleServletRequestBindingException` delegation branch and strengthened the generic 409 test to
assert the client-safe detail (test files only, and the test endpoint stays inside
`@TestConfiguration`). The focused core.web suite passes (49 tests), and so does `./gradlew check`
(70 tests plus ktlint). `.taskcheck` was not run by this helper and must be run by the parent.
