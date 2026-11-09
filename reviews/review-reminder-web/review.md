# Code Review

Date: 2026-11-09

Task: review-reminder-web - Weekly review reminder control in web settings

Scope: re-review of the final code, commit range 8c540b53290c779efb7947fd75d40196cd44c20f..81eee9e3d5decc7f7ec3444f37a4a6a4a2967351 (web/src/app/features/settings/general/*, web/src/app/core/notifications/reminder-notifications.service.spec.ts, web/src/locale/*.xlf). This reviewer had no git access (`git diff`/`git log` were denied), so the files in scope were read in their current state at HEAD. Checked: `general-settings.ts` and `general-settings.html` in full, the reminder specs and the new fix specs in `general-settings.spec.ts`, and the `settings.reviewReminder*` trans-units in `messages.xlf` and `messages.de.xlf`. The earlier findings CR-1 and CR-2 (previous review.md) and `fixes.md` were checked against the code.

Requirements:
- tasks/review-reminder-web.md acceptance criteria
- AGENTS.md project notes (conventions, i18n, generated files)
- Previous review (CR-1, CR-2) and reviews/review-reminder-web/fixes.md

Git range:
- Branch: main
- HEAD: 81eee9e3d5decc7f7ec3444f37a4a6a4a2967351
- Base/start: 8c540b53290c779efb7947fd75d40196cd44c20f
- Commits reviewed: 3 (5508866e9 implementation, 945ffd61e tests and helper reports, 81eee9e3d fixes)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification of earlier findings

- CR-1 (reminder row not refreshed after another row's 412 reload): resolved. `save()` now calls `this.showReminder(current)` after `refresh(current)` whenever no reminder save is pending (`general-settings.ts:254-255`). Because the code before the first `await` in `saveReminder()` runs synchronously, `reminderPending` is already raised while a reminder save is in flight, so the guard holds. The new spec "shows the reloaded reminder after another row saves into a 412" (`general-settings.spec.ts:845`) answers a theme PATCH with a 412, reloads with `FRIDAY@08:00`, and checks that the row shows Friday and 08:00.
- CR-2 (an empty or invalid time was ignored without a message): resolved. The time field now has `<mat-error i18n="@@settings.reviewReminder.timeInvalid">Enter a time.</mat-error>` while the control is invalid (`general-settings.html:131-133`). The new spec "asks for a time when the time field is emptied and left" (`general-settings.spec.ts:829`) checks that the message appears and no PATCH is sent. The English source is in `messages.xlf` and the German target "Gib eine Uhrzeit ein." is in `messages.de.xlf`.

## Notes (not findings)

- The CR-1 fix resets `reminderForm` after every generic save, also a successful one. If the user has emptied the time (not saved) and then changes another row, the stored time comes back and the error clears. The generic rows keep user edits by comparing against a baseline; the reminder row does not. `type="time"` inputs fire `change` once the value is complete, so the only unsaved state that can be lost is an empty or partial time, and putting the stored value back is acceptable there. Not a defect.
- Criterion 1 (load, save off, save day+time): covered by "shows a stored day and time", "shows off without a time field...", "saves off", and "saves a day with the default time, then a changed time". Edge-case specs cover refused saves, unknown stored values, overlapping saves, and picking the stored day again while a save runs.
- Criterion 2: the WEEKLY_REVIEW navigation to `/review` specs in `reminder-notifications.service.spec.ts` were assessed in the previous review and are unchanged by the fix commit (it touched only the settings/general files and locales, per fixes.md).
- Criterion 3: the en/de strings for all five `settings.reviewReminder*` ids are present and translated. This reviewer could not run `npm run check`. fixes.md reports that it passed (4767 tests). The parent worker must record that evidence with `tasks.py verify`.

## Review conclusion

No material issues found.
