# Test Report

Date: 2026-10-22

Task: journal-api - Journal API (round 2, on the final code)

Scope: `git diff caeb0610c967f37ae11c951aef0116f775682c96..10068a31968788c5e7dccd645dc15a483801ea72` on `main`. The task commits are 690699257 (implementation), ad724b1c4 (web tests, openapi, web client), dee2a7470 (CR-2 fix: restore race -> 409), b97a4cc6c (edge case and image size tests, CR-1), 0a9446495 (TW-1: migration moved into the journal module folder), f28dc7d09 (reports), the merge of main 75c6480ab, and 10068a319 (OpenAPI regenerated after the merge). There are no uncommitted changes (`git status --short` is empty).

Requirements:
- `tasks/journal-api.md`: acceptance criteria 1-4 and the five recorded assumptions
- `reviews/journal-api/review.md` (CR-1, CR-2) and `reviews/journal-api/fixes.md`; TW-1 from the round 1 test report
- SPEC.md §8.5; docs/ARCHITECTURE.md "Coverage"; AGENTS.md project notes (migration layout)

## Test files changed

None in this round. The three journal test files are byte-identical to the ones round 1 reviewed and ran (sha256 below).

## Tests added or updated

Count: 0. No meaningful gap was found (see "Coverage"). The round 1 report predated the TW-1 migration move, and its scope listed the CR-2 fix under an older hash. This round checked the final code against the existing tests:
- CR-2 (`JournalEntryTrashContributor.restore`, the `catch (DataIntegrityViolationException)` -> `ConflictException`): covered by `a Trash restore that loses against a concurrent create of its date is a 409, the winner stays the only active entry`. JaCoCo shows lines 37-50 fully covered on HEAD.
- TW-1: the migration is now `server/src/main/resources/db/migration/journal/V202610220130__journal_entries.sql`, next to `V202610211930__journal_habits.sql` and `V202610212300__journal_habits_reminder_id.sql`. That matches the `<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql` convention. Flyway applies it in the journal tests (they pass against Testcontainers Postgres). The authoritative check for the layout is the existing `core.persistence.MigrationConventionTests`. The parent's runs in this round did not select it (see "Unable to test").

## Requirement coverage

