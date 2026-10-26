# Test Report

Date: 2026-10-26

Task: perf-tuning - Performance baseline and tuning

Scope: `git diff d7388b75900291fb47a101514794598f367571eb..bd0d0838a289f37a947bfb79a6a509cfad7ad760` (task commits 67b1e02c0 and bd0d0838a on HEAD; the parent named them 541bc2f11 / 64b25ffb2 before a rebase, same content), plus the uncommitted test changes listed below. Branch `main`.

Requirements:
- `tasks/perf-tuning.md` acceptance criterion 1 and its three recorded assumptions
- `docs/ARCHITECTURE.md` (test commands, "Coverage"), `AGENTS.md`
- Existing ranking contract in the KDoc of `SearchDocumentRepository.fullText` (boost 2 at `now`, 1.5 after 30 days, towards 1)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/search/SearchEdgeCaseTests.kt` (2 tests + 2 private helpers added, 3 imports)
- `server/src/test/kotlin/ch/sonensei/cockpit/perf/PerfPercentileTests.kt` (new, 5 tests)

## Tests added or updated

Count: 7

Why these were warranted:
- The task rewrote the recency expression in `SearchDocumentRepository.fullText` (`extract` to `date_part`, CTE `materialized`). Existing tests cover recency ordering only through `SearchService` with the wall clock: `SearchServiceTests` "among equal matches the more recent ranks first" (0 vs 60 days), `SearchEdgeCaseTests` "upsert refreshes recency" (30 vs 60 days) and "recency does not let an old title hit fall behind a fresh body hit" (bounded boost). None exercised the `greatest(..., 0)` clamp for a document whose `updated_at` lies after `now`; dropping or misplacing that clamp in the rewritten expression would go unnoticed. The new tests call `fullText` directly with a fixed `now`.
- `PerfBaselineTests.percentile` decides the pass/fail of the acceptance criterion's p95 assertion, but only ran inside the slow external test. A plain unit test pins the nearest-rank semantics (p95 of 50 samples is the 48th value).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Seed 10k tasks, 2k notes, 50k mail headers, 5k events | `PerfBaselineTests` "seeded volumes match the baseline" | `cd server && ./gradlew integrationTest --tests '*PerfBaselineTests' --rerun` (run by parent) | Pass (parent-reported: 2 tests, 0 failures) |
| p95 of list/search endpoints below 200 ms in a repeatable `external` test | `PerfBaselineTests` "p95 of list and search endpoints is below the limit" | same (run by parent) | Pass (parent-reported: 24 endpoints, worst p95 118.4 ms search prefix) |
| p95 computed correctly (nearest rank) | `PerfPercentileTests` (5 tests) | `cd server && ./gradlew test --tests '*PerfPercentileTests'` | Not run: Gradle is blocked for the test-writer; parent must run |
| Indexes added as migrations | `V202610261500__perf_mail_unified_sort.sql` applied by Flyway in every Spring test context; effect measured by `PerfBaselineTests` "mail: unified inbox" endpoints | parent's integrationTest run above | Pass (parent-reported) |
| Search tuning keeps the ranking formula: recency ordering | existing `SearchServiceTests` "among equal matches the more recent ranks first", `SearchEdgeCaseTests` "upsert refreshes recency", "recency does not let an old title hit fall behind a fresh body hit"; new "full-text ranking at a fixed now prefers the more recent of equal matches" | existing: `cd server && ./gradlew test --tests '*Search*'` (parent: 105 tests, 0 failures, before the new tests); new: same command | existing Pass (parent-reported); new Not run: Gradle blocked for test-writer |
| Search tuning keeps the ranking formula: clamp for `updated_at` after `now` | new `SearchEdgeCaseTests` "a document changed after now gets the full boost, neither a vanishing nor an unbounded one" | `cd server && ./gradlew test --tests '*SearchEdgeCaseTests'` | Not run: Gradle blocked for test-writer; parent must run |
| Results in docs/perf.md | documentation, inspected only | n/a | Not testable (documentation) |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*SearchEdgeCaseTests' --tests '*PerfPercentileTests'` and `cd server && ./gradlew ktlintCheck`

Result:

Not run by the test-writer (Gradle is blocked for sub-agents in this environment). The parent worker must run these; the result belongs in `quality.json`. Expected: all tests pass (the clamp test discriminates: without `greatest(..., 0)` the 60-days-in-future document gets boost 0 and ranks last, the 29-days-in-future body hit gets boost about 31 and outranks the fresh title hit, title weight A = 1.0 vs body weight B = 0.4).

