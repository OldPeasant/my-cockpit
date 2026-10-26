# Test Report

Date: 2026-10-26

Task: mail-load-older - Load older mail on demand (API for MailHeaderSyncService.loadOlder)

Scope: `git diff 5e7f4c304cd0527f9d3444a0c55441034da74a5c..bcb2023695d56bb9582a29743e02f8ff3612024e -- server api web`
(task commits 049f4201d, 4cf6187e1, 03bda52db, 4fd023cc3, bcb202369, supplied by the parent worker), plus one
uncommitted test change from this pass (`MailLoadOlderGreenMailTests.kt`). Branch `main`, measured HEAD
`bcb2023695d56bb9582a29743e02f8ff3612024e`.

Requirements:
- `tasks/mail-load-older.md` acceptance criteria and its two recorded assumptions (endpoint shape and limit range;
  in-memory `hasOlder` semantics)
- SPEC.md §7.2 (older mail loaded on demand), as cited by the task
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailLoadOlderGreenMailTests.kt`

Already in the task commits and reviewed in this pass, not changed:
`MailLoadOlderControllerTests.kt` (9 tests), `MailOlderServiceTests.kt` (12 tests), and the constructor update in
`MailMessageControllerTests.kt`.

## Tests added or updated

Count: 1

- `a folder fully inside the sync window offers older mail once, then loads nothing on every retry` (external,
  GreenMail + Testcontainers Postgres). This is an end-to-end test of the recorded assumption that a folder
  entirely inside the sync window first reports `hasOlder=true`, and the next load returns
  `loaded=0, hasMore=false` and sets `hasOlder` to false. It also covers a repeated or double-submitted load-older
  on a complete folder, which stays `0/false` and loads nothing more. Before this pass, only unit tests with a
  mocked sync service covered this behavior.

I added no other tests. The existing 26 tests already cover every acceptance criterion, including boundaries,
errors, list scopes and CSRF. More tests would only repeat them.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: `POST /api/v1/mail/folders/{id}/load-older` calls `loadOlder` and returns `{loaded, hasMore}`; default limit 100, range 1..200 (assumption) | `load-older returns what was loaded, with the default limit`, `load-older passes the limit and rejects one out of range`, `load-older accepts the smallest limit and rejects a non-numeric one` (ControllerTests); `the limit is passed to the sync service unchanged` (ServiceTests); `loading older mail lets the folder list continue past the sync window`, `account Inbox and unified lists never offer older mail, a limit out of range is 400` (GreenMail) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*' --tests '*OpenApiContractTest'`; `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.api.MailLoadOlderGreenMailTests'` | Pass |
| AC1: Problem Details 404 for an unknown folder or one gone from the server; 400 for a malformed id | `an unknown folder is 404, a malformed id 400` (Controller); `a folder gone from the server is not found`, `an unknown folder is not found and nothing is recorded` (Service); `an unknown folder is 404, a folder deleted on the server too` (GreenMail, real IMAP delete) | same two commands | Pass |
| AC1: Problem Details 502/503 for an unreachable server or a busy pool | `an unreachable server is 502, busy connections 503, with an error code` (Controller, via `MailConnectionAdvice`); `an unreachable server is 502 with an error code` (GreenMail, server stopped) | same two commands | Pass |
| AC1 (security): a state-changing POST needs CSRF and loads nothing without it | `load-older without a CSRF token is rejected and loads nothing` (GreenMail, full security chain) | integrationTest command | Pass |
| AC2: folder list reports `hasOlder`, only on the last page of a `folderId` list; account Inbox and unified lists always false | `only the last page of a folder list asks whether there is older mail`, `a complete folder reports no older mail on its last page`, `an account Inbox list never offers older mail`, `a threaded folder list with its account offers older mail on the last page` (Controller); `account Inbox and unified lists never offer older mail, ...` (GreenMail) | same two commands | Pass |
| AC2 / assumption: `hasOlder` true until a load returns `hasMore=false` for the current UIDVALIDITY; per folder; non-selectable or unknown folders false | the 12 `MailOlderServiceTests` (UIDVALIDITY change, completeness for the UIDVALIDITY after the load, null UIDVALIDITY, folder deleted during a load, per-folder isolation, a later load that finds more); new GreenMail test `a folder fully inside the sync window offers older mail once, then loads nothing on every retry` | same two commands | Pass |
| AC3: `mail.api.*` tests pass, plus an external GreenMail test showing that after a load the list continues past the window | all 17 `mail.api` unit suites (now 0 failures); `loading older mail lets the folder list continue past the sync window` walks every cursor page before and after each load | same two commands | Pass (unit: 17 suites, 0 failures; integration: 6/6) |
| AC4: `api/openapi.yaml` regenerated, `OpenApiContractTest` passes without the update flag | `OpenApiContractTest` (1 test); `api/openapi.yaml` unchanged afterwards (same SHA-256 before and after) | `cd server && ./gradlew test --tests '*OpenApiContractTest'` (run without `UPDATE_OPENAPI`) | Pass |
| AC4: web client regenerated | `npm run api:check` (`web/scripts/api-gen-check.mjs`), part of `npm run check` in `.taskcheck` | `sh scripts/check.sh` | Pass |

