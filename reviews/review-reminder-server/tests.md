# Test Report

Date: 2026-11-09

Task: review-reminder-server - Weekly review reminder setting and scheduling (server)

Scope: base 4d5fd9777b6a141302f370e1663c84c14f181b28 .. HEAD, server part only, as the parent specified. The parent rebased onto main while this run was going (task commits are now 7122800be and 21908ecd8; HEAD is cf6d3e1aea46f1745e04efd9430ea371beb74d09). `git diff 072dfc092 HEAD -- server/src/main` is empty, so the server production code that was measured is the same as at HEAD. Commit cf6d3e1ae (web item-type glyph/label) arrived after the measurement and is outside this report's server scope: it was not reviewed or tested here.

Requirements:
- tasks/review-reminder-server.md acceptance criteria
- SPEC.md §8.6 (through the task's Design section)
- docs/ARCHITECTURE.md "Coverage"
- parent instructions (what to cover: parse, next occurrence incl. DST/time zone, Spring create/replace/remove/re-arm)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/review/WeeklyReviewScheduleTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/review/WeeklyReviewReminderTests.kt`

## Tests added or updated

Count: 6 added, 1 updated

Added:
- `on the scheduled weekday after the time, the next occurrence is a week later` (also covers Saturday 23:59 -> Sunday 00:00)
- `isValid accepts off and schedules only`
- `setting it through the settings API arms the reminder, an invalid value is a 400 and changes nothing` (PATCH /api/v1/settings with If-Match and CSRF)
- `an unrelated settings change keeps the pending reminder` (the listener ignores keys other than weeklyReviewReminder/timeZone)
- `startup catches up a reminder lost while the server was down` (onStartup)
- `the resolver knows only the one weekly review item` (an unknown id resolves to null)

Updated: `rejects invalid day or time`. The `@ValueSource` line was wrapped because it broke ktlint `max-line-length` (141 > 140) and failed `ktlintTestSourceSetCheck`. Its values did not change.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 setting definition with validation (parse: off, valid, invalid day/time) | `parses off and valid schedules`, `rejects invalid day or time` (12 cases), `rejects non-strings`, `isValid accepts off and schedules only`, `invalid values are rejected with 400`, `setting it through the settings API ...` | `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.review.*' jacocoTestReport` | Pass |
| AC2 next occurrence honours timeZone and DST | `next occurrence is later today, next week, or strictly after now`, `on the scheduled weekday after the time ...`, `next occurrence honours the time zone`, `next occurrence honours DST changes`, `a time in the DST gap moves forward, one in the overlap fires at its first occurrence`, `a time zone change moves the reminder` | same | Pass |
| AC3 pending reminder created/replaced/removed on setting change, re-armed after firing (Spring) | `turning it on creates the reminder, changing it replaces it, off removes it`, `off by default, without a reminder`, `a time zone change moves the reminder`, `re-armed for the next week after it fired, and delivered with its item`, `sync is idempotent and repairs extra or missing pending reminders`, `concurrent syncs leave exactly one pending reminder`, `setting it through the settings API ...`, `an unrelated settings change keeps the pending reminder`, `startup catches up a reminder lost while the server was down`, `the resolver knows only the one weekly review item` | same | Pass |
| AC4 openapi regenerated; `./gradlew check` passes | not tested by this run (the full `check` was not run) | - | Not run: time budget. The parent must run `cd server && ./gradlew check` |

## Focused test execution

Command:

`cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.review.*' jacocoTestReport -q`

Result:

Pass (exit 0). 78 tests, 0 failures, 0 skipped: WeeklyReviewScheduleTests 20, WeeklyReviewReminderTests 11, WeeklyReviewWebTests 9, WeeklyReviewEdgeCaseTests 27, ReviewBackupContributorTests 11. ktlintCheck passes (main and test).

Earlier runs, kept here for the record: the first run failed because the new PATCH test had no CSRF token (403). The test was fixed by adding `with(csrf())`, as SettingsWebTests does. The second run failed only on ktlint, because of the line in the existing test described above.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run (short session budget). The parent should run it before `done`. It is also AC4.

## .taskcheck

Result:

Not run (time budget). The parent runs it before `done`.

## Bugs found

None.

## Coverage

Status: verified for the server files in scope. The measurement comes from the focused review-package test selection, not from the full suite.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured with tooling. A manual check of the JaCoCo line data found that every added line in SettingDefinition.kt (the `Formatted` class, lines 82-89) and ItemType.kt (`WEEKLY_REVIEW`) is covered.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: review-reminder-server. Base 4d5fd9777b6a141302f370e1663c84c14f181b28 (from the parent). Measured on the task code as committed at 072dfc092 (rebased into 7122800be and 21908ecd8 with the same server/src/main content). The file scope is the parent's list plus `git diff --name-only 4d5fd9777..072dfc092 -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): server/src/main = HEAD cf6d3e1aea46f1745e04efd9430ea371beb74d09 (same content as 072dfc092). Uncommitted test files (sha256): WeeklyReviewScheduleTests.kt 3d0eeb80a9a0dcc06b2e613ab6b3ae77197902b9e37b32c4a55e3b012e054ac5, WeeklyReviewReminderTests.kt 55bc118ef913e8e81dd8d5523c051d1a93f3519e3c8fe0bef0d0645e8cf69176.
- Exact included files: review/domain/WeeklyReviewSchedule.kt, review/domain/WeeklyReviewReminders.kt, review/infra/ReviewSettings.kt, review/infra/WeeklyReviewItemResolver.kt, core/settings/domain/SettingDefinition.kt, core/items/domain/ItemType.kt. No files were renamed or deleted. Excluded: api/openapi.yaml and web/src/app/api/generated/* (generated), plus the later web commit cf6d3e1ae (outside the server scope).
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin, the project's configuration); `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.review.*' jacocoTestReport -q`, run in `server/`, with test selection `ch.sonensei.cockpit.review.*`.
- Run start/completion time and exit status: about 2026-11-09T01:47Z to 01:50Z (test suite timestamps 01:48:04Z to 01:49:12Z); exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 812d59b39cd6edff0533257182016ebdbecb13d34495903f5153db90b02bd227.
- Evidence outputs are fresh and inputs stayed unchanged: Gradle reran the tests because the test sources had changed. The XML and test results were rewritten during this run. Server production sources did not change during or after the run (the rebase diff is empty).
- Final run after latest test additions and production fixes: yes. There were no production fixes.

Values below use covered/total (percentage). JaCoCo has no statement counter, so the Statements column shows INSTRUCTION.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `review/domain/WeeklyReviewSchedule.kt` | 164/170 (96.5%) | 12/12 (100%) | 9/11 (81.8%) | 19/19 (100%) |
| `review/domain/WeeklyReviewReminders.kt` | 203/211 (96.2%) | 15/16 (93.8%) | 10/10 (100%) | 33/35 (94.3%) |
| `review/infra/ReviewSettings.kt` | 35/35 (100%) | 2/2 (100%) | 5/5 (100%) | 9/9 (100%) |
| `review/infra/WeeklyReviewItemResolver.kt` | 27/27 (100%) | 2/2 (100%) | 3/3 (100%) | 3/3 (100%) |
| `core/settings/domain/SettingDefinition.kt` (shared file, 8 lines changed) | 588/888 (66.2%) | 28/100 (28.0%) | 23/42 (54.8%) | 73/93 (78.5%) |
| `core/items/domain/ItemType.kt` (1 line changed) | 94/106 (88.7%) | N/A (0 branches) | 4/5 (80.0%) | 15/16 (93.8%) |

Aggregate of the four review-module files (summed counters): instructions 429/443 (96.8%), branches 31/32 (96.9%), methods 27/29 (93.1%), lines 64/66 (97.0%). The two core files are left out of this aggregate on purpose. They are older shared files: their gaps are code that existed before this task, which other packages' tests exercise, and this run selected only the review tests. All scoped files were measured; none is missing.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `WeeklyReviewReminders.kt:83` (one branch: a fired reminder of another item type returns early) | A non-weekly reminder firing must not resync | Left uncovered. Low risk: it is a one-line type guard. A test would need a reminder for another module's item in this test class. It can be added later. |
| `WeeklyReviewReminders.kt:95-96` (the `catch` in `syncLogged`) | A sync failure must not roll back the caller or stop the firing batch | Left uncovered. Exercising it needs a failure injected into ReminderService/JdbcTemplate inside the Spring context (a mock bean), which the existing tests do not set up. It is defensive code. Follow-up is optional. |
| `WeeklyReviewSchedule.kt:16-17` (2 methods: data-class `component1`/`component2`) | none (generated) | Not material |
| `SettingDefinition.kt` / `ItemType.kt` (pre-existing lines) | not part of this task | Outside the task's changed lines |

## Unable to test

- AC4 (`./gradlew check` and openapi regeneration): not run in this short session. The parent must run it.
- Failure isolation in `syncLogged` (see the coverage table): this needs fault injection that the existing test setup does not provide. It is optional follow-up.
- Web commit cf6d3e1ae: outside the server scope the parent gave. It needs its own test-writer pass if the parent counts it as part of this task.

## Summary

Tests added and the focused validation passed. No production defects were found. The full `./gradlew check` and `.taskcheck` were not run here, and the parent must run them.
