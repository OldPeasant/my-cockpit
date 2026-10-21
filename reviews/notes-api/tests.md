# Test Report

Date: 2026-10-21

Task: notes-api - Notes API (test-writer pass on HEAD 385e2b15, after the migration rename to V202610210907 and the merge of origin/main)

Scope: base b41323dc1f171c64d62911f7371aa6d9ffa3f566 (`git merge-base HEAD origin/main`, supplied by the parent) to
HEAD 385e2b15f1dea54253b0d48d86765989f52c0088, branch main. Task commits (supplied by the parent): 5d27f2d6, 6ec5ba23,
45a13f37, f147ae5b, ecbd7cab, 4aa5cba3, 7e100737, and merge 385e2b15 (only regenerated `api/openapi.yaml` and
`web/src/app/api/generated/`). `git diff --stat b41323dc..HEAD` matches the task's `touches` (notes module, notes
tests, notes migration) plus the generated contract/client and `reviews/notes-api/`. Uncommitted input: one new
untracked test file, `server/src/test/kotlin/ch/sonensei/cockpit/notes/NoteMigrationTests.kt` (added in this pass).

Requirements:
- tasks/notes-api.md acceptance criteria 1-6 and the four recorded assumptions
- SPEC.md §8.1 (via the task), docs/ARCHITECTURE.md "Coverage" and "Shared and generated files" (migration naming in
  AGENTS.md), docs/QUALITY.md, AGENTS.md
- Parent instruction: evaluate the drafted migration version guard `.tmp/NoteMigrationTests.kt`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/notes/NoteMigrationTests.kt` (new, untracked)

## Tests added or updated

Count: 3

The draft in `.tmp/NoteMigrationTests.kt` was useful: the notes migration was originally given the same Flyway version
as `calendar/V202610210900__calendar_sync.sql`, which breaks every Spring context with "Found more than one migration
with version ...". The draft was adapted:
- `notes migrations use versions no other migration uses`: scans `classpath*:db/migration/**/*.sql` (main and test
  resources), asserts there is at least one notes migration and at least one other one (so an empty scan cannot pass),
  and that no notes version equals any other migration's version or another notes version. Versions are compared the
  way Flyway reads them (`_` in a version is `.`, so `V1_1` equals `V1.1`), which the draft did not do.
- `notes migrations follow the module folder and naming convention`: every notes migration is
  `notes/V` + a 12-digit yyyyMMddHHmm timestamp + `__notes_` + a lower-case description + `.sql` (AGENTS.md "Shared
  and generated files").
- `version comparison treats underscore and dot separators like Flyway does`: pins the helper so the guard itself
  cannot silently stop detecting clashes (e.g. the test fixture `core/V202610200000_1__...` differs from
  `core/V202610200000__...`).

With the pre-rename name `notes/V202610210900__notes_model.sql`, the first test would fail with the clue
`notes/V202610210900__notes_model.sql shares version 202610210900 with another migration` (it computes version
`202610210900` for both files). This was reasoned from the code, not run: reproducing it would need a production
resource change.

No other tests were added: the coverage gaps below are unreachable defensive branches or compiler-generated code, and
every acceptance criterion is already covered by the existing suites (NoteWebTests 20, NoteEdgeCaseWebTests 26,
NoteInfraTests 9, NoteRevisionInfraTests 5, NoteMarkdownTests 8, NoteImageMaxSizeWebTests 1).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. CRUD `/api/v1/notes` with ETag, derived title, body_md, tags, pinned, archived; list filters by tag/pinned/archived | NoteWebTests: create with ETag/Location, empty create -> untitled, PATCH only sent fields + If-Match, PATCH rejects unknown fields/nulls/wrong types, invalid fields 400, tag dedup, unknown 404, list pinned first + tag/pinned/archived filters, cursor paging; NoteEdgeCaseWebTests: filter combinations, `archived=all`/invalid 400, tag normalization and spelling, cursor/page size validation, blank title, If-Match `*`/lists, 428/412 | F1 | Pass |
| 2a. Inline links `[[type:id]]` synced to the link table on save | NoteWebTests `inline links in the body are synced...`, `linked notes are resolved in the links panel`; NoteEdgeCaseWebTests `restoring a revision that differs only in the title keeps the body and its links`; NoteMarkdownTests inline link tests | F1 | Pass |
| 2b. `POST /notes/{id}/images` stores into `/Attachments/Notes`, returns `cockpit-file:{id}` | NoteWebTests image stored in Attachments/Notes, only images and only existing notes; NoteEdgeCaseWebTests mime/filename/octet-stream/missing part/Trash/folder reuse; NoteImageMaxSizeWebTests (413 / at maximum) | F1 | Pass |
| 3. Last 20 revisions, `GET .../revisions`, `POST .../revisions/{n}/restore` | NoteWebTests `only the newest 20 revisions are kept`, `restoring a revision puts its content back and keeps the replaced one`; NoteEdgeCaseWebTests 5-minute boundary, restore within 5 min, restore equal to newest revision, title-only restore, unknown revision 404, Trash 404, stale If-Match; NoteRevisionInfraTests 20-cap restore, savedAt, concurrent restore/change -> optimistic locking error | F1 | Pass |
| 4. Resolver NOTE, search indexing (Markdown stripped via flexmark), trash, ChangeEvents | NoteInfraTests resolver/Trash restore/purge; NoteWebTests search indexing with Markdown stripped, Trash/purge, change events; NoteEdgeCaseWebTests UPDATED events for revision/Trash restore; NoteRevisionInfraTests Trash restore re-index; NoteMarkdownTests plain text | F1 | Pass |
| 5. `./gradlew test --tests 'ch.sonensei.cockpit.notes.*'` passes (Postgres via Testcontainers) | all 72 notes tests (incl. the 3 new NoteMigrationTests) | F1 | Pass |
| 6. `api/openapi.yaml` regenerated and `OpenApiContractTest` passes without the flag | OpenApiContractTest (run without `UPDATE_OPENAPI`, on the post-merge contract) | F1 | Pass |
| Notes migration version does not clash with other modules (regression of the calendar clash fixed in 7e100737) | NoteMigrationTests (3) | F1 | Pass |

F1 = `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck`

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck`

