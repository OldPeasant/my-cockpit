# Test Report

Date: 2026-10-21

Task: notes-api - Notes API (fresh test-writer pass on the squashed implementation commit 3fc95687; the earlier
evidence referenced pre-squash commits that no longer exist)

Scope: base 3dbd3504fa58040c26e7d656de1b09afb06f5106 (supplied by the parent) to HEAD
c090c42aed1147cf2a58e829d9964dd9b04cb6f9, branch main. `git log 3dbd3504..HEAD` has exactly two commits:
3fc95687e1ea116b5104f7b9295979b0668ebb86 (the squashed task implementation: notes module, notes tests, notes
migration, regenerated `api/openapi.yaml` and `web/src/app/api/generated/`, earlier `reviews/notes-api/` files) and
c090c42a (only `reviews/notes-api/quality.json`). Task ownership: parent-supplied commit list, cross-checked against the
task's `touches` and `git diff --stat 3dbd3504..HEAD`. Uncommitted input: one modified test file,
`server/src/test/kotlin/ch/sonensei/cockpit/notes/NoteEdgeCaseWebTests.kt` (added in this pass); no other changes.

Requirements:
- tasks/notes-api.md acceptance criteria 1-6 and the recorded assumptions (revisions, list defaults, tags, derived
  title, image upload, no-op PATCH)
- SPEC.md §8.1 (via the task), docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md, AGENTS.md

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/notes/NoteEdgeCaseWebTests.kt` (modified, uncommitted)

## Tests added or updated

Count: 2

The existing suites (NoteWebTests 20, NoteEdgeCaseWebTests 26, NoteInfraTests 9, NoteRevisionInfraTests 5,
NoteMarkdownTests 8, NoteMigrationTests 3, NoteImageMaxSizeWebTests 1) already cover every acceptance criterion. Two
real gaps were found and closed:

- `values exactly at their maximum are accepted, duplicates do not count towards the tag limit`: the existing tests
  only checked values just above the limits (501-char title, 100 001-char body, 51-char tag, 51 tags). This pins the
  "at the limit" side: title of 500, body of 100 000, 50 tags of 50 characters are stored; 52 tags of which 2 are case
  or `#` duplicates are accepted as 50 (the limit applies after deduplication, per the tags assumption).
- `an invalid PATCH changes nothing, not even the valid fields sent with it`: a PATCH with valid `pinned`, `archived`,
  `tags` (a new tag) and `bodyMd` plus an invalid title (or a body with NUL) is rejected with 400 and leaves version,
  pin, archive state, body, tags, revisions and the tag list untouched. `NoteService.apply` deliberately applies tags
  first because the tag spelling query auto-flushes; this guards that ordering and the transactional rollback against
  partial writes.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. CRUD `/api/v1/notes` with ETag, derived title, body_md, tags, pinned, archived; list filters by tag/pinned/archived | NoteWebTests: create with ETag/Location, empty create -> untitled, PATCH only sent fields + If-Match, PATCH rejects unknown fields/nulls/wrong types, invalid fields 400, tag dedup, unknown 404, list order + tag/pinned/archived filters, cursor paging; NoteEdgeCaseWebTests: filter combinations, `archived=all`/invalid 400, tag normalization and spelling, cursor/page size validation, blank title, NUL, If-Match `*`/lists, 428/412, stale DELETE, plus the 2 new tests (maximum values, atomic invalid PATCH) | F1 | Pass |