## Focused test execution

Command:

`cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.api.MailLoadOlderGreenMailTests'`

Result:

Pass: 6 tests, 0 skipped, 0 failures, 0 errors (includes the new test; Docker/Testcontainers Postgres and
GreenMail ran for real).

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.api.*' --tests '*OpenApiContractTest'`

Result:

Pass: 17 `mail.api` suites plus `OpenApiContractTest`, 0 failures/errors (MailLoadOlderControllerTests 9,
MailOlderServiceTests 12, MailMessageControllerTests 6, OpenApiContractTest 1, ...).

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport`

Result:

Pass: BUILD SUCCESSFUL in 7m 20s, 341 suites, 4641 tests, 0 failures/errors. External-tagged tests are
excluded from `test` by design; the task's external class ran separately (see above).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (tfp gate tests, server `./gradlew check`, web `npm run check` including
`api:check`, android ktlint + unit tests + lint) ended with "== all checks passed".

## Bugs found

None. No test found production behavior that violates the acceptance criteria or the recorded assumptions.

## Coverage

Status: verified (server, JaCoCo, unit `test` task). The generated web client is excluded (see below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured. I took the JaCoCo `<line>` entries in `jacocoTestReport.xml` and kept those
on lines added at HEAD by `git diff -U0 5e7f4c304..HEAD`
(`MailMessageController.kt` lines 5, 28, 50, 62-63, 85-140, 321-333; all of `MailOlderService.kt`).
Results: `MailOlderService.kt` 17/17 lines (100%), branches on those lines 14/14 (100%);
`MailMessageController.kt` 15/15 executable changed lines (100%), branches on those lines 14/14 (100%).
Aggregate: 32/32 changed executable lines, 28/28 branches on changed lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-load-older`; base
  `5e7f4c304cd0527f9d3444a0c55441034da74a5c`; HEAD `bcb2023695d56bb9582a29743e02f8ff3612024e`; the parent
  worker supplied the task commits (049f4201d, 4cf6187e1, 03bda52db, 4fd023cc3, bcb202369) and scope
  `git diff 5e7f4c304..HEAD -- server api web`. I checked the diff against the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): SHA-256
  `MailOlderService.kt` 9143582a9214de3d69e207f3403d0e9a006e25b8f590b7f208a3c3a889d9ec5f,
  `MailMessageController.kt` 5a1c2afe2bb327eddbee90440ba34f395f3d93932b8b57afabc1bf30c6bd0a4b,
  `MailLoadOlderGreenMailTests.kt` (uncommitted) d10998f5ba9cc785703264c5654efee2ecde92bbcd0bff717218fe016e8d50e2,
  `MailLoadOlderControllerTests.kt` 54304b22ba5742bb89c9be7c94615bd2ae706abee32cbdea60c2f593d8560a1a,
  `MailOlderServiceTests.kt` b3a01bafc178913895d085f091ec8e09048e5623f61ab4acc60c23cc7347e5ba,
  `api/openapi.yaml` a0b741c7329fe75717007d0b41f76dea6a737176ac7a4d21f1f700c9a017edfe.
  I hashed these before the runs and again after them; all were identical.
