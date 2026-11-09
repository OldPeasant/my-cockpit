# Test Report

Date: 2026-11-09

Task: review-reminder-server - Weekly review reminder setting and scheduling (server)

Scope (final re-run, 2026-11-09): task base 2e61bfa28dc1dd187910fb8e551c8e48141c069e .. HEAD 16f9276dd8d4a7ac80ee06a705f7f164ca766fa1. The previous run covered the server work through b08b3fd2d. `git diff --stat b08b3fd2d..HEAD -- server web api` is empty, so the server production code, the server tests and the web side are unchanged and the server numbers below still apply. The only new task-owned code is commit 16f9276dd, which changes two Android unit tests and no production code (`android/app/src/test/.../reminders/ReminderUiTest.kt`, `android/app/src/test/.../trash/TrashModelsTest.kt`). The other Android changes in b08b3fd2d..HEAD belong to task cal-invitation-respond-android (merged from main) and are outside this scope.

Final-revision Android check: the regenerated API client added `WEEKLY_REVIEW` to the item-type enums, and both adjustments are sound.
- `ReminderUiTest`: the destination table now expects `"WEEKLY_REVIEW" -> CockpitDestination.Dashboard`. That is today's behavior, the generic `else` fallback, and the comment marks it for task review-reminder-android to change. The test still fails if any known type falls back by mistake.
- `TrashModelsTest`: the "client knows every API type" loop skips only `WEEKLY_REVIEW`. The well-known weekly review item is never soft-deleted, so it cannot show up in the trash. If it ever did, `trashTypeLabel` would show the generic "other" label instead of crashing. The filter matches one exact value, so any other new enum value without a label still fails the test.

Earlier scope (previous run, server): Base 2d96f3ce646fc9f1d032f721a7fc68a7f0f26d29 .. HEAD 9ad52c6b47f49222f66e3d41b525ac5159faa208 (task commits 6ee0cc105, 2c2e37dd3, a81515f71, a48e64622, 28b739d8e, 9ad52c6b4). The parent set the scope as `git diff 2d96f3ce6..HEAD -- server web api`. The focus is the server production code, including the CR-2 fix in 28b739d8e. This run did not test the web side (generated client, item-type glyph/label and i18n, `item-type.spec.ts`), and the parent runs `npm run check`. I did not touch the uncommitted change to `reviews/review-reminder-server/review.md`, which belongs to the code-reviewer.