| 2a. Inline links `[[type:id]]` synced to the link table on save | NoteWebTests `inline links in the body are synced to the link table on save`, `linked notes are resolved in the links panel`; NoteEdgeCaseWebTests `restoring a revision that differs only in the title keeps the body and its links`; NoteMarkdownTests inline link tests (3) | F1 | Pass |
| 2b. `POST /notes/{id}/images` stores into `/Attachments/Notes`, returns `cockpit-file:{id}` | NoteWebTests `an image is stored in Attachments-Notes and its reference returned`, `only images are accepted, and only for existing notes`; NoteEdgeCaseWebTests mime from filename, octet-stream, no filename/upper-case type, no name and type, missing part/Trash, folder reuse; NoteImageMaxSizeWebTests (413 above, stored at maximum) | F1 | Pass |
| 3. Last 20 revisions, `GET .../revisions`, `POST .../revisions/{n}/restore` | NoteWebTests `only the newest 20 revisions are kept`, `restoring a revision puts its content back and keeps the replaced one`, `a content change keeps the previous version, at most one per five minutes`; NoteEdgeCaseWebTests 5-minute boundary, title-only change, no-op change, restore within 5 min, restore equal to newest revision, title-only restore, no-op restore, unknown revision 404, Trash 404, stale/missing If-Match; NoteRevisionInfraTests 20-cap restore, savedAt, concurrent restore/change -> optimistic locking error | F1 | Pass |
| 4. Resolver NOTE, search indexing (Markdown stripped via flexmark), trash, ChangeEvents | NoteInfraTests resolver registration/resolve/missing+trashed, Trash list/restore/purge/refusals; NoteWebTests `notes are indexed for search with Markdown stripped`, Trash/purge, `every change publishes a change event`; NoteEdgeCaseWebTests UPDATED events for revision/Trash restore; NoteRevisionInfraTests Trash restore re-index; NoteMarkdownTests plain text | F1 | Pass |
| 5. `./gradlew test --tests 'ch.sonensei.cockpit.notes.*'` passes (Postgres via Testcontainers) | all 74 notes tests in 7 classes | F1 | Pass |
| 6. `api/openapi.yaml` regenerated and `OpenApiContractTest` passes without the flag | OpenApiContractTest (run without `UPDATE_OPENAPI`) | F1 | Pass |

F1 = `server/gradlew -p server test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck`
(repository root; equivalent to `cd server && ./gradlew ...`). Regeneration of the contract itself
(`UPDATE_OPENAPI=1`) was not re-run in this pass; the contract test without the flag passing shows the committed
`api/openapi.yaml` matches the current code.

## Focused test execution

Command:

`server/gradlew -p server test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck`

Result:

Pass. BUILD SUCCESSFUL in 36s (started about 2026-10-21T03:56:50+02:00, report written 03:57:28+02:00). From
`server/build/test-results/test`: NoteWebTests 20, NoteEdgeCaseWebTests 28, NoteInfraTests 9, NoteRevisionInfraTests 5,
NoteMarkdownTests 8, NoteMigrationTests 3, NoteImageMaxSizeWebTests 1, OpenApiContractTest 1: 75 tests, 0 failures,
0 errors, 0 skipped. Persistence tests ran on Postgres via Testcontainers. ktlintCheck passed for main and test source
sets (test sources recompiled and re-linted, including the modified file).

## Full test execution

Command:

`sh scripts/check.sh` (repository root; this is `.taskcheck`)

Result:

CHECK_RESULT_PLACEHOLDER

## .taskcheck

Result:

CHECK_RESULT_SHORT_PLACEHOLDER

## Bugs found

None. No production defect was found; both new tests passed on the first run against 3fc95687.

## Coverage

Status: verified (fresh JaCoCo run on HEAD c090c42a = implementation 3fc95687, plus the modified
NoteEdgeCaseWebTests.kt; focused notes + OpenApiContractTest selection).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. All Kotlin files in scope are added in full by 3fc95687
(`git diff 3dbd3504..HEAD` has no pre-existing lines in them), so whole-file coverage equals changed-line coverage here.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: notes-api; base
  3dbd3504fa58040c26e7d656de1b09afb06f5106; HEAD c090c42aed1147cf2a58e829d9964dd9b04cb6f9; task commits 3fc95687
  (code) and c090c42a (reviews only), supplied by the parent; files from
  `git diff --name-only 3dbd3504..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD c090c42a; only uncommitted input is
  `server/src/test/kotlin/ch/sonensei/cockpit/notes/NoteEdgeCaseWebTests.kt`, sha256
  `e19a0acf29388bc9d1cdb7862f30e58ef2b7cf00dc578c6b036ea775fc8cc444` (same before and after the coverage run;
  `git status --short` showed only this file).
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/notes/api/{NoteController,NoteDtos,NotesWebSupport}.kt`,
  `notes/domain/{Note,NoteCommands,NoteMarkdown,NoteService}.kt`,
  `notes/infra/{NoteItemResolver,NoteRepositories,NoteTrashContributor}.kt`. Excluded:
  `server/src/main/resources/db/migration/notes/V202610210907__notes_model.sql` (SQL, not instrumentable by JaCoCo;
  applied by every Testcontainers notes test and checked by NoteMigrationTests); `api/openapi.yaml` and
  `web/src/app/api/generated/**` (generated; checked by OpenApiContractTest). No renamed or deleted files in the
  range. JaCoCo also lists `notes/api/Comparisons.kt`, a synthetic source of inlined Kotlin stdlib code without
  counters; not a task file.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin configured
  in `server/build.gradle.kts`, Gradle 9.8.1; F1 above, run from the repository root with `-p server`; selection
  `ch.sonensei.cockpit.notes.*` and `*OpenApiContractTest`.