Result:

Pass. BUILD SUCCESSFUL in 48s (started 2026-10-21T03:02:49+02:00). From `build/test-results/test`: NoteWebTests 20,
NoteEdgeCaseWebTests 26, NoteInfraTests 9, NoteRevisionInfraTests 5, NoteMarkdownTests 8, NoteMigrationTests 3,
NoteImageMaxSizeWebTests 1, OpenApiContractTest 1: 73 tests, 0 failures, 0 errors, 0 skipped. ktlintCheck passed for
main and test source sets (including the new file).

## Full test execution

Command:

`sh scripts/check.sh` (repository root; quality gate tests, server `./gradlew check`, web `npm run check`, android
`ktlintCheck testDebugUnitTest lintDebug`)

Result:

Pass. Ran 2026-10-21T03:04:35+02:00 to 03:12:52+02:00 (about 8 minutes), output ends with `== all checks passed`.
Server test results after the run: 2719 tests, 0 failures, 0 errors, 0 skipped. The web build prints the
pre-existing initial bundle budget warning only. The `ClockConfigurationTests` flake seen in the previous pass did not
occur.

## .taskcheck

Result:

Pass (`sh scripts/check.sh`, see above). Note: it takes about 8 minutes here, above the 2 minute warning of `done`
but within the 15 minute timeout.

## Bugs found

None. No production defect was found in the notes task.

## Coverage

Status: verified (fresh JaCoCo run on HEAD 385e2b15 plus the untracked NoteMigrationTests.kt; focused notes +
OpenApiContractTest selection).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All Kotlin files in scope are new in this task (`git diff b41323dc..HEAD`
adds them entirely), so whole-file coverage equals changed-line coverage for them.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: notes-api; base
  b41323dc1f171c64d62911f7371aa6d9ffa3f566; HEAD 385e2b15f1dea54253b0d48d86765989f52c0088; commits supplied by the
  parent (listed under Scope); files from `git diff --name-only b41323dc..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 385e2b15; only uncommitted input is untracked
  `server/src/test/kotlin/ch/sonensei/cockpit/notes/NoteMigrationTests.kt`, sha256
  `efb06e1a532283263f730de219075127e7d9f593a71a7d5b0592dde293455c04`. `git status --short` and the hash were the same
  before the coverage run and after the full check.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/notes/api/{NoteController,NoteDtos,NotesWebSupport}.kt`,
  `notes/domain/{Note,NoteCommands,NoteMarkdown,NoteService}.kt`, `notes/infra/{NoteItemResolver,NoteRepositories,NoteTrashContributor}.kt`.
  Excluded: `server/src/main/resources/db/migration/notes/V202610210907__notes_model.sql` (renamed in 7e100737 from
  `V202610210900__notes_model.sql`; SQL is not instrumentable by JaCoCo; it is applied in every Testcontainers notes
  test and checked by NoteMigrationTests); `api/openapi.yaml` and `web/src/app/api/generated/**` (generated; checked by
  OpenApiContractTest and `npm run check`). No deleted files. JaCoCo also lists `notes/api/Comparisons.kt`, a synthetic
  source of inlined Kotlin stdlib code without counters; not a task file.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin
  (configured in `server/build.gradle.kts`), Gradle 9.8.1; `./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck`
  in `server/`.
