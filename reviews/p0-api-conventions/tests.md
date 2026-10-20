# Test Report

Date: 2026-10-20

Task: p0-api-conventions - API conventions: errors, ETags, pagination, OpenAPI contract file

Scope: branch `main`, base `85d39d7b152188d6934fb0e50bbe050dc725009d`, measured HEAD
`d2b291ed2f9aa65c6e30d1155c66e9f4f7fefbf7` (task commit "p0-api-conventions: Problem Details,
ETag/If-Match, cursor pagination, OpenAPI contract"; the parent named it `fad7501`, which does not
exist in this clone. It was rebased to `d2b291e` with the same content). Diff examined:
`git diff 85d39d7..HEAD -- . ':!tasks' ':!reviews'`. The working tree also has the
uncommitted test changes listed below (no production changes).

Requirements:
- `tasks/p0-api-conventions.md` acceptance criteria and recorded assumptions (limit 1..200 with
  default 50, out-of-range is 400; missing If-Match 428, stale 412, optimistic lock 409; strong
  comparison, `*` matches)
- `SPEC.md` §4.2 (via the task), `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- Parent instructions (test-only controllers stay inside `@TestConfiguration`, package
  `ch.sonensei.cockpit.core.web`)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/web/ApiConventionsWebTests.kt` (8 tests added)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/web/ConventionsTestApi.kt` (test-only endpoints
  `/range`, `/schedule`, `/forbidden`, `/bad-credentials` plus a `@Validated` test `Scheduler`
  service, all inside the existing `@TestConfiguration`)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/web/ApiExceptionsTests.kt` (new, 2 tests)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/web/CursorRequestTests.kt` (2 tests added)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/web/ETagsTests.kt` (1 test added, 1 strengthened)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/web/OpenApiContractTest.kt` (strengthened)

## Tests added or updated

Count: 15 (13 new, 2 strengthened)

New:
- `ApiConventionsWebTests.Errors`: `several invalid parameters give one 400 listing every field error sorted by field`,
  `validation in a service gives 400 with field errors instead of 500` (ConstraintViolationException path),
  `authentication failure raised in a controller stays 401 and is not turned into a 500`,
  `missing required parameter gives 400 problem`, `unsupported method gives 405 problem`,
  `unsupported content type gives 415 problem`, `access denied stays 403 and is not turned into a 500`,
  `unauthenticated request stays 401 and is not turned into a 500`
- `ApiExceptionsTests`: `each exception maps to its status with a client-safe default detail`,
  `only validation problems carry the errors extension`
- `CursorRequestTests`: `nextCursor points at the last returned item, not the extra fetched row`,
  `cursor is URL-safe even for non-ASCII keysets`
- `ETagsTests`: `a list of only stale or weak tags is 412` (includes `"12"` vs version 2)

Strengthened:
- `ETagsTests.current version, a list containing it, or star matches`: a weak tag next to the
  matching strong tag still matches
- `OpenApiContractTest`: the generated contract must contain the shared `ProblemDetail` and
  `FieldError` schemas

The existing tests already covered the core of each criterion well. The additions target the
uncovered handler paths (service-level validation, security pass-through), multi-error ordering,
Spring MVC error kinds and cursor/ETag edge cases.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Problem Details: validation 400 + field errors | `ApiConventionsWebTests.Errors`: body/parameter/in-code/service validation, several invalid parameters, missing parameter, malformed JSON, non-numeric limit; `ApiExceptionsTests` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.web.*'` | Pass |
| AC1 not found 404, conflict 409, precondition failed 412 | `unknown entity gives 404`, `unknown route gives 404 problem`, `conflict gives 409`, `optimistic locking failure gives 409 without entity internals`, `PATCH/DELETE with a stale If-Match gives 412`, `ApiExceptionsTests` | same | Pass |
| AC1 no stack traces or internal messages leak | `unexpected exception gives a generic 500 without message or stack trace`, `malformed JSON gives 400 without parser internals`, optimistic-lock test, `isProblem` helper (no `trace`/`exception` fields), 401/403 tests (exception message absent) | same | Pass |
| AC1 security failures not swallowed into 500 (advice rethrow branch) | `access denied stays 403 ...`, `unauthenticated request stays 401 ...`, `authentication failure raised in a controller stays 401 ...` | same | Pass |
| AC2 GET returns ETag from version | `GET returns the version as ETag`, `PATCH with the current If-Match updates and returns the new ETag`, `ETagsTests.ETag is the quoted version` | same | Pass |
| AC2 PATCH/DELETE without If-Match 428, stale 412 | `ETagsAndIfMatch` (7 tests on the test controller), `ETagsTests` (5) | same | Pass |
| AC3 CursorPage (`items`, `nextCursor`) and `?cursor=&limit=` parsing with bounds | `CursorRequestTests` (8), `ApiConventionsWebTests.Pagination` (5) | same | Pass |
| AC4 `OpenApiContractTest` fails when the file differs, file committed, shared schemas present, test controllers excluded | `OpenApiContractTest` | same | Pass |
| AC4 write mode (`UPDATE_OPENAPI=1`) writes `api/openapi.yaml` | `OpenApiContractTest` via `./gradlew updateOpenApi` (the Gradle task sets `UPDATE_OPENAPI=1`) | `cd server && ./gradlew updateOpenApi` | Pass: file rewritten (mtime 10:57:01), `git status -- api` clean, so generation is deterministic and matches the committed file |
| AC4 `api/README.md` explains regenerating and resolving conflicts by regeneration | manual read of `api/README.md` ("Regenerating", "Merge conflicts" sections) | n/a | Not testable automatically. Read and confirmed present |
| AC5 `./gradlew test --tests 'ch.sonensei.cockpit.core.web.*'` passes (Postgres via Testcontainers) | all 45 core.web tests (`PostgresTestBase`) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.web.*'` | Pass (45 tests, 0 failures, 0 skipped) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.web.*'`

Result:

Pass. 45 tests, 0 failures/errors/skipped (ApiConventionsWebTests: Errors 17, ETagsAndIfMatch 7,
Pagination 5; CursorRequestTests 8; ETagsTests 5; ApiExceptionsTests 2; OpenApiContractTest 1),
taken from `server/build/test-results/test/*.xml`.

## Full test execution

Command:

`cd server && ./gradlew check` (ktlint main/test/scripts + full server unit suite)

Result:

Pass. BUILD SUCCESSFUL, 66 tests, 0 failures/errors/skipped, ktlint clean on the new tests.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` (web + android + server) was denied by this sub-agent's command
permissions. The server part (`./gradlew check`) passed above. The parent worker must run
`.taskcheck` itself before `done`.

## Bugs found

None. Every new test passed against the unchanged production code.

Observation (not a defect, no test impact): the `server/build.gradle.kts` diff turned the comment
`// Coverage: ./gradlew test ...` into `// Coverage:./gradlew test ...` (lost a space).

## Coverage

Status: verified (fresh run, exit 0, on the final test snapshot; no production changes since).

Metric: whole-file coverage of task-changed production files (JaCoCo counters; includes hits from
all server tests).
Changed-line coverage: not measured separately. All five files are new in this task, so every
executable line is a changed line, and whole-file coverage equals changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: p0-api-conventions;
  base `85d39d7b152188d6934fb0e50bbe050dc725009d` (given by the parent); HEAD
  `d2b291ed2f9aa65c6e30d1155c66e9f4f7fefbf7` = the single task commit on top of the base
  (`git log 85d39d7..HEAD`), with production files from `git diff --stat 85d39d7..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): production = HEAD `d2b291e`
  (no uncommitted production changes). Uncommitted test files (sha256):
  ApiConventionsWebTests.kt `0af5448e825c...`, ApiExceptionsTests.kt `f634f7e6378f...`,
  ConventionsTestApi.kt `de0136d38a20...`, CursorRequestTests.kt `4bd939b27b20...`,
  ETagsTests.kt `f4b3dbd21601...`, OpenApiContractTest.kt `9fdd5eeac054...` (unchanged
  between the coverage run and this report).
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/web/{ApiExceptions,CursorPage,ETags,OpenApiConfig,ProblemDetailsAdvice}.kt`.
  Excluded: `server/src/main/resources/application.properties` (configuration: its springdoc
  properties are exercised indirectly by `OpenApiContractTest`, since the sorted keys and kept
  schemas show up in the contract), `server/build.gradle.kts` (build script: the `updateOpenApi`
  task was run directly), `api/openapi.yaml`, `api/README.md`, `docs/ARCHITECTURE.md` (generated
  file/docs). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco`
  plugin, version as configured in `server/build.gradle.kts`); `./gradlew cleanTest test jacocoTestReport`
  in `server/`; full server `test` task (all 66 tests).
- Run start/completion time and exit status: 2026-10-20 ~10:56:03 to 10:56:17 +0200 (14 s), exit 0
  (BUILD SUCCESSFUL). An earlier `./gradlew test --rerun jacocoTestReport` failed with a Gradle
  infrastructure error (`NoSuchFileException .../in-progress-results-generic.bin`, likely a
  concurrent Gradle build in the same directory: a later run reported "1 busy" daemon). Its
  output was discarded and is not used here.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  sha256 `daad42683be5be1ea99115ee6ae970e9b33586503c27defe00f2db89e3fd2e6d`;
  `server/build/jacoco/test.exec` sha256 `9e209e29882aa9a02e9d45ead5a5d8f3ba74a10b5c2adebde45af596fdf1a4c3`.
- Evidence outputs are fresh and inputs stayed unchanged: `cleanTest` forced the test task to
  rerun, and `jacocoTestReport` executed (not up to date). No test or source file was edited
  after the run.
- Final run after latest test additions and production fixes: yes (no production fixes were needed).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.
JaCoCo has no separate statement counter; "Statements" shows INSTRUCTION counters.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/web/ProblemDetailsAdvice.kt` | 226/251 (90.0%) | 9/14 (64.3%) | 8/8 (100%) | 28/28 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/web/ETags.kt` | 88/88 (100%) | 12/12 (100%) | 3/3 (100%) | 6/6 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/web/ApiExceptions.kt` | 119/119 (100%) | N/A (0 branches) | 17/17 (100%) | 23/23 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/web/OpenApiConfig.kt` | 140/140 (100%) | N/A (0 branches) | 4/4 (100%) | 27/27 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/web/CursorPage.kt` | 167/167 (100%) | 18/18 (100%) | 10/10 (100%) | 27/27 (100%) |
| **Aggregate (summed counters, all 5 scoped files measured, none missing)** | 740/765 (96.7%) | 39/44 (88.6%) | 42/42 (100%) | 111/111 (100%) |

Before the added tests (same command, first fresh run): ProblemDetailsAdvice 179/251 instr,
8/14 branches, 22/28 lines, 7/8 methods (`handleConstraintViolation` never ran). ApiExceptions
missed the `NotFoundException` default message. Package aggregate 684/765 instr, 38/44 branches,
104/111 lines.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ProblemDetailsAdvice.kt:45-51` (was uncovered) | ConstraintViolationException from `@Validated` services must be a 400 with field errors, not a 500 | Covered by `validation in a service gives 400 with field errors instead of 500` |
| `ProblemDetailsAdvice.kt:56` AuthenticationException branch (was uncovered) | auth failures must stay 401, not become a generic 500 | Covered by `authentication failure raised in a controller stays 401 ...` |
| `ProblemDetailsAdvice.kt:70` (2 branches): `bindingResult.globalErrors` entries | class-level (cross-field) Bean Validation constraint on a request body | Left uncovered: no class-level constraint exists in the codebase yet. Testing it needs a custom constraint annotation and validator in tests. Low risk (same `validationProblem` path as field errors). Worth a test when the first cross-field constraint is added |
| `ProblemDetailsAdvice.kt:69,83` (1 branch each): `defaultMessage ?: "is invalid"` | fallback when a constraint has no message | Left uncovered: defensive. Standard Bean Validation constraints always resolve a message |
| `ProblemDetailsAdvice.kt:82` (1 branch): `parameterName ?: "parameter" + parameterIndex` | fallback when the parameter name cannot be discovered | Left uncovered: defensive. Kotlin parameter-name discovery works (the tests show `count`, `from`, `to`) |
| `ApiExceptions.kt:16-18` (was uncovered) | `NotFoundException` default detail | Covered by `ApiExceptionsTests` |

## Unable to test

- `.taskcheck` (`sh scripts/check.sh`): not run, because this sub-agent was denied permission. The
  parent must run it. Server lint and tests pass via `./gradlew check`.
- The literal `UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'` invocation: shell
  env-var prefixes are blocked in this environment. Ran the equivalent `./gradlew updateOpenApi`
  (it sets `UPDATE_OPENAPI=1` for the same test filter) instead. No follow-up needed.
- `api/README.md` content: documentation, checked by reading only.
- Class-level (global) validation errors: see the coverage gaps above. Follow-up is optional, when
  the first cross-field constraint appears.

## Summary

Tests and task-scoped coverage are sufficient. No production defects found. 13 tests were added
and 2 strengthened (test files only). The focused core.web suite (45 tests) and `./gradlew check`
(66 tests + ktlint) pass. `.taskcheck` was not run by this helper and must be run by the parent.
