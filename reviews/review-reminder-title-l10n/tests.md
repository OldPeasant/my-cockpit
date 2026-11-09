# Test Report

Date: 2026-11-09

Task: review-reminder-title-l10n - Weekly review reminder notification title in the client language

Scope: task base af9d1053df332792e4d890abffc21a28a132b5f1, task commit 7d600e831 (git diff af9d1053d..7d600e831),
supplied by the parent. During the run the parent rebased: HEAD is now 07ab3e15f97ae820f4424586f8bae98730fbae57
(07ab3e15f, on top of the assume commit 7a9232603). `git diff 7d600e831 07ab3e15f -- web android server` is empty, so the
measured code is identical. Uncommitted at measurement time: only the two web spec files changed by this report
(hashes below).

Requirements:
- tasks/review-reminder-title-l10n.md acceptance criteria
- reviews/review-reminder/review.md finding CR-1 (referenced by the task goal)
- docs/ARCHITECTURE.md "Coverage" and "Code conventions" (i18n: English + German)
- AGENTS.md (test data fictitious, tests in each part's existing framework)

## Test files changed

- `web/src/app/core/notifications/push-sw.spec.ts`
- `web/src/app/core/notifications/reminder-notifications.service.spec.ts`

No Android test files were changed: the existing task tests already cover both Android paths in en + de.

## Tests added or updated

Count: 3 (added, nothing existing changed)

- push-sw.spec.ts: `keeps the title of other reminders in German` - the WEEKLY_REVIEW override does not leak to other
  item types in the German worker.
- push-sw.spec.ts: `names the weekly review like the app translations, in English and German` - the service worker
  cannot use $localize and keeps its own copy (TEXTS.weeklyReview); this ties the English name to the `source` of
  `reminders.weeklyReview` in messages.xlf and the German name to its `target` in messages.de.xlf, so the push and the
  in-app notification cannot drift apart. It also proves the German xlf unit really is "Wochenrückblick", which the
  service spec's German block only assumes via a hard-coded `loadTranslations` map.
- reminder-notifications.service.spec.ts, "in German": `keeps the server title of other reminders`.

Gap review of the tests delivered with the task (all kept unchanged):

| Path | en | de |
|---|---|---|
| Web in-app browser notification | `shows its own name even when the server sends another title` | `titles the browser notification Wochenrückblick, not the server's English title` |
| Web in-app toast | `shows its own name when the server sends no title` | `toasts Wochenrückblick while notifications are off` |
| Web service worker push | `shows the weekly review push ...`, `shows its own name, not the title the server sends, and also without one` | `titles the German push Wochenrückblick instead of the server's English title` |
| Android local alarm | `a weekly review reminder shows its own name also when the server sends a title` | `in German a weekly review reminder with the server's English title is called Wochenrückblick` |
| Android UnifiedPush | `a pushed weekly review reminder with the server's title shows the app's own name` | `in German a pushed weekly review reminder with the server's English title is called Wochenrückblick` |

Other item types keeping their title: Android `other reminders keep the title the server sends` (alarm) and the
existing push test `a tap on the pushed reminder opens its item ...`; web English paths by pre-existing tests; German
web paths by the two new tests above.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Web in-app notification/toast shows the localized WEEKLY_REVIEW title (en + de) | service spec "weekly review reminder" block incl. "in German" (6 title tests) + new `keeps the server title of other reminders` | `cd web && npx ng test --watch=false --include='src/app/core/notifications/**/*.spec.ts'` | Pass (8 files, 135 tests) |
| AC1 Web service worker push shows the localized WEEKLY_REVIEW title (en + de) | push-sw spec "weekly review reminder" block incl. 2 new tests | same command | Pass |
| AC2 Android local alarm shows review_reminder_title also with a server title (en + de) | ReminderAlarmsNotifierTest (19 tests) | `cd android && ./gradlew testDebugUnitTest --tests '*ReminderAlarmsNotifierTest' --tests '*PushReminderDeliveryTest'` | Pass (task UP-TO-DATE: results of the identical inputs from 2026-11-09T08:48Z, 19 tests, 0 failures); re-executed by the coverage run below, BUILD successful |
| AC2 Android UnifiedPush shows review_reminder_title also with a server title (en + de) | PushReminderDeliveryTest (25 tests) | same command | Pass (25 tests, 0 failures); re-executed by the coverage run below |
| AC3 `sh scripts/check.sh` passes | - | `sh scripts/check.sh` | Not run: the command was denied by the sub-agent permission system; the parent must run it |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/core/notifications/**/*.spec.ts'`

Result:

Pass - 8 test files, 135 tests (after adding the 3 tests).

Command:

`cd android && ./gradlew testDebugUnitTest --tests '*ReminderAlarmsNotifierTest' --tests '*PushReminderDeliveryTest'`

Result:

Pass - BUILD SUCCESSFUL, testDebugUnitTest UP-TO-DATE (the parent's earlier run on the same inputs; result XML:
ReminderAlarmsNotifierTest 19 tests, PushReminderDeliveryTest 25 tests, 0 failures, 0 errors, 0 skipped).

Lint of the changed spec files: `npx prettier --write` (reformatted one long line in push-sw.spec.ts), then
`npx prettier --check` and `npx eslint --max-warnings=0` on both files: clean.

## Full test execution

Command:

`cd web && npm run coverage` (full web Vitest suite with coverage)

Result:

Pass - 196 test files, 4779 tests.

Command:

`cd android && ./gradlew createDebugUnitTestCoverageReport --console=plain -q` (full Android debug unit test suite with JaCoCo)

Result:

Pass - exit 0, no failure output, fresh report written.

Server: not affected by the task (no server file changed); not run.

## .taskcheck

Result:

Not run - `sh scripts/check.sh` was denied by the permission system for this sub-agent. The parent must run it (AC3).

## Bugs found

None.

## Coverage

Status: verified for reminder-notifications.service.ts and ReminderNotifier.kt; unavailable for web/public/push-sw.js
(not instrumented, see below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: Android measured from JaCoCo `line` elements of the changed executable lines 40, 105, 106, 108
of ReminderNotifier.kt: 4/4 lines, 4/4 branches (the 2 branches each on 105 and 108). Web
reminder-notifications.service.ts: every executable line and branch of the file is covered (whole-file 100%), so all
changed lines are covered; not separately intersected. push-sw.js: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: review-reminder-title-l10n; base
  af9d1053df332792e4d890abffc21a28a132b5f1 (from the parent); task commit 7d600e831, rebased by the parent to
  07ab3e15f97ae820f4424586f8bae98730fbae57 with identical code (empty diff for web/android/server).
- Source/test snapshot identifier (including uncommitted inputs), sha256:
  web/public/push-sw.js 4fcbf387...ce68d;
  web/src/app/core/notifications/reminder-notifications.service.ts 382bcd94...e5769;
  web/src/app/core/notifications/push-sw.spec.ts e38c5dc3...5e8625 (uncommitted, after prettier, before the coverage run);
  web/src/app/core/notifications/reminder-notifications.service.spec.ts ed13c201...e1ce0 (uncommitted);
  android/.../reminders/ReminderNotifier.kt 170b6763...0c365;
  android/.../reminders/ReminderAlarmsNotifierTest.kt 2046b611...109e4;
  android/.../push/PushReminderDeliveryTest.kt 551151ee...dd3df3.
  Production hashes were identical before and after the runs.
- Exact included files: `web/src/app/core/notifications/reminder-notifications.service.ts`, `web/public/push-sw.js`,
  `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderNotifier.kt`. Excluded: `web/src/locale/messages.xlf`,
  `web/src/locale/messages.de.xlf` (translation data, no executable code; their content is checked by the new
  push-sw test and by src/locale/messages-de.spec.ts), all test files. No renames or deletions.
- Provider/version, command, working directory, and test selection: web - Vitest 5.0.5 + @vitest/coverage-v8 5.0.5,
  `npm run coverage` in web/, full suite; Android - JaCoCo via AGP `enableUnitTestCoverage`,
  `./gradlew createDebugUnitTestCoverageReport` in android/, full debug unit test suite.
- Run start/completion time and exit status: web 2026-11-09T09:50:19+01:00 to 09:51:06, exit 0; Android finished
  2026-11-09T09:55:36+01:00 (report mtime), exit 0.
- Generated report paths and report hashes/run identifier: web/coverage/web/coverage-summary.json (sha256 aff510fd...a304),
  web/coverage/web/coverage-final.json (sha256 df0f596e...e86a); android/app/build/reports/coverage/test/debug/report.xml
  (sha256 a3a5d329...f2d5).
- Evidence outputs are fresh and inputs stayed unchanged: yes - web/coverage was recreated at the run start
  (directory mtime 09:50:19), report files written at 09:51:06; JaCoCo report written at 09:55:36 by this run; input
  hashes re-checked after the runs.
- Final run after latest test additions and production fixes: yes (no production fixes; all 3 tests were added before
  both coverage runs).

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/core/notifications/reminder-notifications.service.ts` | 81/81 (100%) | 39/39 (100%) | 20/20 (100%) | 66/66 (100%) |
| `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderNotifier.kt` | 245/246 instructions (99.6%) | 20/20 (100%) | 11/11 methods (100%) | 55/56 (98.2%) |
| `web/public/push-sw.js` | N/A (not instrumented) | N/A | N/A | N/A |

No aggregate is given: the two providers count differently (V8 statements vs JaCoCo instructions), and push-sw.js is
unmeasured.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `android/.../reminders/ReminderNotifier.kt:53` | `catch (_: SecurityException)` when the permission is revoked between check and notify; pre-existing, not changed by this task | Justified gap: outside the task; Robolectric does not throw there |
| `web/public/push-sw.js` (whole file) | WEEKLY_REVIEW title ternary (line 59) | Not instrumented: the spec loads the worker with `readFileSync` and evaluates it, and the file lives in `public/`, outside the Vitest coverage include. Measuring it would need a coverage configuration change, which is outside the test-writer's authority. Behaviorally both arms of the new ternary and both arms of `item.title or T.reminder` are exercised in en and de by the tests listed above |

## Unable to test

- `sh scripts/check.sh` (AC3): not run because the permission system denied it to this sub-agent; the parent must run it.
- Coverage of web/public/push-sw.js: not measurable with the current configuration (see Coverage); its behavior is
  covered by push-sw.spec.ts. No follow-up needed for the task.
- Real-device display (an actual Android notification shade, an actual browser push): not in scope of the unit-test
  criteria.

## Summary

Tests added and all validation passed (focused web and Android tests, full web suite, full Android unit suite with
coverage). No production defects found. `.taskcheck` was not run by the test-writer and must be run by the parent.
