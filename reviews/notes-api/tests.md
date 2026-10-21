# Test Report

Date: 2026-10-21

Task: notes-api - Notes API (final test-writer pass on the final code, after fix d4e1dfeb: NUL characters in tags
and in the tag filter are rejected with 400)

Scope: base 00b5371959c290a90a2e4d2018ced7155fc536d0 (`git merge-base HEAD origin/main`, supplied by the parent) to
HEAD d4e1dfeb19737574e582c31969a8ece6b2849e97, branch main. Task commits (supplied by the parent, cross-checked with
`git log --oneline 00b53719..HEAD`, which lists exactly these four): d9341538 (implementation: notes module, notes
tests, notes migration, regenerated `api/openapi.yaml` and `web/src/app/api/generated/`), 07e336b2 (quality evidence
only), 19eeb0b9 (boundary and invalid-PATCH tests, reports), d4e1dfeb (NUL check in `NoteService.list` and
`NoteService.normalizeTags`, 2 tests in NoteEdgeCaseWebTests, fixes.md). The diff stays inside the task's `touches`
plus generated API files and `reviews/notes-api/`. Uncommitted inputs at measurement time: this pass's change to
`server/src/test/kotlin/ch/sonensei/cockpit/notes/NoteEdgeCaseWebTests.kt`, and `reviews/notes-api/review.md`
(being written by the concurrent code-reviewer; not a test or production input, not touched by this pass).

Requirements:
- tasks/notes-api.md acceptance criteria 1-6 and the recorded assumptions (revisions, list defaults, tags, derived
  title, image upload, no-op PATCH)
