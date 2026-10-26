# Test Report

Date: 2026-10-26

Task: perf-tuning - Performance baseline and tuning

Scope: final revision, HEAD `acaab5f01c363ff45c08b7b14df342d86f82cf2c` on branch `main`, base `d7388b75900291fb47a101514794598f367571eb`. Task-owned commits: `6c5f6a55cb9b2e8825d847bc3fe86b0b2cfdb4e8` (baseline test, index, search tuning), `961a6756188bc88dced82b169c97cce6caf1c3f1` (docs/perf.md), `ca0d11ce2ac1afa116526d50ca62e968e3eba564` (test-writer tests), `acaab5f01c363ff45c08b7b14df342d86f82cf2c` (fixes: deterministic seed ids, per-account thread key, final results). `git diff d7388b759..HEAD -- server docs` also contains the pdf-fulltext task (`files/fulltext/*`, `FilesService.kt`, `V202610261300__files_fulltext.sql`, `build.gradle.kts`, test `application.properties`, one ARCHITECTURE.md line), merged to main as `46b2f4a9c` (done: pdf-fulltext by dell26/agent-2) between the base and the task commits; those paths are excluded as not owned by this task. Task-owned paths were established with `git diff 46b2f4a9c..acaab5f01 -- server docs`. Working tree clean at the time of writing (`git status --short` empty).

Requirements:
- `tasks/perf-tuning.md` acceptance criterion 1 and its four recorded assumptions
- `docs/ARCHITECTURE.md` (test commands, "Coverage"), `AGENTS.md`
- Ranking contract in the KDoc of `SearchDocumentRepository.fullText` (boost 2 at `now`, 1.5 after 30 days, towards 1)
- `reviews/perf-tuning/fixes.md` (changes since the first test pass)

## Test files changed

In this second pass: none. Tests from the first pass, committed in `ca0d11ce2`:

- `server/src/test/kotlin/ch/sonensei/cockpit/core/search/SearchEdgeCaseTests.kt` (2 tests + 2 private helpers)
- `server/src/test/kotlin/ch/sonensei/cockpit/perf/PerfPercentileTests.kt` (new, 5 tests)

Task test-support code changed by the fixer since the first pass (not written by the test-writer): `PerfSeed.kt`, `PerfBaselineTests.kt` (KDoc only).

## Tests added or updated

Count: 0 in this pass (7 from the first pass, committed).