Parent-reported runs before the new tests: `./gradlew test --tests '*Search*'` -> 105 tests, 0 failures; `./gradlew integrationTest --tests '*PerfBaselineTests' --rerun` -> 2 tests, 0 failures; `./gradlew ktlintCheck` -> pass.

## Full test execution

Command:

`sh scripts/check.sh` (`.taskcheck`)

Result:

Not run by the test-writer (Gradle blocked); the parent runs it before `done`.

## .taskcheck

Result:

Not run by the test-writer (see above).

## Bugs found

None. No production defect was found by reading the change; the rewritten expression is algebraically identical to the previous one (`date_part('epoch', interval)` returns double precision, `extract` numeric; ordering is unaffected at the tested resolutions).

## Coverage

Status: unavailable to the test-writer - JaCoCo runs through Gradle, which is blocked for sub-agents here. Task-scoped coverage for `SearchDocumentRepository.kt` must come from the parent's run of `cd server && ./gradlew test jacocoTestReport` (report `server/build/reports/jacoco/test/jacocoTestReport.xml`, sourcefile `SearchDocumentRepository.kt` in package `ch/sonensei/cockpit/core/search/infra`).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: perf-tuning; base d7388b75900291fb47a101514794598f367571eb; HEAD bd0d0838a289f37a947bfb79a6a509cfad7ad760; task commits 67b1e02c0, bd0d0838a (parent-supplied as 541bc2f11, 64b25ffb2 before rebase), file list from `git diff --stat d7388b759..HEAD`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD bd0d0838a plus `SearchEdgeCaseTests.kt` sha256 877292795f247c424cd3224e3ecb1d29be9837bffcf2066ccb122b3358d74359 and `PerfPercentileTests.kt` sha256 132f6f11d758725bb56dd2683bfce9aaef649c888296ec2e19b536567306338c.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/core/search/infra/SearchDocumentRepository.kt`. Excluded: `server/src/main/resources/db/migration/perf/V202610261500__perf_mail_unified_sort.sql` (SQL migration, not instrumented by JaCoCo; applied by Flyway in every Spring test); `docs/perf.md`, `docs/ARCHITECTURE.md` (documentation); `PerfBaselineTests.kt`, `PerfSeed.kt` (test code); `tasks/perf-tuning.md` (board).
- Provider/version, command, working directory, and test selection: not run (JaCoCo via Gradle blocked for test-writer).
- Run start/completion time and exit status: not run.
- Generated report paths and report hashes/run identifier: none produced by the test-writer.
- Evidence outputs are fresh and inputs stayed unchanged: not applicable, no run.
- Final run after latest test additions and production fixes: no - no coverage run was possible for the test-writer.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/search/infra/SearchDocumentRepository.kt` | not measured | not measured | not measured | not measured |

No aggregate is reported; the only scoped file is unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `SearchDocumentRepository.kt:49-67` (`fullText`, the changed code) | ranking formula and materialized tsquery | Not measured by JaCoCo here; reached by every full-text search test (`SearchServiceTests`, `SearchEdgeCaseTests`, `SearchWebTests`, mail search tests) and directly by the 2 new tests. The changed lines are SQL text inside one Kotlin call, so line coverage cannot distinguish formula branches; behavior is pinned by the ordering tests instead. |

Follow-up: the parent runs `cd server && ./gradlew test jacocoTestReport` and reads the `SearchDocumentRepository.kt` counters (INSTRUCTION, BRANCH, LINE, METHOD) from the XML report.

## Unable to test

- Running any Gradle task (tests, ktlint, JaCoCo, `.taskcheck`): blocked for the test-writer; the parent runs them. Follow-up: yes, the parent must run the focused commands above before `done`.
- Whether `mail_message_sort` is actually chosen by the planner: only observable through EXPLAIN or timing; an EXPLAIN-based unit test would be brittle against planner statistics, so it is left to `PerfBaselineTests` (timing). No follow-up needed.
- That `materialized` avoids re-evaluating `to_tsquery` per row in a generic plan: a planner/performance property, measured by `PerfBaselineTests` "search:" endpoints, not functionally testable. No follow-up needed.

## Summary

Tests added; no production defects found by inspection. The 7 new tests and task-scoped coverage have not been run by the test-writer because Gradle is blocked for sub-agents. The parent must run `cd server && ./gradlew ktlintCheck test --tests '*SearchEdgeCaseTests' --tests '*PerfPercentileTests'` and `cd server && ./gradlew test jacocoTestReport` before this report can count as validated.
