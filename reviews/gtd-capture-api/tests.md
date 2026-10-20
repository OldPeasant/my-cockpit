# Test Report

Date: 2026-10-20

Task: gtd-capture-api - Quick capture endpoint and syntax parser

Scope: task commits b977d6d64b6475ca55082dd46a72b0681b01556c..f3ac29dbf8a3f57f55b8aa492a351800736062b9 (base and range supplied by the parent worker; branch `main`, working tree clean, no uncommitted inputs).

Requirements:
- `tasks/gtd-capture-api.md` acceptance criteria 1-5 and recorded assumptions (capture syntax, `^waiting`, project matching, title/notes split, server-clock relative dates)
- SPEC.md §4.5, §5.4 (via task goal); `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md`
- Prior reports: `reviews/gtd-capture-api/review.md`, `reviews/gtd-capture-api/fixes.md`

## Test files changed

- None in this run. The existing task-owned suites were checked against the criteria and changed behavior:
  - `server/src/test/kotlin/ch/sonensei/cockpit/gtd/CaptureParserTests.kt` (20 tests)
  - `server/src/test/kotlin/ch/sonensei/cockpit/gtd/CaptureParserEdgeCaseTests.kt` (28 tests)
  - `server/src/test/kotlin/ch/sonensei/cockpit/gtd/CaptureWebTests.kt` (17 tests)
  - `server/src/test/kotlin/ch/sonensei/cockpit/gtd/CaptureEdgeCaseWebTests.kt` (25 tests)
  - `server/src/test/kotlin/ch/sonensei/cockpit/gtd/CaptureSideEffectWebTests.kt` (3 tests)

## Tests added or updated

Count: 0

No real gap was found: every acceptance criterion, every recorded assumption and every reachable branch of the
changed code is already exercised (see Coverage). Adding more tests would only chase the remaining defensive branches.

## Requirement coverage

Command A: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest' jacocoTestReport` (318 tests, 0 failures, 0 errors, 0 skipped; Postgres via Testcontainers).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 `POST /api/v1/capture {id?, text, notes?, fileIds?, parse?}` creates an INBOX task | `capture creates an inbox task with ETag and Location`, `parse false keeps the text as it is`, `further lines go in front of the notes`, `body lines are stored verbatim, with and without parsing`, `blank notes are ignored`, `invalid captures are rejected`, `capture rejects malformed bodies`, `capture needs a login` | A | Pass |
| AC1 repeated client `id` returns 200 with the existing task, no duplicate | `a repeated client id returns 200 with the existing task and creates no duplicate`, `a repeated id returns the original task whatever the repeat contains`, `a repeat whose text alone would be invalid still returns the existing task`, `a capture with the id of a task created through the tasks api returns that task`, `captures without an id are never deduplicated`, `a concurrent capture with the same id returns 200 with the winner's task and links no files again`, `a capture with the id of a trashed task is a conflict` | A | Pass |
| AC2 parser is a pure function, unit-tested: `#project` (existing, case-insensitive, else text) | `CaptureParserTests`: `a project is found by title...`, `an unknown project is kept as text`, `the first known project wins...`; `CaptureParserEdgeCaseTests`: preference order, spacing, unknown-before-known; web: `completed and deleted projects are not matched, active ones are preferred`, `projects on hold are preferred over someday ones...`, `a someday project is matched when it is the only one`, `unknown project and context tokens create no project or context, in capture and preview` | A | Pass |
| AC2 `@context` | `contexts are found with or without their at sign and accumulate`, `an unknown context is kept as text`, `context names are trimmed before matching`, `the same context named twice in different spellings is listed once`, `contexts captured together are all stored` | A | Pass |
| AC2 `!today` / `!tomorrow` / `!YYYY-MM-DD` due | `due today, tomorrow and an ISO date`, `invalid dates and unknown keywords stay as text`, `the first due date wins`, `tomorrow crosses the year boundary`, `leap days are valid only in leap years`, `dates in other formats stay as text`, `an invalid date before a valid one does not block the valid one`, `preview uses today in the user's time zone` | A | Pass |
| AC2 `^waiting` | `waiting puts the task on the waiting list, a name says for whom`, `the waiting keyword after a name stays as text`, `waiting without a name sets waiting since but no waiting for`, `a waiting for name longer than a title is rejected, not a server error` | A | Pass |
| AC2 `POST /api/v1/capture/preview` returns the parse result | `preview returns the parse result without storing anything`, `preview of a text without a title answers 200 with an empty title`, `preview of a plain text has no project, due date or waiting for`, `preview needs a text`, `preview rejects a too long text`, `preview needs a login` | A | Pass |
| AC3 `fileIds` linked via the link system | `file ids are linked to the task, once each`, `a repeated capture links no files again`, `an unknown or deleted file is rejected and nothing is created`, `at most 50 files per capture`, `an empty file list links nothing`, `files are linked also when the syntax is not parsed`, `a captured file is shown by the link api on the task and on the file`, `a capture rejected after its files were checked leaves no task and no link` | A | Pass |
| AC4 `./gradlew test --tests 'ch.sonensei.cockpit.gtd.*'` passes (Testcontainers) | whole `ch.sonensei.cockpit.gtd` package | A | Pass |
| AC5 `OpenApiContractTest` passes without the flag | `OpenApiContractTest` (1 test) | A (no `UPDATE_OPENAPI`) | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest' jacocoTestReport`

Result:

