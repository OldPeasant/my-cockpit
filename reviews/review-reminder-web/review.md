# Code Review

Date: 2026-11-09

Task: review-reminder-web - Weekly review reminder control in web settings

Scope: re-review of the final code, commit range 8c540b53290c779efb7947fd75d40196cd44c20f..0df50ea38d08cf46de4216e3276b30a3d56462d6 (web/src/app/features/settings/general/*, web/src/app/core/notifications/reminder-notifications.service.spec.ts, web/src/locale/*.xlf). This reviewer had no git access (`git diff`/`git log` were denied), so the files in scope were read in their current state at HEAD. Checked: `general-settings.ts` and `general-settings.html` in full, the reminder specs and the new fix specs in `general-settings.spec.ts`, and the `settings.reviewReminder*` trans-units in `messages.xlf` and `messages.de.xlf`. The earlier findings CR-1 and CR-2 (previous review.md) and `fixes.md` were checked against the code. Follow-up: commit 0df50ea38 (one added spec, no production change) was reviewed with `git show 0df50ea38` and `git diff 81eee9e3d 0df50ea38 --stat`; see "Follow-up: final revision 0df50ea38".

Requirements:
- tasks/review-reminder-web.md acceptance criteria
- AGENTS.md project notes (conventions, i18n, generated files)
- Previous review (CR-1, CR-2) and reviews/review-reminder-web/fixes.md

Git range:
- Branch: main
- HEAD: 0df50ea38d08cf46de4216e3276b30a3d56462d6
- Base/start: 8c540b53290c779efb7947fd75d40196cd44c20f
- Commits reviewed: 4 (5508866e9 implementation, 945ffd61e tests and helper reports, 81eee9e3d fixes, 0df50ea38 added spec and reports)

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

## Follow-up: final revision 0df50ea38

- `git diff 81eee9e3d 0df50ea38 --stat` shows that only `general-settings.spec.ts` (+26 lines) and the files under `reviews/` changed. Production code is the same as in the revision reviewed above.
- The new spec "keeps a pending reminder pick when another row finishes saving first" (`general-settings.spec.ts:870`) starts a theme PATCH, picks Monday while it is in flight, then answers the theme PATCH with settings that still hold `SUNDAY@18:00`. It checks that the day select still shows Monday, that the reminder PATCH body is `{ weeklyReviewReminder: 'MONDAY@18:00' }`, and that Monday is still shown after that PATCH succeeds.
- This covers the `if (!this.reminderPending)` guard added by the CR-1 fix (`general-settings.ts:255`). Without the guard, the theme answer would call `showReminder` with Sunday and the first assertion would fail, so the spec is a real regression test and not one that passes no matter what. It asserts what the user sees and the request body, not internals. `http.verify()` in the top-level `afterEach` (`general-settings.spec.ts:53`) makes sure no request is left unanswered.
- This reviewer did not run the spec. The parent worker's validation run is the evidence that it passes.
- No new findings.

## Notes (not findings)

- The CR-1 fix resets `reminderForm` after every generic save, also a successful one. If the user has emptied the time (not saved) and then changes another row, the stored time comes back and the error clears. The generic rows keep user edits by comparing against a baseline; the reminder row does not. `type="time"` inputs fire `change` once the value is complete, so the only unsaved state that can be lost is an empty or partial time, and putting the stored value back is acceptable there. Not a defect.
- Criterion 1 (load, save off, save day+time): covered by "shows a stored day and time", "shows off without a time field...", "saves off", and "saves a day with the default time, then a changed time". Edge-case specs cover refused saves, unknown stored values, overlapping saves, and picking the stored day again while a save runs.
- Criterion 2: the WEEKLY_REVIEW navigation to `/review` specs in `reminder-notifications.service.spec.ts` were assessed in the previous review and are unchanged by the fix commit (it touched only the settings/general files and locales, per fixes.md).
- Criterion 3: the en/de strings for all five `settings.reviewReminder*` ids are present and translated. This reviewer could not run `npm run check`. fixes.md reports that it passed (4767 tests). The parent worker must record that evidence with `tasks.py verify`.

## Review conclusion

No material issues found.
