# Test Report

Date: 2026-11-09

Task: review-reminder - Weekly review reminder on a configurable weekday (integration/parent task)

Scope: The parent task has no code of its own (base = HEAD = `8a340b6b0c9f756488f603ca0e51f7572c188fdd`, branch `main`). Examined the code of its subtasks review-reminder-server, review-reminder-web and review-reminder-android as merged on main: `git log 502809565^..8a340b6b0 -- server/src web/src android/app/src`, restricted to the review-reminder commits (69c89585e, 5b315a193, e8ac084d6, f373899f8, f41a6ebc6, 3bb2b343c, adb06dc84, 422a989b2, d2cc62ddb, 8ad8c11a5, 1c05ce794, a43c9f505, 0b72c53d7, d2c7a4ad1). Other commits in that range (mail, notes, dashboard, calendar) belong to other tasks and are excluded. Uncommitted at test time: only the test file added by this report (`web/src/app/core/notifications/push-sw.spec.ts`, sha256 `42adf6f0edb1858ba1e907d208c49869f489b19814700afb0fd8217066a60706`).

Requirements:
- `tasks/review-reminder.md` acceptance criteria and recorded assumption (ItemType.WEEKLY_REVIEW, resolver deepLink `/review`, setting `off | <DAY>@HH:mm`)
- SPEC.md §8.6, docs/ARCHITECTURE.md "Coverage"
- Subtask reports `reviews/review-reminder-{server,web,android}/tests.md` (read for context only)

## Test files changed

- `web/src/app/core/notifications/push-sw.spec.ts`

## Tests added or updated

Count: 2 (new describe block `push service worker, weekly review reminder`)

Gap found across the seams: the server-to-web deepLink was tested in the in-page path (`reminder-notifications.service.spec.ts` navigates to `/review` with a mocked router), but not in the Web Push service worker. That worker is what shows the reminder "at the time" when no tab is open. The new tests send the server's weekly review payload (`type WEEKLY_REVIEW`, `deepLink /review`) through `public/push-sw.js` and check that a click opens `<scope>review`. They check both a new tab and an open tab of the German locale build (`/de/review`). The route `review` exists in `web/src/app/app.routes.ts:59`. Both tests pass. They found no defect.

Other seams, checked by reading code and existing tests (no gap, so no test added):
- Server resolver gives deepLink `/review`: `WeeklyReviewReminderTests` (line 119 via the reminders API, line 245 via the resolver directly).
- Android maps `WEEKLY_REVIEW` to `CockpitDestination.Review`, by item type and not by deepLink: `ReminderUiTest` (line 471), `ReminderAlarmsNotifierTest` (lines 160-180, alarm path) and `PushReminderDeliveryTest` (lines 184-204, UnifiedPush path). The type string matches the server `ItemType.WEEKLY_REVIEW`.
- Web en/de strings: 6 new trans-units (`settings.reviewReminder*`, `itemType.weeklyReview`), present in both `messages.xlf` and `messages.de.xlf`. Android `strings_review.xml` in `values` and `values-de` has the same 96 string entries.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| 1. Setting weekday+time/off (server definition + web control) | `SettingsServiceTests`, `WeeklyReviewScheduleTests` (20), `WeeklyReviewEdgeCaseTests` (27); `general-settings.spec.ts` | server: `server/gradlew -p server test --tests '*WeeklyReview*' --tests '*ItemResolverRegistryTests' --tests '*SettingsServiceTests'`; web: `npm --prefix web test -- --watch=false --include <4 specs>` | Pass |
| 2. Server creates the reminder at that time through the existing delivery | `WeeklyReviewReminderTests` (15), `WeeklyReviewWebTests` (9), `ItemResolverRegistryTests` (5); web `reminder-notifications.service.spec.ts` (SSE), `push-sw.spec.ts` (Web Push) | same as above | Pass. Android alarm and UnifiedPush tests not run here (see below) |
| 3. Tapping opens the review on web | `reminder-notifications.service.spec.ts` "weekly review reminder" (2), `push-sw.spec.ts` "push service worker, weekly review reminder" (2, new) | web command above | Pass |
| 3. Tapping opens the review on Android | `ReminderUiTest`, `ReminderAlarmsNotifierTest`, `PushReminderDeliveryTest` (WEEKLY_REVIEW cases) | not run (no Android test changed; slow suite, per parent instruction) | Not run: parent runs `.taskcheck`, which includes `testDebugUnitTest` |
| 4. en+de strings on both clients | inspection of xlf trans-units and `strings_review.xml`; Android `ReminderAlarmsNotifierTest` German title test | grep of the locale files | Pass (files complete). See TW-1 for the notification title at runtime |
| 5. Unit tests on each part; `sh scripts/check.sh` passes | all of the above | `scripts/check.sh` not run, per parent instruction | Not run: the parent runs `.taskcheck` |