- Exact included files; renamed/deleted/excluded files and reasons: included
  `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailOlderService.kt` (new) and
  `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailMessageController.kt` (modified). Excluded:
  `api/openapi.yaml` (generated contract, not executable) and `web/src/app/api/generated/**` (5 generated client
  files from ng-openapi-gen, not hand-written behavior; their freshness is checked by `npm run api:check`
  instead). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin
  (project configuration, unchanged); `./gradlew test jacocoTestReport` in `server/`; full `test` task (all
  non-`external` tests). The GreenMail `external` tests are not in this JaCoCo report.
- Run start/completion time and exit status: about 2026-10-26T05:45:55+01:00 to 05:53:14+01:00, BUILD SUCCESSFUL
  (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (written 2026-10-26 05:53:14 +01:00, SHA-256 09f0e6619c19a6cb9a6b27c59fb28dd0e8fa81a2e2c6e02a791126cab8a9b9df),
  `server/build/jacoco/test.exec` (written 05:53:11 +01:00).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Gradle reran `test` because the test sources
  changed, the report mtime falls inside this run, and the snapshot hashes matched before and after.
- Final run after latest test additions and production fixes: yes (no production changes in this pass; the new
  test existed before the run).

Values below use covered/total (percentage).

| Changed file | Statements (JaCoCo instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailOlderService.kt` | 106/106 (100%) | 14/14 (100%) | 3/3 (100%) | 17/17 (100%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailMessageController.kt` | 1012/1050 (96.4%) | 30/34 (88.2%) | 88/89 (98.9%) | 194/194 (100%) |
| Aggregate (summed counters, 2 files; generated web client and openapi.yaml excluded as above) | 1118/1156 (96.7%) | 44/48 (91.7%) | 91/92 (98.9%) | 211/211 (100%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailMessageController.kt:313` (`IMAGE_CACHE` in companion init, 2 instructions; the 1 missed method/class is most likely this companion's initializer) | Pre-existing companion/static init, not part of this task | Justified gap: outside task scope, not task behavior |
| `MailMessageController.kt:439, 462, 495, 496` (`checkNotNull(...)` null branch, 1 branch each) | Pre-existing defensive null checks in summary/detail mappers; not reachable for persisted messages | Justified gap: pre-existing code, outside task scope, not meaningfully reachable |

No uncovered line or branch lies in the task-changed lines.

## Unable to test

- Persistence of folder completeness across a restart: by design (recorded assumption) completeness is held in
  memory, so there is nothing to test beyond the documented "offers older once after restart" behavior, which
  the new GreenMail test and `MailOlderServiceTests` cover.
- The 503 `POOL_EXHAUSTED` mapping is tested through `MailConnectionAdvice` with a mocked service, not by
  exhausting a real IMAP pool against GreenMail. The mapping is the shared handler from earlier tasks. No
  follow-up needed.
- Loading older mail from a non-selectable folder end to end (`loaded=0, hasMore=false`) is decided inside
  `MailHeaderSyncService` (mail-header-sync, outside this task's scope). The `hasOlder=false` side is unit-tested
  in `MailOlderServiceTests`. No follow-up needed.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. This pass added one external
GreenMail test for the "folder fully inside the sync window / repeated load" behavior. All focused, full server,
integration and `.taskcheck` runs pass.