- Run start/completion time and exit status: started 2026-10-21T03:02:49+02:00; `build/jacoco/test.exec` written
  03:03:34, report written 03:03:37; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`,
  sha256 `4cc3bd89300314ad89cbe49eb46340aef69aadbd8fa7fa0ba1a5faf023e43879`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The test task executed (new test source compiled, not
  up to date) and rewrote the exec file and report. The later full `check` run does not run `jacocoTestReport`; the XML
  report hash was unchanged afterwards.
- Final run after latest test additions and production fixes: yes. It ran on HEAD 385e2b15 (including the migration
  rename 7e100737 and the merge) with the new NoteMigrationTests.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.
Statements = JaCoCo INSTRUCTION counter.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `notes/api/NoteController.kt` | 400/401 (99.8%) | 30/32 (93.8%) | 17/17 (100.0%) | 47/47 (100.0%) |
| `notes/api/NoteDtos.kt` | 466/508 (91.7%) | 13/16 (81.2%) | 48/53 (90.6%) | 92/92 (100.0%) |
| `notes/api/NotesWebSupport.kt` | 275/277 (99.3%) | 23/24 (95.8%) | 3/3 (100.0%) | 20/20 (100.0%) |
| `notes/domain/Note.kt` | 144/153 (94.1%) | 6/8 (75.0%) | 19/21 (90.5%) | 25/26 (96.2%) |
| `notes/domain/NoteCommands.kt` | 111/133 (83.5%) | N/A | 16/17 (94.1%) | 16/17 (94.1%) |
| `notes/domain/NoteMarkdown.kt` | 234/248 (94.4%) | 15/18 (83.3%) | 6/6 (100.0%) | 34/34 (100.0%) |
| `notes/domain/NoteService.kt` | 1346/1375 (97.9%) | 105/108 (97.2%) | 37/38 (97.4%) | 156/156 (100.0%) |
| `notes/infra/NoteItemResolver.kt` | 47/47 (100.0%) | 6/6 (100.0%) | 3/3 (100.0%) | 12/12 (100.0%) |
| `notes/infra/NoteRepositories.kt` | N/A | N/A | N/A | N/A |
| `notes/infra/NoteTrashContributor.kt` | 109/118 (92.4%) | 13/14 (92.9%) | 5/5 (100.0%) | 23/23 (100.0%) |
| **Aggregate (summed counters)** | 3132/3260 (96.1%) | 211/226 (93.4%) | 154/163 (94.5%) | 425/427 (99.5%) |

The aggregate covers the 9 measured Kotlin files. `NoteRepositories.kt` holds only Spring Data interfaces and a
projection, so JaCoCo has no bytecode counters for it (N/A; exercised through the service tests). The migration and
generated files are excluded as listed above. No threshold is defined (ARCHITECTURE.md: coverage is not a gate). The
numbers equal the previous pass on 3d5c5215: the rename and the merge did not change notes Kotlin code.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteService.kt:119,168,243` (partial) | `checkNotNull(updatedAt/createdAt)` null branches | Unreachable for persisted entities; no test. The other branches at 243 (restore whose replaced content equals the newest revision) are covered by `a restore does not keep the replaced content again when it equals the newest revision` |
| `NoteService.kt:378` / `Note.kt:40,43,54` / `NoteCommands.kt:21-25` / `NoteDtos.kt:45-67,119` | Compiler-generated data class/companion/JPA members and `checkNotNull` null branches; `Note.kt:43` blank-title fallback (the service normalizes blank titles to null, tested by the blank title tests) | Not behavior or unreachable; no test |
| `NoteMarkdown.kt:45` (2 of 4 branches) | `UUID.fromString` failure fallback in `inlineLinks` | Unreachable: the regex only matches canonical UUID text; malformed ids are covered by `inline links with an unknown type or a malformed id are ignored` |
| `NoteMarkdown.kt:70` (1 branch) | `firstOrNull()` of a plain-text line | Both title outcomes are tested; the remaining branch is an empty sequence that `lineSequence()` never yields |
| `NoteController.kt:210` (2 branches) | `file.contentType` null/blank/octet-stream fallback; `originalFilename` null | null, blank-name, octet-stream and explicit types are tested in NoteEdgeCaseWebTests image tests; the remaining short-circuit combinations behave the same |
| `NotesWebSupport.kt:33` (1 branch) | Jackson error without a property path -> field `body` | Unreachable: the PATCH body is a map whose keys are whitelisted first, so a conversion error always has a property path |
| `NoteTrashContributor.kt:27` (1 branch) | `checkNotNull(deletedAt)` for Trash items | Unreachable: the query only returns deleted notes |

## Unable to test

- The new guard's failure on a real version clash was not executed (it would need a duplicate production migration
  file); it is shown by reasoning above and by the separator test. No follow-up needed.
- Concurrency at the HTTP level (two simultaneous PATCHes giving 409) is tested at the service level
  (NoteInfraTests/NoteRevisionInfraTests assert `OptimisticLockingFailureException`); the mapping to 409 is the shared
  Problem Details advice covered by core tests. No follow-up needed.

## Summary

Tests added and all validation passed. Three migration guard tests were added (untracked, to be committed by the
parent); the 73 focused tests, ktlint and the full `.taskcheck` (`sh scripts/check.sh`) pass on HEAD 385e2b15.
Task-scoped coverage is unchanged and sufficient; no production defects found.