## Focused test execution

Command:

`server/gradlew -p server test --tests '*WeeklyReview*' --tests '*ItemResolverRegistryTests' --tests '*SettingsServiceTests'`

Result:

Pass. BUILD SUCCESSFUL. JUnit XML: WeeklyReviewReminderTests 15, WeeklyReviewWebTests 9, WeeklyReviewEdgeCaseTests 27, WeeklyReviewScheduleTests 20, SettingsServiceTests 6, ItemResolverRegistryTests 5. Total 82 tests, 0 failures, 0 errors, 0 skipped.

Command:

`npm --prefix web test -- --watch=false --include src/app/core/notifications/push-sw.spec.ts --include src/app/core/notifications/reminder-notifications.service.spec.ts --include src/app/features/settings/general/general-settings.spec.ts --include src/app/shared/links/item-type.spec.ts`

Result:

Pass. 4 files, 128 tests passed. Run through `npm test` so that the working directory is `web/`: `push-sw.spec.ts` reads `public/push-sw.js` relative to the cwd.

## Full test execution

Command:

`sh scripts/check.sh`

Result:

Not run. The parent instruction says the parent worker runs it.

## .taskcheck

Result:

Not run (the parent runs it).

## Bugs found

### Weekly review notification title is always English

Finding ID:

`TW-1`

Test:

None added. Which layer should own the fix is a design decision for the parent (see below), so no failing test was written.

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/review/infra/WeeklyReviewItemResolver.kt:16` (hard-coded `title = "Weekly review"`). The clients show it unchanged: `web/src/app/core/notifications/reminder-notifications.service.ts:89`, `web/public/push-sw.js:56`, `android/app/src/main/java/ch/sonensei/cockpit/push/PushReminderDelivery.kt:72`. The Android German fallback `untitledTitle()` (`ReminderNotifier.kt:98`, "Wochenrückblick") applies only when the title is null, and the server always sends one.

Requirement:

Criterion 4 (strings in English and German on both clients). ARCHITECTURE.md: all user-facing strings are externalized and translated to German.

Expected behavior:

A German-language user sees the weekly review reminder titled in German ("Wochenrückblick") on web (toast, browser notification, push) and Android.

Actual behavior:

All clients show the English server title "Weekly review". The German Android string and the web `itemType.weeklyReview` label exist, but the notification path never uses them. The Android German test only covers the case with no title, which the server never sends.

Severity:

Low (cosmetic i18n; the reminder works and opens the review).

Fix required:

`Yes — production code must be fixed by the parent worker / fixer`, or the parent records an assumption that this is accepted. Possible fixes: clients choose the title by item type for `WEEKLY_REVIEW`, or the server omits the title so clients fall back to their localized string. The second option still needs a web fallback by type.

## Coverage

Status: verified for server and web (focused runs). Android not measured.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id: review-reminder. Immutable base = measured HEAD = `8a340b6b0c9f756488f603ca0e51f7572c188fdd`. Task-owned commits: the review-reminder-* subtask commits listed under Scope, identified from the parent instruction and commit messages.
- Source/test snapshot: HEAD `8a340b6b0` plus the uncommitted `push-sw.spec.ts` (sha256 `42adf6f0...a60706`). No other changes in the working tree.
- Included server files: `review/domain/WeeklyReviewReminders.kt`, `review/domain/WeeklyReviewSchedule.kt`, `review/infra/ReviewSettings.kt`, `review/infra/WeeklyReviewItemResolver.kt`, `core/items/domain/ItemType.kt`, `core/settings/domain/SettingDefinition.kt`. Included web files: `features/settings/general/general-settings.ts`, `shared/links/item-type.ts`, `core/notifications/reminder-notifications.service.ts`.
- Excluded: generated `web/src/app/api/generated/**` (generated code); `web/public/push-sw.js` (not touched by the task, and not instrumented by Vitest; its tests load it through `new Function`); `general-settings.html/.scss` (templates); locale files (data). Android `reminders/ReminderNotifier.kt` and `reminders/ReminderOpenRequests.kt` are in scope but unmeasured: no Android test changed, and the parent asked not to run the slow Android suite.
- Server: JaCoCo (Gradle plugin), `server/gradlew -p server test --tests '*WeeklyReview*' --tests '*ItemResolverRegistryTests' --tests '*SettingsServiceTests' jacocoTestReport`, cwd = repository root (`-p server`). Exit 0. The report was regenerated at 2026-11-09T05:27:59+01:00 from the focused test run (so the numbers count hits from these focused tests only). Report `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 `a9c430753dbde6e8f0daa97709a35e42c0e26009106e978067bd5cad058c2ec9`.
- Web: Vitest + `@vitest/coverage-v8`, `npm --prefix web run coverage -- --include <the 4 specs above>`. Started 05:28:19, exit 0, 128 tests passed. Report `web/coverage/web/coverage-summary.json`, sha256 `9fa62ce46f01959133d0f59aab03f6800f02796ade9eb4261f70c84fd2b6d993`.
- Outputs are fresh, and no inputs changed between the runs and the hashes above.
- Final run after the latest test additions: yes. No production fixes were made.

Values below use covered/total (percentage). The server "Statements" column is JaCoCo instructions.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/.../review/domain/WeeklyReviewReminders.kt` | 248/255 (97.3%) | 20/20 (100%) | 10/10 (100%) | 36/38 (94.7%) |
| `server/.../review/domain/WeeklyReviewSchedule.kt` | 164/170 (96.5%) | 12/12 (100%) | 9/11 (81.8%) | 19/19 (100%) |
| `server/.../review/infra/ReviewSettings.kt` | 35/35 (100%) | 2/2 (100%) | 5/5 (100%) | 9/9 (100%) |
| `server/.../review/infra/WeeklyReviewItemResolver.kt` | 27/27 (100%) | 2/2 (100%) | 3/3 (100%) | 3/3 (100%) |
| `server/.../core/items/domain/ItemType.kt` | 94/106 (88.7%) | N/A | 4/5 (80.0%) | 15/16 (93.8%) |
| `server/.../core/settings/domain/SettingDefinition.kt` | 650/888 (73.2%) | 35/100 (35.0%) | 31/42 (73.8%) | 81/93 (87.1%) |
| `web/.../settings/general/general-settings.ts` | 167/169 (98.8%) | 85/94 (90.4%) | 35/35 (100%) | 140/140 (100%) |
| `web/.../shared/links/item-type.ts` | 28/28 (100%) | 25/25 (100%) | 3/3 (100%) | 19/19 (100%) |
| `web/.../core/notifications/reminder-notifications.service.ts` | 78/78 (100%) | 37/37 (100%) | 19/19 (100%) | 63/63 (100%) |
| Android `ReminderNotifier.kt`, `ReminderOpenRequests.kt` | not measured | not measured | not measured | not measured |

No aggregate is given, because the server and web providers count statements differently and Android is unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `WeeklyReviewReminders.kt:101-102` | catch/log in `syncLogged` when a sync fails | Defensive. Its behavior (no rollback of the caller) is a design choice. Justified gap |
| `SettingDefinition.kt:26,55-63,140,147,154` and partial branches | Pre-existing validation code for other settings (file changed only to add `weeklyReviewReminder`). The new definition is covered by `WeeklyReviewScheduleTests` / `SettingsServiceTests` | Outside this task's behavior. Justified gap |
| `ItemType.kt:26` | pre-existing helper not used by the focused tests | Outside the task. Justified gap |
| `general-settings.ts` 9 partial branches | earlier subtask edge cases. All lines covered | No meaningful new behavior. Justified gap |
| Android reminder files | criterion 3 on Android | Not measured in this run. Covered by the subtask's tests, which run in `.taskcheck` (`testDebugUnitTest`) |

## Unable to test

- Android unit tests and Android coverage were not run here (slow suite, per the parent instruction; no Android test changed). The parent's `.taskcheck` runs `testDebugUnitTest`.
- Clicking a real OS notification in a real browser or device was not done: no browser or emulator was used. Covered by unit tests of the click handlers only.
- `sh scripts/check.sh` was not run (the parent runs it).

## Summary

Tests added (2 seam tests for the Web Push path). Server and web validation passed. One low-severity production defect was found (TW-1: the weekly review notification title is never localized). The parent must fix it with the fixer, or record it as an accepted assumption, before completing the task.