Pass (BUILD SUCCESSFUL, 318 tests, 0 failures/errors/skipped; capture suites 93 tests, OpenApiContractTest 1 test). 2026-10-20T23:14:02+02:00 to 23:15:43+02:00.

## Full test execution

Command:

`sh scripts/check.sh` (= `.taskcheck`: tfp gate tests, server ktlint + tests, web eslint + tests + build, android ktlint + unit tests + lint)

Result:

Pass ("== all checks passed"; web 757 tests passed).

## .taskcheck

Result:

Pass. Runtime 2026-10-20T23:16:27+02:00 to 23:22:09+02:00 (about 5 min 40 s): within the 15 min limit, but above the 2 min warning threshold of `done` on this machine.

## Bugs found

None.

## Coverage

Status: verified

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All four capture files are new in this task (whole file = changed lines); for `GtdRepositories.kt` the only change is an interface method declaration with no executable bytecode.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: gtd-capture-api; base b977d6d64b6475ca55082dd46a72b0681b01556c; HEAD f3ac29dbf8a3f57f55b8aa492a351800736062b9; task commits b977d6d6..HEAD as supplied by the parent worker, cross-checked against the task `touches` and `git diff --stat`.
- Source/test snapshot identifier (including uncommitted inputs): commit f3ac29dbf8a3f57f55b8aa492a351800736062b9, `git status --short` empty before and after the run (no uncommitted inputs; no tests added).
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/CaptureParser.kt`, `.../gtd/domain/CaptureService.kt`, `.../gtd/api/CaptureController.kt`, `.../gtd/api/CaptureDtos.kt`. Excluded: `.../gtd/infra/GtdRepositories.kt` (only a Spring Data derived-query declaration in an interface, no JaCoCo counters; exercised indirectly by every parse through `CaptureService.parse`), `api/openapi.yaml` and `web/src/app/api/generated/**` (generated), `reviews/**` (docs). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via Gradle `jacoco` plugin (Gradle 9.8.1); `./gradlew test --tests 'ch.sonensei.cockpit.gtd.*' --tests '*OpenApiContractTest' jacocoTestReport`; cwd `server/`; selection = gtd package + OpenApiContractTest.
- Run start/completion time and exit status: 2026-10-20T23:14:02+02:00 to 23:15:43+02:00, exit 0 (BUILD SUCCESSFUL, `test` and `jacocoTestReport` executed).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (sha256 d96ba17d57e6835f4831761fc93009f15d3a948f24f0b3c3b484dc370369fa98, written 23:15), `server/build/jacoco/test.exec` (written 23:15).
- Evidence outputs are fresh and inputs stayed unchanged: yes; report and exec timestamps fall inside the run, tree clean and HEAD unchanged after it. (The later `.taskcheck` reran server tests; the numbers below come from the hashed report of the focused run.)
- Final run after latest test additions and production fixes: yes (no tests added; HEAD includes all fixes up to f3ac29db).

Values below use covered/total (percentage); JaCoCo has no statement counter, instructions are shown instead.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/CaptureParser.kt` | 718/718 (100.0%) | 64/64 (100.0%) | 26/26 (100.0%) | 101/101 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/domain/CaptureService.kt` | 505/509 (99.2%) | 33/34 (97.1%) | 14/15 (93.3%) | 49/49 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/CaptureController.kt` | 88/100 (88.0%) | 5/8 (62.5%) | 3/3 (100.0%) | 20/20 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/CaptureDtos.kt` | 240/261 (92.0%) | 7/8 (87.5%) | 22/26 (84.6%) | 48/48 (100.0%) |
| Aggregate (summed counters, the 4 files above; `GtdRepositories.kt` excluded, no counters) | 1551/1588 (97.7%) | 109/114 (95.6%) | 65/70 (92.9%) | 218/218 (100.0%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `CaptureController.kt:69` (2 of 4 branches: `request.id == null`, or `captures.existing(id)` returns null, then `throw ex`) | Rethrow of a `DataIntegrityViolationException` that is not an idempotent-repeat race | Justified gap: the lost-race path with an id is covered by the concurrent-capture test; without an id or with no row there is no reachable insert conflict to provoke without fault injection (no mocking convention for this in the web suites). |
| `CaptureController.kt:99` (1 branch: `checkNotNull(request.text)` null) | Defensive null check | Justified gap: `@NotNull` on the DTO rejects a missing text first (`preview needs a text`). |
| `CaptureDtos.kt:25,27,30,32` (getters `getId`/`getNotes`/`getFileIds`/`getParse` of `CaptureRequest`) | None; Kotlin getters not called because `toCommand` reads the fields | Non-material (Jackson deserializes via the constructor). |
| `CaptureDtos.kt:34` (1 branch: `checkNotNull(text)` null) | Defensive null check | Justified gap: `@NotNull` validation rejects it first (`invalid captures are rejected` with `{}`). |
| `CaptureService.kt:58` (1 branch: `fileResolver == null`) | No FILE resolver registered | Justified gap: the files module always registers one in the application context; the branch only matters for a deployment without the files module. |
| `CaptureService.kt:22,98` (synthetic default constructor of `CaptureCommand`, companion init) | None | Non-material compiler-generated code. |

## Unable to test

- Nothing required by the acceptance criteria. The controller's rethrow branch (`CaptureController.kt:69`) is not provoked, see above; no follow-up needed unless the parent wants fault-injection tests.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No additional tests were necessary for the reviewed scope.