Requirements:
- tasks/review-reminder-server.md acceptance criteria and Design section (SPEC.md §8.6)
- docs/ARCHITECTURE.md "Coverage"
- parent instructions: re-run after the CR-2 fix, add only missing relevant tests, focused tests only, no full `./gradlew check`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/review/WeeklyReviewReminderTests.kt` (previous run)
- `android/app/src/test/java/ch/sonensei/cockpit/reminders/ReminderUiTest.kt` and `android/app/src/test/java/ch/sonensei/cockpit/trash/TrashModelsTest.kt` (commit 16f9276dd by the parent, reviewed in this run; the test-writer made no further changes)

## Tests added or updated

Count: 2 added

- `of several due pending reminders, sync keeps only the latest`: covers the CR-2 `due.dropLast(1)` path. Two due pending reminders exist (the server was down past both). `sync()` keeps only the latest due one, so the scheduler can still fire it, deletes the older one and arms next Sunday's.
- `a fired reminder of another item type does not resync`: covers the type guard in `onReminderFired`. When a `ReminderFiredEvent` for another item type arrives, the tampered weekly reminder row stays unchanged.

The existing tests already covered everything else in scope, including the CR-2 single-due-reminder case (`startup keeps a reminder that became due while the server was down, so it still fires`) and turning it off while a reminder is due. Earlier reports listed the type guard as an uncovered branch, and that gap is now covered.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 setting definition with validation (parse: off, valid, invalid day/time) | WeeklyReviewScheduleTests: `parses off and valid schedules`, `rejects invalid day or time` (12 cases), `rejects non-strings`, `isValid accepts off and schedules only`; WeeklyReviewReminderTests: `invalid values are rejected with 400`, `setting it through the settings API arms the reminder, an invalid value is a 400 and changes nothing` | `cd server && ./gradlew test --tests '*WeeklyReview*' jacocoTestReport` | Pass |
| AC2 next occurrence honours timeZone and DST | `next occurrence is later today, next week, or strictly after now`, `on the scheduled weekday after the time, the next occurrence is a week later`, `next occurrence honours the time zone`, `next occurrence honours DST changes`, `a time in the DST gap moves forward, one in the overlap fires at its first occurrence`, `a time zone change moves the reminder` | same | Pass |
| AC3 pending reminder created/replaced/removed on setting change and re-armed after firing (Spring) | `turning it on creates the reminder, changing it replaces it, off removes it`, `off by default, without a reminder`, `a time zone change moves the reminder`, `re-armed for the next week after it fired, and delivered with its item`, `an unrelated settings change keeps the pending reminder`, `startup catches up a reminder lost while the server was down`, `startup keeps a reminder that became due while the server was down, so it still fires`, `of several due pending reminders, sync keeps only the latest`, `a fired reminder of another item type does not resync`, `turning it off also removes a pending reminder that is already due`, `sync is idempotent and repairs extra or missing pending reminders`, `concurrent syncs leave exactly one pending reminder`, `the resolver knows only the one weekly review item` | same | Pass |
| AC4 openapi regenerated; `./gradlew check` passes | not run here (the parent said not to run the full check) | - | Not run: the parent runs `cd server && ./gradlew check` |
| Regenerated client stays compatible with Android (follows from AC4, not a separate criterion) | ReminderUiTest (23 tests), TrashModelsTest (5 tests) | `android/gradlew -p android ktlintCheck testDebugUnitTest --tests '*ReminderUiTest' --tests '*TrashModelsTest'` | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*WeeklyReview*' jacocoTestReport` and then `./gradlew ktlintTestSourceSetCheck -q`

Result:

Pass. Both commands exited 0 (BUILD SUCCESSFUL). 71 tests, 0 failures, 0 errors, 0 skipped: WeeklyReviewScheduleTests 20, WeeklyReviewReminderTests 15, WeeklyReviewWebTests 9, WeeklyReviewEdgeCaseTests 27. Test suites started at about 2026-11-09T02:31:55Z. ktlint passes on the test sources.

Final revision (Android, HEAD 16f9276dd):

`android/gradlew -p android ktlintCheck testDebugUnitTest --tests '*ReminderUiTest' --tests '*TrashModelsTest'`, run from the repo root

Result: Pass. BUILD SUCCESSFUL in 2m 58s, exit 0. ReminderUiTest: 23 tests, 0 failures, 0 errors, 0 skipped (started 2026-11-09T02:58:39Z). TrashModelsTest: 5 tests, 0 failures, 0 errors, 0 skipped (started 02:59:09Z). ktlintCheck passed.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run. The parent asked not to run it and runs it before `done`. It is AC4.

## .taskcheck

Result:

Not run. The parent runs it.

## Bugs found

None.

## Coverage