- SPEC.md §8.1 (via the task), docs/ARCHITECTURE.md "Coverage", docs/QUALITY.md, AGENTS.md
- reviews/notes-api/fixes.md (intent of fix d4e1dfeb: NUL in tags/tag filter must give 400 Problem Details, not 500)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/notes/NoteEdgeCaseWebTests.kt` (modified, uncommitted; for the parent
  to commit)

## Tests added or updated

Count: 1

The fix d4e1dfeb already came with two tests (`a tag filter with NUL characters is rejected with 400`, `tags with
NUL characters are rejected with 400 on create and PATCH`). They cover the main paths. One remaining gap around the
fix was closed:

- `a NUL tag is reported together with other tag errors and a rejected change has no side effects`: a PATCH whose
  tags contain both a NUL tag (`#\u0000`, NUL after the `#`-strip) and a 51-character tag reports both `tags` errors
  in one Problem Details response (multiple violations, collected rather than first-wins); a create whose only tag is
  NUL surrounded by blanks is rejected (NUL survives trimming). Neither request publishes a ChangeEvent, keeps a
  revision, changes the body sent with the PATCH, creates a note, or changes `GET /notes/tags`. A tag filter that is
  only a NUL character (not blank for the controller's blank check) also gives 400.

No other real gaps were found: every acceptance criterion already had passing tests (see the earlier passes), and
the code changed since then only by the two NUL checks.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. CRUD `/api/v1/notes` with ETag, derived title, body_md, tags, pinned, archived; list filters by tag/pinned/archived | NoteWebTests (create with ETag/Location, untitled create, PATCH only sent fields + If-Match, unknown fields/nulls/wrong types, invalid fields 400, tag dedup, unknown 404, list order and tag/pinned/archived filters, cursor paging); NoteEdgeCaseWebTests (filter combinations, `archived=all`/invalid 400, tag normalization and spelling, cursor/page size validation, blank title, NUL in title/body, NUL in tags and tag filter (3 tests incl. the new one), If-Match `*`/lists, 428/412, stale DELETE, maximum values, atomic invalid PATCH) | F1 | Pass |
| 2a. Inline links `[[type:id]]` synced to the link table on save | NoteWebTests `inline links in the body are synced to the link table on save`, `linked notes are resolved in the links panel`; NoteEdgeCaseWebTests `restoring a revision that differs only in the title keeps the body and its links`; NoteMarkdownTests inline link tests | F1 | Pass |
| 2b. `POST /notes/{id}/images` stores into `/Attachments/Notes`, returns `cockpit-file:{id}` | NoteWebTests image upload and image-only/existing-note checks; NoteEdgeCaseWebTests mime from filename, octet-stream, no filename/upper-case type, no name and type, missing part/Trash, folder reuse; NoteImageMaxSizeWebTests | F1 | Pass |
| 3. Last 20 revisions, `GET .../revisions`, `POST .../revisions/{n}/restore` | NoteWebTests `only the newest 20 revisions are kept`, restore, 5-minute rule; NoteEdgeCaseWebTests 5-minute boundary, title-only, no-op, restore variants, 404s, If-Match; NoteRevisionInfraTests (20-cap restore, savedAt, concurrent restore/change -> optimistic locking) | F1 | Pass |
| 4. Resolver NOTE, search indexing (Markdown stripped via flexmark), trash, ChangeEvents | NoteInfraTests resolver and Trash list/restore/purge; NoteWebTests search with stripped Markdown, Trash/purge, change events; NoteEdgeCaseWebTests UPDATED events for restores, no events for rejected NUL-tag changes (new); NoteRevisionInfraTests Trash restore re-index; NoteMarkdownTests | F1 | Pass |
| 5. `./gradlew test --tests 'ch.sonensei.cockpit.notes.*'` passes (Postgres via Testcontainers) | all 77 notes tests in 7 classes | F1 | Pass |
| 6. `api/openapi.yaml` regenerated and `OpenApiContractTest` passes without the flag | OpenApiContractTest (without `UPDATE_OPENAPI`) | F1 | Pass |

F1 = `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest'`, followed by
`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest' jacocoTestReport ktlintCheck`
(the second run found `test` up to date with the same inputs and wrote the JaCoCo report from the first run's
execution data). The fix d4e1dfeb did not change the API contract (no new response codes; `listNotes` already
documented 400); `UPDATE_OPENAPI=1` regeneration was not re-run in this pass, and the contract test without the flag
passed on the current code.

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests '*OpenApiContractTest'`

Result:

Pass. BUILD SUCCESSFUL in 1m 8s (started 2026-10-21T04:26:50+02:00, test JVMs shut down 04:28:01+02:00;
`compileTestKotlin` and `test` executed). From `server/build/test-results/test`: NoteEdgeCaseWebTests 31,
NoteWebTests 20, NoteInfraTests 9, NoteMarkdownTests 8, NoteRevisionInfraTests 5, NoteMigrationTests 3,
NoteImageMaxSizeWebTests 1, OpenApiContractTest 1: 78 tests, 0 failures, 0 errors, 0 skipped. Persistence tests ran
on Postgres via Testcontainers. Then `... jacocoTestReport ktlintCheck`: BUILD SUCCESSFUL in 18s, ktlint passed for
main and test source sets (test sources re-linted including the modified file).

## Full test execution

Command:

`sh scripts/check.sh` (repository root; this is `.taskcheck`: lint + unit tests of server, web and android)

Result:

FULL_RESULT

## .taskcheck

Result:

TASKCHECK_RESULT

## Bugs found

None. The new test passed on the first run against d4e1dfeb; no production defect was found.

## Coverage

Status: verified (fresh JaCoCo run on HEAD d4e1dfeb plus the modified NoteEdgeCaseWebTests.kt; focused notes +
OpenApiContractTest selection).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Every Kotlin file in scope is added in full by the task
(`git diff 00b53719..HEAD` contains no pre-existing lines in them), so whole-file coverage equals changed-line
coverage here. The two lines changed by the fix d4e1dfeb (`NoteService.kt:78`, `:293`) are fully covered, both
branches each (the file's only missed branches are at 125, 174, 249, see below).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: notes-api; base
  00b5371959c290a90a2e4d2018ced7155fc536d0; HEAD d4e1dfeb19737574e582c31969a8ece6b2849e97; task commits d9341538,
  07e336b2, 19eeb0b9, d4e1dfeb supplied by the parent; files from
  `git diff --name-only 00b53719..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD d4e1dfeb; only uncommitted source/test input
  is `server/src/test/kotlin/ch/sonensei/cockpit/notes/NoteEdgeCaseWebTests.kt`, sha256
  `fde92dca2253a402277a7de7332054f4c7aeda79670ed95132a1fd9cf346063e` (identical before the run at 04:26:46 and after
  the report at 04:28:13). `reviews/notes-api/review.md` was also modified, by the concurrent code-reviewer; it is
  not a build input.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/notes/api/{NoteController,NoteDtos,NotesWebSupport}.kt`,
  `notes/domain/{Note,NoteCommands,NoteMarkdown,NoteService}.kt`,
  `notes/infra/{NoteItemResolver,NoteRepositories,NoteTrashContributor}.kt`. Excluded:
  `server/src/main/resources/db/migration/notes/V202610210907__notes_model.sql` (SQL, not instrumentable by JaCoCo;
  applied by every Testcontainers notes test and checked by NoteMigrationTests); `api/openapi.yaml` and
  `web/src/app/api/generated/**` (generated; checked by OpenApiContractTest). No renamed or deleted files in the
  range. JaCoCo also lists `notes/api/Comparisons.kt`, a synthetic source of inlined Kotlin stdlib code without
  counters; not a task file.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin configured
  in `server/build.gradle.kts`, Gradle 9.8.1; F1 above, working directory `server/`; selection
  `ch.sonensei.cockpit.notes.*` and `*OpenApiContractTest`.
- Run start/completion time and exit status: test run 2026-10-21T04:26:50+02:00 to 04:28:01+02:00 (test result
  timestamps 02:27:03Z-02:27:59Z), BUILD SUCCESSFUL; report run BUILD SUCCESSFUL, `jacocoTestReport` executed.
- Generated report paths and report hashes/run identifier: `server/build/jacoco/test.exec` (written
  2026-10-21 04:28:01+02:00 by this test run); `server/build/reports/jacoco/test/jacocoTestReport.xml` (written
  04:28:13+02:00), sha256 `569359f517341bfacfdabec60e18ab352b4139ea702eb75cd851f68f0217f421`.
- Evidence outputs are fresh and inputs stayed unchanged: yes. The test sources were recompiled and the test task
  executed for the new snapshot; the exec file is from that run and the XML was regenerated from it (counters moved
  versus the previous pass: NoteService.kt 1346 -> 1393 instructions, 108 -> 116 branches). Counters read with a
  small script over the XML (`<sourcefile>` counters INSTRUCTION, BRANCH, METHOD, LINE).
- Final run after latest test additions and production fixes: yes. It measures d4e1dfeb (the latest production
  fix) plus the test added in this pass.

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
| `notes/domain/NoteService.kt` | 1393/1422 (98.0%) | 113/116 (97.4%) | 37/38 (97.4%) | 158/158 (100.0%) |
| `notes/infra/NoteItemResolver.kt` | 47/47 (100.0%) | 6/6 (100.0%) | 3/3 (100.0%) | 12/12 (100.0%) |
| `notes/infra/NoteRepositories.kt` | N/A | N/A | N/A | N/A |
| `notes/infra/NoteTrashContributor.kt` | 109/118 (92.4%) | 13/14 (92.9%) | 5/5 (100.0%) | 23/23 (100.0%) |
| **Aggregate (summed counters)** | 3179/3307 (96.1%) | 219/234 (93.6%) | 154/163 (94.5%) | 427/429 (99.5%) |

The aggregate covers the 9 measured Kotlin files. `NoteRepositories.kt` holds only Spring Data interfaces and a
projection, so JaCoCo has no bytecode counters for it (N/A; exercised through the service and web tests). The
migration and generated files are excluded as listed above. No threshold is defined (ARCHITECTURE.md: coverage is not
a gate).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteService.kt:125,174,249` (1 branch each) | `checkNotNull(updatedAt/createdAt)` null branches | Unreachable for persisted entities; no test |
| `NoteService.kt:385`, `Note.kt:40,54`, `NoteCommands.kt:21-25`, `NoteDtos.kt:61-67` | Compiler-generated members (companion constant init, data class / default-argument constructors of `NoteFilter` and `CreateNoteRequest`, JPA accessors) | Not behavior; no test |
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
- Observation, not a defect: a request with both a NUL tag and an invalid title reports only the `tags` error, because
  tag normalization throws before the remaining field validation. The task and spec do not require all field errors
  in one response, so no test pins either behavior.

## Summary

SUMMARY_TEXT
