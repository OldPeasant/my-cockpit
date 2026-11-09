# Code Review

Date: 2026-11-09

Task: review-reminder-web - Weekly review reminder control in web settings

Scope: commit range 8c540b53290c779efb7947fd75d40196cd44c20f..c7a216b19 (one commit: "review-reminder-web: weekly review reminder control in General settings, WEEKLY_REVIEW notification tests"). Bash was not available to this reviewer, so `git diff` could not be run. The changed files named by the parent worker were read in full instead: `web/src/app/features/settings/general/general-settings.ts`, `general-settings.html`, `general-settings.spec.ts`, `web/src/app/core/notifications/reminder-notifications.service.spec.ts`. The generated `Settings`, `SettingsPatch` and `ReminderItemResponse` models were checked for the contract, along with the server `ReviewSettings.kt` and `WeeklyReviewSchedule.kt`.

Requirements:
- tasks/review-reminder-web.md acceptance criteria
- AGENTS.md project notes (conventions, generated files, i18n)
- Server contract: `weeklyReviewReminder` setting (`off` or `<DAY>@HH:mm`), ReviewSettings.kt / WeeklyReviewSchedule.kt

Git range:
- Branch: main
- HEAD: c7a216b19
- Base/start: 8c540b53290c779efb7947fd75d40196cd44c20f
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Reminder row is not refreshed after another row saves or reloads after a 412**
    - File: `web/src/app/features/settings/general/general-settings.ts:342`
    - Category: Correctness
    - Problem: `refresh()` loops only over `this.fields()`, which are the controls of `form`. `reminderForm` is updated only by `show()`, which runs on the initial load, and by `saveReminder()`. Say the theme save gets a 412 and the settings service reloads the settings. The other rows then show the reloaded server values (see the spec "shows the reloaded server value after a 412"), but the weekly review reminder row keeps its old value. That happens even when the reload has a different `weeklyReviewReminder` that another device set.
    - Why it matters: Inside one section, the reminder row can show a stale day and time while the other rows show fresh ones. The next reminder save still compares against the fresh `service.settings()`, so no data is lost. The row is only shown inconsistently.
    - Suggested fix: When a generic save finishes and no reminder save is pending, also call `showReminder(current)` (for example from `save()` after `refresh`, guarded by `!this.reminderPending`). Add a spec that does the 412 reload with a changed `weeklyReviewReminder`.

- [ ] **CR-2 | An empty or invalid reminder time is silently ignored**
    - File: `web/src/app/features/settings/general/general-settings.ts:262`, `web/src/app/features/settings/general/general-settings.html:122`
    - Category: Quality
    - Problem: With a weekday selected, clearing the time input makes `saveReminder()` return early. No `mat-error` is shown and the save status does not change, so an earlier "Saved" may still be visible. The label rows show "Each label needs 1 to 40 characters." for the same kind of input, but the time field has no message.
    - Why it matters: The user may think the reminder was changed when the server still holds the old time.
    - Suggested fix: Show a `mat-error` when `reminderForm.controls.time` is invalid. Alternatively, put the stored time back on blur, as the time zone field does.

## Notes (not findings)

- Acceptance criterion 1 (load, save off, save day+time): covered by the specs "shows a stored day and time", "saves off" and "saves a day with the default time, then a changed time". Edge cases are covered too: unknown stored value, refused save, overlapping saves, and picking the stored day again while a save is in flight.
- Acceptance criterion 2: WEEKLY_REVIEW navigation to `/review` is unit-tested for both the browser notification click and the toast action. `WEEKLY_REVIEW` is part of the generated `ReminderItemResponse.type` union. The test supplies `deepLink: '/review'` itself, so it checks that the deep link is passed through. It does not check that the server resolver produces that link. The task description asks for exactly this pass-through check.
- Acceptance criterion 3: this reviewer could not run `npm run check` or check the new `@@settings.reviewReminder*` ids in the en/de locale files. The parent worker must record that evidence.
- Client and server agree on the format: the client regex `^([A-Z]+)@((?:[01]\d|2[0-3]):[0-5]\d)$` plus the weekday check matches the server's `WeeklyReviewSchedule.PATTERN`. `formatReminder` produces `DAY@HH:mm` or `off`, both of which the server accepts. The PATCH goes through `SettingsService.update`, so it gets ETag/If-Match and the service's rollback.

## Review conclusion

No material issues found.