Why no further tests were needed for the final code:
- The only production Kotlin change, `SearchDocumentRepository.fullText` (lines 56-66: `materialized` CTE, `date_part` boost), is unchanged since the first pass (`git diff ca0d11ce2..acaab5f01` touches no file under `server/src/main`). Its behavior is pinned by the first-pass tests (fixed-`now` recency ordering, clamp for `updated_at` after `now`) plus the existing search suite.
- The migration `V202610261500__perf_mail_unified_sort.sql` is also unchanged; Flyway applies it in every Spring test context and its effect is measured by the `PerfBaselineTests` unified-inbox endpoints.
- The fixes changed only seed data (md5-derived ids instead of `gen_random_uuid()`, thread key `'thread-' || g % 2 || '-' || g / 6` so every 3-message conversation stays within the account chosen by `g % 2`). This is test fixture code; it is exercised by `PerfBaselineTests` "seeded volumes match the baseline" (row counts) and by every measured endpoint. A separate test asserting id determinism across two seedings would need a second 67k-row seed in the slow `external` suite and protects no product behavior, so none was added. By inspection: md5 of a distinct per-table prefix and row number gives distinct ids; for a fixed `g / 6`, the parities 0 and 1 each hold exactly 3 values of `g`, so conversations have 3 messages within one account.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Seed 10k tasks, 2k notes, 50k mail headers, 5k events | `PerfBaselineTests` "seeded volumes match the baseline" | `cd server && ./gradlew ktlintCheck integrationTest --tests '*PerfBaselineTests' --rerun` (run by parent on HEAD acaab5f01) | Pass (parent-reported: BUILD SUCCESSFUL, 2 tests, 0 failures) |
| p95 of list/search endpoints below 200 ms in a repeatable `external` test | `PerfBaselineTests` "p95 of list and search endpoints is below the limit" | same (run by parent on HEAD) | Pass (parent-reported: all 24 endpoints p95 below 200 ms; worst unified inbox threaded 96.5 ms, search common word 66.5 ms) |
| p95 computed correctly (nearest rank) | `PerfPercentileTests` (5 tests) | `cd server && ./gradlew test --tests '*Search*' --tests '*PerfPercentileTests' jacocoTestReport` (run by parent on HEAD) | Pass (parent-reported: 112 tests, 0 failures, includes these 5) |
| Indexes added as migrations | `V202610261500__perf_mail_unified_sort.sql` applied by Flyway in every Spring test context (incl. `MigrationConventionTests` per the task's assumption); effect measured by `PerfBaselineTests` "mail: unified inbox" endpoints | parent's integrationTest run above | Pass (parent-reported) |
| Search tuning keeps the ranking formula: recency ordering | existing `SearchServiceTests` "among equal matches the more recent ranks first", `SearchEdgeCaseTests` "upsert refreshes recency", "recency does not let an old title hit fall behind a fresh body hit"; first-pass `SearchEdgeCaseTests` "full-text ranking at a fixed now prefers the more recent of equal matches" | `cd server && ./gradlew test --tests '*Search*' --tests '*PerfPercentileTests' jacocoTestReport` (parent, HEAD) | Pass (parent-reported, within the 112 tests) |
| Search tuning keeps the ranking formula: clamp for `updated_at` after `now` | first-pass `SearchEdgeCaseTests` "a document changed after now gets the full boost, neither a vanishing nor an unbounded one" | same | Pass (parent-reported, within the 112 tests) |
| Repeatable test (same data every run) | `PerfSeed` deterministic ids and `setseed`; inspected, exercised by `PerfBaselineTests` | parent's integrationTest run above | Pass for the run itself (parent-reported); determinism across runs checked by inspection only, see "Unable to test" |
| Results in docs/perf.md | documentation, inspected only | n/a | Not testable (documentation) |

## Focused test execution

Command (run by the parent on HEAD acaab5f01; Gradle is blocked for the test-writer in this environment):

`cd server && ./gradlew test --tests '*Search*' --tests '*PerfPercentileTests' jacocoTestReport`

Result:

Pass (parent-reported: 112 tests, 0 failures).

## Full test execution

Command (run by the parent on HEAD):

`cd server && ./gradlew ktlintCheck integrationTest --tests '*PerfBaselineTests' --rerun`

Result:

Pass (parent-reported: BUILD SUCCESSFUL, 2 tests, 0 failures). The full `.taskcheck` was not reported to the test-writer; see below.

## .taskcheck

Result:

Not run by the test-writer (Gradle blocked for sub-agents). The parent runs `sh scripts/check.sh` before `done`.

## Bugs found

None. No production defect was found; the fixes since the first pass touch only test fixture code and documentation.

## Coverage

Status: verified (parent-run JaCoCo on HEAD acaab5f01, Search* and PerfPercentileTests subset; numbers as reported by the parent, the test-writer could not read the report itself because Gradle and report generation run outside its permissions).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured by the provider; by inspection, the changed lines 56-66 are all inside the single expression body of `fullText`, which the passing first-pass tests call directly, so they execute whenever `fullText` runs.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: perf-tuning; base d7388b75900291fb47a101514794598f367571eb; HEAD acaab5f01c363ff45c08b7b14df342d86f82cf2c; task commits 6c5f6a55c, 961a67561, ca0d11ce2, acaab5f01 (from `git log d7388b759..HEAD`, parent-supplied base); file scope from `git diff 46b2f4a9c..acaab5f01 -- server docs`.
- Source/test snapshot identifier (including uncommitted inputs): commit acaab5f01c363ff45c08b7b14df342d86f82cf2c, clean working tree, no uncommitted inputs.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/search/infra/SearchDocumentRepository.kt`. Excluded: `server/src/main/resources/db/migration/perf/V202610261500__perf_mail_unified_sort.sql` (SQL, not instrumented by JaCoCo; applied by Flyway in every Spring test and exercised by PerfBaselineTests); `docs/perf.md`, `docs/ARCHITECTURE.md` (documentation); `PerfBaselineTests.kt`, `PerfSeed.kt`, `PerfPercentileTests.kt`, `SearchEdgeCaseTests.kt` (test code); pdf-fulltext paths (other task, see Scope). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via the project's Gradle `jacocoTestReport`; `./gradlew test --tests '*Search*' --tests '*PerfPercentileTests' jacocoTestReport` in `server/`; selection Search* and PerfPercentileTests (112 tests). Provider version not reported to the test-writer.
- Run start/completion time and exit status: run by the parent on 2026-10-26 on HEAD acaab5f01; exit status success (0 failures); exact times not reported to the test-writer.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` (default project path, Git-ignored); hash not reported to the test-writer.
- Evidence outputs are fresh and inputs stayed unchanged: the parent ran on HEAD acaab5f01, which is the commit this report describes; the working tree is clean.
- Final run after latest test additions and production fixes: yes - no tests were added in this pass and the production code has not changed since ca0d11ce2.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements (JaCoCo instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/search/infra/SearchDocumentRepository.kt` | 196/198 (99.0 %) | not reported | 8/9 (88.9 %) | 27/28 (96.4 %) |

Aggregate over the measured scope equals the single file above. Unmeasured: the SQL migration (not instrumentable by JaCoCo).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `SearchDocumentRepository.kt`: 1 line, 1 method, 2 instructions (exact location not reported to the test-writer) | Not task-changed code: `fullText` (the changed lines 56-66) is one expression body executed by the passing first-pass tests, so the missed method must be one of the pre-existing members (`upsert`, `delete`, `similarTitles`, its no-op row callback, or the `HIT` mapper), all of which pre-date this task and are covered by the full suite rather than the Search* subset. | No test added; outside the task's changed behavior. Justified gap for the subset run. |
| `V202610261500__perf_mail_unified_sort.sql` | Index used by the unified Inbox list | Not measurable by JaCoCo; applied in every Spring test, effect measured by PerfBaselineTests (worst unified inbox p95 96.5 ms). |

## Unable to test

- Running any Gradle task (tests, ktlint, JaCoCo, `.taskcheck`) is blocked for the test-writer; every result above was run and reported by the parent. Follow-up: parent runs `.taskcheck` before `done`.
- Cross-run determinism of the seed (same ids and assignments on two runs): checked by inspection of `PerfSeed.kt` only; an automated check would need a second full seed in the slow external suite and protects fixture code, not product behavior. No follow-up needed.
- Whether the planner chooses `mail_message_sort`, and that `materialized` avoids re-evaluating `to_tsquery` per row: planner/performance properties, measured by PerfBaselineTests timings rather than functional tests. No follow-up needed.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. No additional tests were necessary for the final revision: the production code is unchanged since the first pass, whose 7 tests pass on HEAD acaab5f01 (parent-reported: 112 tests, 0 failures; PerfBaselineTests 2 tests, 0 failures, all 24 endpoints p95 below 200 ms).