Command F (run by the parent on HEAD 10068a319): `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.journal.entries.*' --tests '*OpenApiContractTest'` -> BUILD SUCCESSFUL. JournalEntryWebTests 11, JournalEntryEdgeCaseWebTests 18, JournalImageMaxSizeWebTests 1, OpenApiContractTest 1; 0 failures, 0 skipped (Postgres via Testcontainers).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: upsert by date (201 create / 200 replace with If-Match, 428/412, future date rejected, time zone) | `JournalEntryWebTests`: `a PUT creates the entry of a date, a second one replaces it with If-Match`, `creating with an If-Match is refused, a missing date is 404`, `today follows the time zone setting`; edge: `today's date is accepted, a replace keeps id and createdAt and sends the new ETag`, `a replace that loses against a concurrent change fails with optimistic locking, a stale ETag is 412` | F | Pass |
| AC1 / CR-1 (a): a concurrent create of the same date -> 409, one active row | `a concurrent create of the same date is a conflict and stores nothing of the loser` | F | Pass |
| AC1: Markdown body, mood 1-5, tags normalized (max 50x50, NUL, empty, stored spelling kept, CR-1 c) | `invalid entries are rejected`; edge: `tag limits - 50 tags of 50 characters are accepted, more or longer are rejected`, `the body may be 100000 characters without NUL, mood 1 to 5 or null`, `tags keep their stored spelling on replace, duplicates collapse to the first spelling` | F | Pass |
| AC1: images in /Attachments/Journal, non-image 400, oversized 413 (CR-1 b) | `an image is stored in Attachments-Journal`; edge: `an image without a usable content type gets its mime from the name, one without a name is called image`, `a purge removes the entry with its tags and links, its images stay in the files`; `JournalImageMaxSizeWebTests.an image above the maximum size is a 413 Problem Details, one at the maximum is stored` | F | Pass |
| AC1: heatmap (default 365 days, max 3660, reversed range) | `the heatmap lists the dates with entries and their mood`; edge: `the heatmap accepts exactly 3660 days and rejects 3661 or a reversed range`, `the heatmap defaults the start to 364 days before the given end and leaves out deleted entries` | F | Pass |
| AC1: on this day (earlier years, 28/29 February rule) | `on this day lists the same day of earlier years, newest first`, `on 28 February of a common year, entries of 29 February count`; edge: `on 29 February only 29 February counts, the asked year and deleted entries are left out` | F | Pass |
| AC1: list / cursor / tag filter | `the list is newest first, filtered by range and tag, and paged`; edge: `malformed cursors, positions and page sizes are rejected with 400`, `a tag-filtered list pages with a cursor and leaves out deleted entries` | F | Pass |
| AC2: resolver JOURNAL_ENTRY, search, links | `entries resolve, are searchable and sync their inline links`; edge: `links follow body changes and stay when only mood or tags change` | F | Pass |
| AC2: trash (delete, restore, restore conflict, purge, events) | `a deleted entry goes to the Trash, its date can be written again, a restore then conflicts`; edge: `delete, restore and purge announce change events, a restored entry is searchable by its body again`, `a purge removes the entry with its tags and links, its images stay in the files`, `an active entry cannot be purged or restored, deleting checks If-Match and existence` | F | Pass |
| AC2 / CR-2: a Trash restore racing a PUT for the same date -> 409, not 500 | `a Trash restore that loses against a concurrent create of its date is a 409, the winner stays the only active entry` | F | Pass |
| Assumption 5: one active entry per date (partial unique index), mood check | `the schema enforces the mood range and one active entry per date` | F | Pass |
| AC3: `./gradlew test --tests 'ch.sonensei.cockpit.journal.entries.*'` passes (Testcontainers Postgres) | all 30 tests in 3 classes | F | Pass (30 tests, 0 failures, 0 skipped) |
| AC4: `api/openapi.yaml` regenerated, `OpenApiContractTest` passes without the flag | `OpenApiContractTest` | F (without `UPDATE_OPENAPI`) | Pass (1 test) |
| TW-1: migration in `db/migration/<module>/` | `core.persistence.MigrationConventionTests` (existing) | Not run in this round | Not run: not selected by the parent's commands. The layout is correct by inspection. The parent should run it, or the full `.taskcheck` |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.journal.entries.*' --tests '*OpenApiContractTest'` (run by the parent on HEAD 10068a319; this sub-agent cannot run Gradle)

Result:

Pass: BUILD SUCCESSFUL, 31 tests, 0 failures, 0 skipped.

## Full test execution

Command:

`cd server && ./gradlew check` / `sh scripts/check.sh`

Result:

Not run in this round, by this sub-agent or by the parent as reported to it. The parent runs `.taskcheck` before `done`. Round 1's full run failed on TW-1, which is now fixed, and on a test JVM heap crash. The memory note says the test heap was raised to 2g on 2026-10-22.

## .taskcheck

Result:

Not run by this sub-agent (Gradle is not permitted to sub-agents). The parent must run `sh scripts/check.sh`. It also covers `MigrationConventionTests` for TW-1.

## Bugs found

None in this round. TW-1 (from round 1) is fixed by 0a9446495, and CR-2 is fixed and covered.

## Coverage

Status: verified (the parent's fresh focused run on HEAD; the report was read and parsed by this sub-agent).

Metric: whole-file coverage of task-changed production files (JaCoCo, focused journal test run only).
Changed-line coverage: not measured separately. Every scoped Kotlin file was added by this task (`git diff --name-status` shows all `A`), so whole-file coverage equals coverage of the task's added lines.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `journal-api`; base `caeb0610c967f37ae11c951aef0116f775682c96` (given by the parent: the claim commit); HEAD `10068a31968788c5e7dccd645dc15a483801ea72`; the task commits are listed in "Scope". The file scope is `git diff --name-status caeb0610c..HEAD -- server`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 10068a319, clean working tree. sha256: `JournalEntryEdgeCaseWebTests.kt` 8bca1a41be95a5f6bb317cadec99c651b1598906a604e9a7bd711a5e57edb7b9, `JournalEntryWebTests.kt` c1c73cc4f810b4da39f5c975ebc0cff7b2413d34c02e51bcd462969d7393f730, `JournalImageMaxSizeWebTests.kt` c79b13af2b0099f090a2f15eda35ebbb5d67a9a1668c4291f02f5847fb80ee44, `V202610220130__journal_entries.sql` 275cd4a2cc03080cc93bb86445cc2b4afdb71cc8db628964b418d383679d2be4.
- Exact included files: the 8 Kotlin files below. Excluded: the SQL migration (JaCoCo does not instrument SQL; its constraints are exercised by `the schema enforces the mood range and one active entry per date`), `api/openapi.yaml` and `web/src/app/api/generated/**` (generated). No files were renamed or deleted in the net diff; the migration move happened inside the task, so the file is new at its final path. JaCoCo also lists an empty `api/Comparisons.kt` source entry with no counters. It is not in the task diff and is not counted.
- Provider/version, command, working directory, and test selection: JaCoCo through the Gradle `jacoco` plugin; `./gradlew test --tests 'ch.sonensei.cockpit.journal.entries.*' jacocoTestReport` in `server/` (run by the parent); selection = `ch.sonensei.cockpit.journal.entries.*`.
- Run start/completion time and exit status: the parent reported BUILD SUCCESSFUL. The report was written at 2026-10-22 02:28:14 +0200, after the HEAD commit (02:26:53 +0200). The parent did not give a start time.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 1956889d4f0153769bb7a42ff8f26a6a8c2a2cd7fb2df54b7a248fcf9c64b71d.
- Evidence outputs are fresh and inputs stayed unchanged: the report postdates HEAD. The working tree is clean, and the test file hashes match. The per-package totals parsed from the report match the parent's figures (domain LINE 118/118, BRANCH 89/90; infra LINE 40/40, BRANCH 17/18; api LINE 120/120, BRANCH 54/60).
- Final run after latest test additions and production fixes: yes (HEAD includes the CR-2 and TW-1 fixes; no tests changed in this round).

Values use covered/total (percentage); zero-denominator metrics are N/A. "Statements" = JaCoCo INSTRUCTION counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/api/JournalEntryController.kt` | 408/409 (99.8%) | 39/42 (92.9%) | 14/14 (100.0%) | 46/46 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/api/JournalEntryDtos.kt` | 277/295 (93.9%) | 2/4 (50.0%) | 40/40 (100.0%) | 63/63 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/api/JournalWebSupport.kt` | 116/118 (98.3%) | 13/14 (92.9%) | 1/1 (100.0%) | 11/11 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/domain/JournalEntry.kt` | 111/117 (94.9%) | N/A (0/0) | 18/19 (94.7%) | 19/19 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/domain/JournalEntryService.kt` | 936/936 (100.0%) | 89/90 (98.9%) | 21/21 (100.0%) | 99/99 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/infra/JournalEntryItemResolver.kt` | 37/37 (100.0%) | 2/2 (100.0%) | 3/3 (100.0%) | 12/12 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/infra/JournalEntryRepository.kt` | N/A (interface, no counters) | N/A | N/A | N/A |
| `server/src/main/kotlin/ch/sonensei/cockpit/journal/entries/infra/JournalEntryTrashContributor.kt` | 133/142 (93.7%) | 15/16 (93.8%) | 5/5 (100.0%) | 28/28 (100.0%) |
| **Aggregate (summed counters, 8 files; SQL migration excluded, no unmeasured Kotlin files)** | 2018/2054 (98.2%) | 160/168 (95.2%) | 102/103 (99.0%) | 278/278 (100.0%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `JournalEntryTrashContributor.kt:30` (1/2 branches) | `checkNotNull(it.deletedAt)` in the Trash list; the query returns only deleted rows | Justified gap: defensive, not reachable through the API |
| `JournalEntryTrashContributor.kt:37-50` (CR-2 restore race) | 409 instead of 500 when a PUT wins the race | Covered (`a Trash restore that loses against a concurrent create...`) |
| `JournalEntryService.kt:83` (5/6 branches) | `list`: `to < from` only checked when both bounds are given; the missed branch is `from` given, `to` missing | Non-material: both-bounds (400) and open-range paths are tested |
| `JournalEntryController.kt:115` (5/6) | A blank `If-Match` on a missing entry counts as absent (201) | Non-material header edge; absent (201) and non-blank (412) are tested |
| `JournalEntryController.kt:213` (10/12) | `contentType` null/blank variants of the mime fallback | Non-material: the octet-stream fallback, guessing from the name, and the unguessable -> 400 path are tested |
| `JournalEntryDtos.kt:38-39` (2/4) | `checkNotNull(createdAt/updatedAt)` on a persisted entity | Justified gap: defensive, unreachable |
| `JournalWebSupport.kt:29` (1/2) | A Jackson error without a property path maps to the field `body` | Non-material: the field-path case is tested |
| `JournalEntry.kt:36,44` (1 method, 6 instructions) | JPA no-arg constructor / default arguments | Justified gap: framework/generated code |

No threshold is defined for the task. No acceptance criterion or error path sits in the uncovered code.

## Unable to test

- `MigrationConventionTests` (TW-1 regression guard) was not run in this round. Follow-up: parent runs `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.persistence.MigrationConventionTests'` or `sh scripts/check.sh` and records the result.
- The full server suite and `.taskcheck` were not run in this round (sub-agents cannot run Gradle). Follow-up: parent, before `done`.
- CR-1 (a) and CR-2 are exercised at the service/contributor level, not as real HTTP races. MockMvc cannot trigger two-request races deterministically. The `ConflictException` -> 409 Problem Details mapping is covered by the existing `ApiConventionsWebTests`. No follow-up needed.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found.

On the final code (HEAD 10068a319), the existing 30 journal tests plus `OpenApiContractTest` pass. They cover all four acceptance criteria and the CR-1/CR-2 fixes, so no tests were added. Coverage for the task's Kotlin files: 278/278 lines, 160/168 branches. The remaining branches are defensive or non-material. Still open for the parent: run `MigrationConventionTests` (or `.taskcheck`) to confirm TW-1 on the final layout.