- Run start/completion time and exit status: about 2026-10-21T03:56:50+02:00 to 03:57:28+02:00; test result
  timestamps 01:56:59Z-01:57:24Z; BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (written 2026-10-21 03:57:28+02:00), sha256 `fcb20f7e63c6090465cf8a155c8d37c0ce114b264095b2980b65716e230e75db`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. Production and test sources were recompiled
  (`compileKotlin`, `compileTestKotlin` executed), the test task executed and `jacocoTestReport` rewrote the XML. The
  test-file hash was unchanged after the run. Counters were read with a small script over the XML (`<sourcefile>`
  counters INSTRUCTION, BRANCH, METHOD, LINE).
- Final run after latest test additions and production fixes: yes. No production code changed in this pass; the run
  includes both new tests.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.
Statements = JaCoCo INSTRUCTION counter, Functions = METHOD.

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
projection, so JaCoCo has no bytecode counters for it (N/A; exercised through the service and web tests). The
migration and generated files are excluded as listed above. No threshold is defined (ARCHITECTURE.md: coverage is not
a gate). The two new tests strengthen assertions on already-executed code, so the counters did not move.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteService.kt:119,168,243` (1 branch each) | `checkNotNull(updatedAt/createdAt)` null branches | Unreachable for persisted entities; no test. The covered branches at 243 (restore whose replaced content equals the newest revision) are tested by `a restore does not keep the replaced content again when it equals the newest revision` |
| `NoteService.kt:378`, `Note.kt:40,54`, `NoteCommands.kt:21-25`, `NoteDtos.kt:61-67` | Compiler-generated members (data class/companion, default-argument constructors of `NoteFilter` and `CreateNoteRequest`, JPA accessors) | Not behavior; no test |
| `Note.kt:43` (2 branches) | `displayTitle` fallback for a blank stored title | Unreachable via the service: blank titles are normalized to null (tested by `a blank title is stored as no title, NUL characters are rejected`) |
| `NoteDtos.kt:45,46,119` (1 branch each) | `checkNotNull(createdAt/updatedAt)` null branches | Unreachable for persisted entities |
| `NoteMarkdown.kt:45` (2 of 4 branches) | `UUID.fromString` failure fallback in `inlineLinks` | Unreachable: the regex only matches canonical UUID text; malformed ids are covered by `inline links with an unknown type or a malformed id are ignored` |
| `NoteMarkdown.kt:70` (1 branch) | `firstOrNull()` on an empty line sequence | `lineSequence()` never yields an empty sequence; both title outcomes are tested |
| `NoteController.kt:210` (2 branches) | Remaining short-circuit combinations of content type null/blank/octet-stream | null, octet-stream and explicit types are tested; the remaining combinations take the same path |
| `NotesWebSupport.kt:33` (1 branch) | Jackson error without a property path -> field `body` | Unreachable: PATCH keys are whitelisted first, so a conversion error always has a property path |
| `NoteTrashContributor.kt:27` (1 branch) | `checkNotNull(deletedAt)` for Trash items | Unreachable: the query only returns deleted notes |

## Unable to test

- Concurrency at the HTTP level (two simultaneous PATCHes giving 409) is tested at the service level
  (NoteInfraTests/NoteRevisionInfraTests assert `OptimisticLockingFailureException`); the mapping to 409 is the shared
  Problem Details advice covered by core tests. No follow-up needed.
- `UPDATE_OPENAPI=1` regeneration was not re-run in this pass (it rewrites a committed file, outside a test-writer's
  remit); the contract test without the flag passed on the current code. No follow-up needed.

## Summary

SUMMARY_PLACEHOLDER
