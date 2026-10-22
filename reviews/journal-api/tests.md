# Test Report

Date: 2026-10-22

Task: journal-api - Journal API

Scope: `git diff 9e8b3aa49d8a701d5c1f6415400bc4abb862e23a..79f6b1697a31559519ce5b839a934d4c1ee53cae` on `main`. The parent named the task commits: 11749498 (implementation), 92e1f03a (web tests, openapi, web client) and 79f6b169 (CR-2 fix, restore race -> 409). The scope also includes the uncommitted test files that an interrupted earlier test-writer run left behind, which this run reviewed and finished.

Requirements:
- `tasks/journal-api.md`: acceptance criteria 1-4 and the five recorded assumptions
- `reviews/journal-api/review.md`: CR-1 test gaps (a), (b) and (c), and CR-2 (restore race)
- `reviews/journal-api/fixes.md` (CR-2 fix)
- SPEC.md §8.5, §4.2-4.4; docs/ARCHITECTURE.md "Coverage" and "Persistence conventions"; AGENTS.md project notes (migration layout `db/migration/<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql`)
- Existing test conventions (`NoteEdgeCaseWebTests`, `NoteImageMaxSizeWebTests`, `MigrationConventionTests`)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/journal/entries/JournalEntryEdgeCaseWebTests.kt` (new, untracked; left by the earlier run, reviewed and kept, one test added, ktlint-formatted)
- `server/src/test/kotlin/ch/sonensei/cockpit/journal/entries/JournalImageMaxSizeWebTests.kt` (new, untracked; left by the earlier run, reviewed and kept unchanged)
- `server/src/test/kotlin/ch/sonensei/cockpit/journal/entries/JournalEntryMigrationTests.kt`: the earlier run left this file and this run removed it, before it was ever committed. It asserted that the migration lives in `journal/entries/`. That is the layout the project convention forbids (see TW-1). It also duplicated the existing `core.persistence.MigrationConventionTests`, which is the authoritative check and now fails on that file.

## Tests added or updated

Count: 19 new tests (18 in `JournalEntryEdgeCaseWebTests`, 1 in `JournalImageMaxSizeWebTests`). This run wrote one of them: `a Trash restore that loses against a concurrent create of its date is a 409, the winner stays the only active entry`. It reviewed the other 18 and kept them.

## Requirement coverage

Focused command (F): `cd server && ./gradlew ktlintCheck test --rerun --tests 'ch.sonensei.cockpit.journal.entries.*' jacocoTestReport`

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: upsert by date (201 create / 200 replace with If-Match, 428/412, future date rejected, time zone) | `JournalEntryWebTests`: `a PUT creates the entry of a date...`, `creating with an If-Match is refused...`, `today follows the time zone setting`; edge: `today's date is accepted, a replace keeps id and createdAt...`, `a replace that loses against a concurrent change fails with optimistic locking, a stale ETag is 412` | F | Pass |
| CR-1 (a): a concurrent create of the same date -> 409, exactly one active row | `a concurrent create of the same date is a conflict and stores nothing of the loser`. It calls the service: a competing row is committed from another connection inside the If-Match callback, i.e. between the lookup and the flush. It checks for a `ConflictException` with status 409, 1 row, no search document, no change event, and that a later PUT without If-Match gets 428 | F | Pass |
| CR-1 (c): a PUT changing a tag only by case keeps the stored spelling | `tags keep their stored spelling on replace, duplicates collapse to the first spelling` (`["walk","new"]` over `["Walk","Tea"]` -> `Walk`, `new`; also checked in `journal_entry_tag`) | F | Pass |
| AC1: Markdown body, mood 1-5, tags normalized (max 50x50, NUL, empty) | `invalid entries are rejected`; edge: `tag limits - 50 tags of 50 characters...`, `the body may be 100000 characters without NUL, mood 1 to 5 or null` | F | Pass |
| AC1: images in /Attachments/Journal | `an image is stored in Attachments-Journal`; edge: `an image without a usable content type gets its mime from the name...`, `a purge removes the entry with its tags and links, its images stay in the files` | F | Pass |
| CR-1 (b): an oversized image -> 413 Problem Details, nothing stored | `JournalImageMaxSizeWebTests.an image above the maximum size is a 413 Problem Details, one at the maximum is stored` (`cockpit.files.max-size=1KB`; the limit+1 bytes give 413 `application/problem+json` and the detail text; exactly the limit gives 201) | F | Pass |
| AC1: heatmap (default 365 days, max 3660, reversed range) | `the heatmap lists the dates with entries and their mood`; edge: `the heatmap accepts exactly 3660 days and rejects 3661...`, `the heatmap defaults the start to 364 days before the given end...` | F | Pass |
| AC1: on this day (earlier years, 28/29 Feb rule) | `on this day lists the same day...`, `on 28 February of a common year, entries of 29 February count`; edge: `on 29 February only 29 February counts...` | F | Pass |
| AC1: list / cursor / tag filter | `the list is newest first, filtered by range and tag, and paged`; edge: `malformed cursors, positions and page sizes are rejected with 400`, `a tag-filtered list pages with a cursor and leaves out deleted entries` | F | Pass |
| AC2: resolver JOURNAL_ENTRY, search, links | `entries resolve, are searchable and sync their inline links`; edge: `links follow body changes and stay when only mood or tags change` | F | Pass |
| AC2: trash (delete, restore conflict, purge, events) | `a deleted entry goes to the Trash, its date can be written again, a restore then conflicts`; edge: `delete, restore and purge announce change events...`, `a purge removes the entry...`, `an active entry cannot be purged or restored...` | F | Pass |
| CR-2: a Trash restore racing a PUT for the same date -> 409, not 500 | `a Trash restore that loses against a concurrent create of its date is a 409, the winner stays the only active entry`. A delegating repository answers the restore's pre-check and then commits a competing active entry before the restore's flush. The test checks for a `ConflictException` (409), one active row, the restored entry still in the Trash and not indexed. JaCoCo confirms the `catch (DataIntegrityViolationException)` branch runs | F | Pass |
| Assumption 5: one active entry per date (partial unique index), mood check | `the schema enforces the mood range and one active entry per date` | F | Pass |
| AC3: `./gradlew test --tests 'ch.sonensei.cockpit.journal.entries.*'` passes (Testcontainers Postgres) | All 30 tests in 3 classes | F | Pass (30 tests, 0 failures, 0 skipped) |
| AC4: openapi regenerated, `OpenApiContractTest` passes without the flag | `OpenApiContractTest` | Not run in isolation by this run | Not run: the parent records this criterion. The full server suite (below) crashed before reaching it, and it is not part of the task test filter |
| Project convention: migrations in `db/migration/<module>/V..__<module>_<desc>.sql` | `core.persistence.MigrationConventionTests` (2 existing tests) | `cd server && ./gradlew check` | **Fail**, see TW-1 |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --rerun --tests 'ch.sonensei.cockpit.journal.entries.*' jacocoTestReport`

Result:

Pass: BUILD SUCCESSFUL. JournalEntryWebTests 11/11, JournalEntryEdgeCaseWebTests 18/18, JournalImageMaxSizeWebTests 1/1, 0 failures, 0 skipped. ktlintCheck passed.

## Full test execution

Command:

`cd server && ./gradlew check` (run twice: once as part of `sh scripts/check.sh`, 01:38-01:43, and once on its own, 01:44-01:49 +02:00)

Result:

Fail. Both runs: "2163 tests completed, 2 failed, 1 skipped", followed by `Could not complete execution for Gradle Test Executor 1. > Java heap space` (test JVM `maxHeapSize = "1g"`).
- The 2 failures are `MigrationConventionTests`. They come from this task's migration path (TW-1).
- The heap crash happened in the `files.*` test classes (they are the last group without result XMLs). That is before any `journal.*`, `mail.*`, `notes.*` or `support.*` class ran. The journal test classes share their Spring context configuration with existing classes (`NoteEdgeCaseWebTests`, `NoteImageMaxSizeWebTests`), so they add no new cached contexts. This run did not determine whether the crash also happens at the base commit (that would need a second checkout). It is reported as a suite or environment problem for the parent, not as a defect of this task.

Web and Android parts were not reached, because `scripts/check.sh` stops at the server step.

## .taskcheck

Result:

Fail. `sh scripts/check.sh`: the TFP gate tests pass (20 tests OK). The server step fails with the 2 `MigrationConventionTests` failures (TW-1) and the test JVM heap crash described above.

## Bugs found

### TW-1: The journal entries migration violates the module migration layout and breaks MigrationConventionTests

Finding ID:

`TW-1`

Test:

`ch.sonensei.cockpit.core.persistence.MigrationConventionTests.every migration lives in a module folder and is named V yyyyMMddHHmm __ module _ desc` and `MigrationConventionTests.the core pg_trgm migration has the lowest version` (an existing legitimate test; it was not modified)

Production source:

`server/src/main/resources/db/migration/journal/entries/V202610220130__journal_entries.sql`

Requirement:

AGENTS.md project notes and docs/ARCHITECTURE.md "Persistence conventions": new migrations go to `server/src/main/resources/db/migration/<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql`. The existing journal migrations follow this rule: `journal/V202610211930__journal_habits.sql`, `journal/V202610212300__journal_habits_reminder_id.sql`.

Expected behavior:

The migration sits directly in the module folder: `db/migration/journal/V202610220130__journal_entries.sql`. Both convention tests pass.

Actual behavior:

The migration sits one level deeper, in `journal/entries/`. Test 1 fails: `journal/entries/V202610220130__journal_entries.sql: Collection should have size 2 but has size 3`. Test 2 fails with a `NullPointerException` at `MigrationConventionTests.kt:46`, because `substringAfter("/")` yields `entries/V...sql`, which does not match the name pattern. Flyway still applies the file, because it scans recursively, so the journal tests pass. But `.taskcheck` (`./gradlew check`) fails, so `tasks.py done` will refuse.

Probable cause: the task's `touches` list names `server/src/main/resources/db/migration/journal/entries`. The fix moves the file to `db/migration/journal/`, which is outside that `touches` entry. The parent decides how to handle that. Flyway identifies a migration by version and checksum, not by folder, so a database that already applied V202610220130 is not affected by the move.

Severity:

High (it blocks `.taskcheck` and so task completion; it is a project-convention violation, not a runtime defect)

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: verified (focused run on a stable snapshot. The whole-suite coverage run was not possible because of the heap crash described above.)

Metric: whole-file coverage of task-changed production files (JaCoCo counters from the focused journal test run only).
Changed-line coverage: not measured separately. Every scoped Kotlin file was added by this task, so whole-file coverage equals coverage of the task's added lines for these files.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `journal-api`; base `9e8b3aa49d8a701d5c1f6415400bc4abb862e23a`; HEAD `79f6b1697a31559519ce5b839a934d4c1ee53cae`; the parent named the task commits 11749498, 92e1f03a and 79f6b169; the file scope is `git diff --name-status 9e8b3aa4..HEAD -- server/src/main`, which is all `A` (added) under `journal/entries` and matches the task `touches`.
- Source/test snapshot identifier (including uncommitted inputs): production code = HEAD 79f6b169 with no uncommitted production changes (`git diff` empty). Untracked tests, sha256: `JournalEntryEdgeCaseWebTests.kt` 8bca1a41be95a5f6bb317cadec99c651b1598906a604e9a7bd711a5e57edb7b9, `JournalImageMaxSizeWebTests.kt` c79b13af2b0099f090a2f15eda35ebbb5d67a9a1668c4291f02f5847fb80ee44. Committed `JournalEntryWebTests.kt` c1c73cc4f810b4da39f5c975ebc0cff7b2413d34c02e51bcd462969d7393f730.
- Exact included files: the 8 Kotlin files below. Excluded: `V202610220130__journal_entries.sql`, because SQL is not instrumented by JaCoCo (its constraints are exercised by `the schema enforces the mood range and one active entry per date`). Also excluded: `api/openapi.yaml` and `web/src/app/api/generated/**`, which are generated. No files were renamed or deleted.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin (default plugin version; `build.gradle.kts` does not pin one); `./gradlew ktlintCheck test --rerun --tests 'ch.sonensei.cockpit.journal.entries.*' jacocoTestReport`; working directory `server/`; selection = the `ch.sonensei.cockpit.journal.entries.*` tests.
- Run start/completion time and exit status: 2026-10-22T01:50:09+02:00 to 01:50:42+02:00, BUILD SUCCESSFUL (exit 0).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (mtime 2026-10-22 01:50:42 +0200, sha256 a99e23f0127bf8b0bd40659ea061bdf0a9a8e6ff3fea82ba1494a58c2281562f). Test result XMLs carry timestamps from 23:50:13Z to 23:50:36Z.
- Evidence outputs are fresh and inputs stayed unchanged: `test --rerun` forced execution. The report mtime matches the run. The test file hashes were taken after the run and match the final files. No production file changed during the run.
- Final run after latest test additions and production fixes: yes. It ran after the last test change (the removal of `JournalEntryMigrationTests.kt`) on HEAD 79f6b169, which includes the CR-2 fix. TW-1 is not fixed yet. Its fix (moving a SQL file) does not change Kotlin coverage, but a fresh run is still needed after it.

Values below use covered/total (percentage); zero-denominator metrics are N/A. "Statements" = JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/api/JournalEntryController.kt` | 408/409 (99.8%) | 39/42 (92.9%) | 14/14 (100.0%) | 46/46 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/api/JournalEntryDtos.kt` | 277/295 (93.9%) | 2/4 (50.0%) | 40/40 (100.0%) | 63/63 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/api/JournalWebSupport.kt` | 116/118 (98.3%) | 13/14 (92.9%) | 1/1 (100.0%) | 11/11 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/domain/JournalEntry.kt` | 111/117 (94.9%) | N/A (0/0) | 18/19 (94.7%) | 19/19 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/domain/JournalEntryService.kt` | 933/933 (100.0%) | 89/90 (98.9%) | 21/21 (100.0%) | 99/99 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/infra/JournalEntryItemResolver.kt` | 37/37 (100.0%) | 2/2 (100.0%) | 3/3 (100.0%) | 12/12 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/infra/JournalEntryRepository.kt` | N/A (0/0, interface) | N/A | N/A | N/A |
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/infra/JournalEntryTrashContributor.kt` | 133/142 (93.7%) | 15/16 (93.8%) | 5/5 (100.0%) | 28/28 (100.0%) |
| **Aggregate (summed counters, 8 measured files; SQL migration excluded, no unmeasured Kotlin files)** | 2015/2051 (98.2%) | 160/168 (95.2%) | 102/103 (99.0%) | 278/278 (100.0%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `JournalEntryTrashContributor.kt:30` (1/2 branches) | `checkNotNull(it.deletedAt)` in the Trash list: defensive, because the query returns only deleted rows | Justified gap: not reachable through the API |
| `JournalEntryTrashContributor.kt:42-44` (restore race catch, CR-2) | 409 instead of 500 when a PUT wins the race | Covered by the new restore race test (lines 28/28) |
| `JournalEntryService.kt:131-136` (create race catch, CR-1 a) | 409 on a concurrent create | Covered (`a concurrent create of the same date is a conflict...`) |
| `JournalEntryService.kt:83` (5/6 branches) | `list`: range check `to < from` with one bound missing | Non-material: both-bounds and no-bounds paths are tested |
| `JournalEntryController.kt:115` (5/6) | A blank `If-Match` on a missing entry is treated as absent | Non-material edge of the header parsing; the non-blank (412) and absent (201) paths are tested |
| `JournalEntryController.kt:213` (10/12) | `originalFilename == null` fallback (MockMvc always sends a name) | Non-material: the blank-name -> `image` fallback is tested |
| `JournalEntryDtos.kt:38-39` (2/4) | `checkNotNull(createdAt/updatedAt)` on a persisted entity | Justified gap: defensive, unreachable |
| `JournalWebSupport.kt:29` (1/2) | Jackson error without a property path -> field `body` | Non-material: the field-path case (`"tags":"walk"`) is tested |
| `JournalEntry.kt:36,44` (1 method) | JPA no-arg constructor / default arguments of `JournalEntryContent` | Justified gap: framework or generated code |

## Unable to test

- AC4 (`OpenApiContractTest` without the flag) was not run on its own by this run. The full server suite crashed (Java heap space) before reaching it. The parent should run `cd server && ./gradlew test --tests '*OpenApiContractTest'` and record the result. Follow-up: parent.
- A full server suite and `.taskcheck` pass could not be shown, because of TW-1 and the test JVM heap crash in the `files.*` classes. This run did not check whether the heap crash also happens without this task's changes. Follow-up: parent (fix TW-1, then re-run `sh scripts/check.sh`; if the heap crash persists, find out whether it is pre-existing).
- CR-1 (a) is exercised at the service level, not through HTTP. The service throws the `ConflictException`; the existing `ProblemDetailsAdvice` maps every `ApiException` to Problem Details, and that mapping is covered by `ApiConventionsWebTests`. A real two-request HTTP race cannot be triggered deterministically through MockMvc. No follow-up needed.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.

All CR-1 gaps (a, b, c) and the CR-2 restore race are covered, and the focused journal suite passes (30/30). TW-1 (the migration sits in `journal/entries/` instead of `journal/`) fails the existing `MigrationConventionTests` and blocks `.taskcheck`. A test JVM heap crash in the full server suite (in the `files.*` classes, before any journal test runs) also needs the parent's attention.