Status: verified for the server files in scope. The numbers come from the focused `*WeeklyReview*` test selection, not from the full suite.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured with tooling. A manual check of the JaCoCo line data shows that every line of the four review-module files is covered except `WeeklyReviewReminders.kt:101-102`. The changed lines in the core files (the `Formatted` setting type in SettingDefinition.kt and `WEEKLY_REVIEW` in ItemType.kt) are covered as well.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: review-reminder-server. Base 2d96f3ce646fc9f1d032f721a7fc68a7f0f26d29 (from the parent). Measured HEAD 9ad52c6b47f49222f66e3d41b525ac5159faa208. The file scope is `git diff --name-only 2d96f3ce6..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): production code = HEAD 9ad52c6b4 (no uncommitted production changes). Uncommitted test file: WeeklyReviewReminderTests.kt, sha256 d50ecef661c63c0964a83a0c082e695a56b8797ce61cd5a7a1787d5787ba3fc2. The other test files are as at HEAD.
- Exact included files: review/domain/WeeklyReviewSchedule.kt, review/domain/WeeklyReviewReminders.kt, review/infra/ReviewSettings.kt, review/infra/WeeklyReviewItemResolver.kt, core/settings/domain/SettingDefinition.kt, core/items/domain/ItemType.kt. No files were renamed or deleted. Excluded: api/openapi.yaml and web/src/app/api/generated/* (generated), plus the web sources (outside this server measurement).
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin, the project's configuration; Gradle 9.8.1). Command `./gradlew test --tests '*WeeklyReview*' jacocoTestReport`, run in `server/`, with test selection `*WeeklyReview*`.
- Run start/completion time and exit status: 2026-11-09 at about 02:31Z to 02:32Z (the build took 42 s); exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 a05a1f3f2ecd0a1c5cb091d5ba8676f867b38a49ac1be1c526ea1e73aa4026c0.
- Evidence outputs are fresh and inputs stayed unchanged: compileTestKotlin and test executed (not up to date) because the test file had changed, and jacocoTestReport was regenerated. The production code did not change during the run.
- Final run after latest test additions and production fixes: yes. It ran after the CR-2 fix (28b739d8e) and after the two tests added here. The final revision is HEAD 16f9276dd. `git diff --stat b08b3fd2d..HEAD -- server web api` is empty, so the server production and test inputs are identical to the measured state and these numbers still apply. The only change since then (16f9276dd) touches Android tests only. It adds no production code, so it adds no coverage scope, and no Android coverage was measured for this task.

Values below use covered/total (percentage). JaCoCo has no statement counter, so the Statements column shows INSTRUCTION.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `review/domain/WeeklyReviewSchedule.kt` | 164/170 (96.5%) | 12/12 (100%) | 9/11 (81.8%) | 19/19 (100%) |
| `review/domain/WeeklyReviewReminders.kt` | 248/255 (97.3%) | 20/20 (100%) | 10/10 (100%) | 36/38 (94.7%) |
| `review/infra/ReviewSettings.kt` | 35/35 (100%) | 2/2 (100%) | 5/5 (100%) | 9/9 (100%) |
| `review/infra/WeeklyReviewItemResolver.kt` | 27/27 (100%) | 2/2 (100%) | 3/3 (100%) | 3/3 (100%) |
| `core/settings/domain/SettingDefinition.kt` (shared file, 8 lines changed) | 588/888 (66.2%) | 28/100 (28.0%) | 23/42 (54.8%) | 73/93 (78.5%) |
| `core/items/domain/ItemType.kt` (1 line changed) | 94/106 (88.7%) | N/A (0 branches) | 4/5 (80.0%) | 15/16 (93.8%) |

Aggregate of the four review-module files (summed counters): instructions 474/487 (97.3%), branches 36/36 (100%), methods 27/29 (93.1%), lines 67/69 (97.1%). The two core files are left out of this aggregate on purpose. They are older shared files whose gaps are code that existed before this task (other packages' tests exercise it), and only the WeeklyReview tests were selected. All six scoped files were measured; none is missing.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `WeeklyReviewReminders.kt:101-102` (the `catch` in `syncLogged`) | A sync failure must not roll back the caller or stop the firing batch | Left uncovered. Exercising it needs a failure injected into ReminderService/JdbcTemplate inside the Spring context (a mock bean replacing a shared bean), which the existing tests do not set up. It is defensive code. Follow-up is optional. |
| `WeeklyReviewSchedule.kt:16-17` (data-class `component1`/`component2`) | none (generated) | Not material |
| `SettingDefinition.kt` / `ItemType.kt` (pre-existing lines, e.g. ItemType.kt:26) | not part of this task | Outside the task's changed lines |

## Unable to test

- AC4 (`./gradlew check`, openapi regeneration): not run here, at the parent's instruction. The parent runs it.
- Failure isolation in `syncLogged`: this needs fault injection that the existing test setup does not provide. It is optional follow-up.
- Web changes (generated client, glyph/label, i18n): outside this server-focused run. They are covered by `npm run check`, which the parent runs.

## Summary

Tests added and the focused validation passed. On the final revision, the two Android test adjustments for the new `WEEKLY_REVIEW` enum value are sound and pass together with ktlint. Task-scoped server coverage is sufficient, and no production defects were found. The full `./gradlew check` and `.taskcheck` were not run here, and the parent must run them.
