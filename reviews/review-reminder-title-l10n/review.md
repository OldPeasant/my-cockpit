# Code Review

Date: 2026-11-09

Task: review-reminder-title-l10n - Weekly review reminder notification title in the client language

Scope: final revision 63f1b05eaf8b7fa55a10631d4d94b58adab982f8. The scope is the task's implementation commit (original 7d600e831, rebased to 07ab3e15f with identical code; `git diff 7d600e831 07ab3e15f` differs only in tasks/review-reminder-title-l10n.md) plus the test commit (`git diff 07ab3e15f..63f1b05ea`). Committed changes only.

Requirements:
- tasks/review-reminder-title-l10n.md (acceptance criteria, goal, recorded assumption)
- reviews/review-reminder/review.md finding CR-1 (origin of the task)
- SPEC.md, docs/ARCHITECTURE.md conventions (i18n xlf units, lint), AGENTS.md project notes
- Parent instructions: push-sw.js and android push/PushReminderDeliveryTest.kt are accepted outside the declared touches; no Gradle runs

Git range:
- Branch: main
- HEAD: 63f1b05eaf8b7fa55a10631d4d94b58adab982f8 (reviewed revision; later rebases to fe391e372 differ from it only in tasks/ files)
- Base/start: 7a9232603 (parent of the rebased implementation commit; originally af9d1053d)
- Commits reviewed: 2 (07ab3e15f feat: clients show their own translated title for weekly review reminders; 63f1b05ea test: German keeps other titles, worker names match the xlf; test report)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## What was checked

- Web in-app path (`reminder-notifications.service.ts`): `reminderTitle()` returns the `$localize` unit `reminders.weeklyReview` for item type WEEKLY_REVIEW and keeps the server title for every other type. It is used for both the browser notification and the toast fallback. New unit `reminders.weeklyReview` exists in `messages.xlf` and has a translated German target ("Wochenrückblick") in `messages.de.xlf`.
- Web push path (`web/public/push-sw.js`): new `TEXTS.weeklyReview` for en and de. The reminder title is chosen by `item.type === 'WEEKLY_REVIEW'`, and the old `item.title || T.reminder` fallback stays for the other types.
- Android (`ReminderNotifier.kt`): the new `notificationTitle()` uses `R.string.review_reminder_title` for WEEKLY_REVIEW whether or not a title is present. The local alarm path (`ReminderScheduler`/`ReminderReceivers`) and the UnifiedPush path (`PushReminderDelivery` builds a `ScheduledReminder`) both call `ReminderNotifier.show`, so both are covered. The German string exists in `values-de/strings_review.xml`.
- Implementation tests (07ab3e15f): web spec covers en and de (loadTranslations with the same targets as the xlf), with and without a server title, for both the notification and the toast. The push-sw spec covers en and de and both with and without a title. The Android tests cover the alarm path (en with a server title, de with the English server title, other types keep their title) and the push path (en and de). The changed Android push test replaced the old keep-server-title assertion, as the assumption recorded in the task allows.
- Test commit (63f1b05ea):
  - `reminder-notifications.service.spec.ts` adds a German-locale case that a non-weekly reminder keeps its server title ("Call the plumber", the default fixture title). This is a valid regression guard against translating every title.
  - `push-sw.spec.ts` adds a German case that keeps a TASK reminder's title. It also adds a drift guard that parses `messages.xlf` (source) and `messages.de.xlf` (target) for `reminders.weeklyReview` and compares them with the worker's own titles. If the unit is missing, the lookup returns undefined and the assertion fails, so the guard cannot pass vacuously. This addresses the risk that the worker's hard-coded copy and the app's translation drift apart.
  - These are test-only changes plus `reviews/review-reminder-title-l10n/tests.md`. No production code changed.
- Ran `npm --prefix web run test:ci -- --include='src/app/core/notifications/**/*.spec.ts'` on the clean working tree at the final revision (only tasks/ files differ from 63f1b05ea): 8 files, 135 tests passed.
- Ran `eslint` on both changed spec files: clean. `prettier --check` on both: clean. (The formatting problem noted in the earlier version of this report was in uncommitted work; it is fixed in the committed spec.) Earlier, `eslint` on `reminder-notifications.service.ts` and `public/push-sw.js` was also clean, and those files have not changed since.
- Android tests and ktlint were not run here because the parent said not to run Gradle. The Kotlin changes were reviewed by reading them.
- Security: no secrets, logging or input-handling changes. The title is a fixed label, so the lock-screen privacy handling does not change.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | `untitledTitle` keeps an unreachable weekly-review branch and an outdated comment**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/reminders/ReminderNotifier.kt:111`
    - Category: Quality
    - Problem: `untitledTitle` is now only called from the `else` branch of `notificationTitle` (line 108), which runs only when `itemType != WEEKLY_REVIEW`. Its `WEEKLY_REVIEW -> R.string.review_reminder_title` branch and its doc comment ("the weekly review has its own name") no longer describe any production path. Only the test `only the weekly review gets its own fallback title...` (ReminderAlarmsNotifierTest.kt:200) still exercises that branch.
    - Why it matters: Two functions now decide the weekly-review title. Someone changing one of them may assume the other still matters, and the test keeps dead behavior alive.
    - Suggested fix: Simplify to `reminder.title ?: context.getString(R.string.reminder_untitled)` in the else branch and remove `untitledTitle`, or move that test onto `notificationTitle`. Or keep `untitledTitle` as the single decision point and drop the duplicate check in `notificationTitle`.

## Review conclusion

No material issues found. All three clients show the translated name for WEEKLY_REVIEW reminders, the new tests are meaningful and pass, and the one finding is a small cleanup that does not block the task.
