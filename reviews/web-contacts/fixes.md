# Fix Report

Date: 2026-10-22

Task: web-contacts - Contacts UI

Scope: current task (findings CR-2..CR-5 of `reviews/web-contacts/review.md`; CR-1 handled by the parent worker)

## Fixed findings

### CR-2 — Editing a name never updates a derived display name

Files changed:
- `web/src/app/features/contacts/contact-form-model.ts`
- `web/src/app/features/contacts/contact-form-model.spec.ts`

Problem:
The edit form filled "Shown as" with the stored display name and always sent it back. A display name the server had derived therefore never changed when the name was edited.

Fix:
New `derivedName()` mirrors the server's `ContactService.derivedName` (given + family name, else organization, else first email). `toForm()` leaves "Shown as" empty when the stored display name equals the derived one. A PATCH then sends `displayName: null` and the server derives the name again. A display name the user set keeps being sent. Known edge case: a display name typed by hand that matches the derived name exactly is treated as derived.

Validation:
- New model tests: rename case (`familyName` changed -> `displayName: null`), own display name kept, derivation order. The existing "sends every field" test now expects `displayName: null`, because its fixture's display name is the derived one.
- `npx ng test --watch=false --include='src/app/features/contacts/**/*.spec.ts'` — Pass

### CR-3 — After a save conflict, the user's edits are replaced and a second Save stores nothing

Files changed:
- `web/src/app/features/contacts/contact-form.ts`
- `web/src/app/features/contacts/contact-form.spec.ts`
- `web/src/app/features/contacts/contact-detail.ts`
- `web/src/app/features/contacts/contact-detail.spec.ts`

Problem:
On a 409/412 the page reloaded the contact, and the form's effect replaced the user's input with the server version. A second Save then sent the server version and reported success.

Fix:
The form now refills only when the contact's id changes. A newer version of the same contact keeps the typed values, and only its `version` feeds the next If-Match. On a conflict the page shows a dedicated message (`@@contacts.saveConflict`): the contact was changed elsewhere, the changes are kept, Save again to replace that version or Cancel to see it. The form test that asserted the old refill behaviour (the bug) was replaced by two tests: the same contact reloaded keeps the edits, another contact refills the form. The detail conflict test now checks that the typed title is still in the input and in the retry body, with `If-Match: "3"`.

Validation:
- `npx ng test --watch=false --include='src/app/features/contacts/**/*.spec.ts'` — Pass

### CR-4 — Upcoming event links open the series start, not the listed occurrence

Files changed:
- `web/src/app/features/contacts/contact-display.ts`

Problem:
The doc comment said the calendar opens the occurrence at its date. The calendar actually opens the event's own first occurrence.

Fix:
I checked the calendar first. `calendar-view.ts` `openLinked()` always calls `gotoDate()` on the date of `occurrenceOfEvent(event)`, so a `?date=` next to `?event=` is overridden. The calendar has no occurrence deep link, and changing it is outside this task's `touches`. Only the doc comment was corrected to describe the real behaviour and the limitation. The link itself is unchanged.

Validation:
- `npx ng test --watch=false --include='src/app/features/contacts/**/*.spec.ts'` — Pass (existing link tests unchanged)

### CR-5 — The sync label can render empty parentheses

Files changed:
- `web/src/app/features/contacts/contact-detail.html`
- `web/src/app/features/contacts/contacts-page.html`
- `web/src/app/features/contacts/contact-detail.spec.ts`
- `web/src/app/features/contacts/contacts-page.spec.ts`

Problem:
When an account has neither email nor displayName, the detail showed "Synced with Google ()" and the list showed an empty `.who` span.

Fix:
The detail uses a new message without the parentheses (`@@contacts.detail.syncedNoAccountName`, "Synced with {provider}"). The list omits the `.who` span. Tests were added for both.

Validation:
- `npx ng test --watch=false --include='src/app/features/contacts/**/*.spec.ts'` — Pass

## Not fixed

### CR-1 — The e2e spec in criterion 3 needs unmerged work and the task does not depend on it

Status:
Out of scope

Reason:
This concerns task state and dependencies, which the parent worker owns (the parent excluded it explicitly).

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/contacts/**/*.spec.ts'` — Pass, 6 files, 82 tests

Full test suite:
- `cd web && npm run check` — Fail, not in contacts code. The first run had 1 failure in `src/app/shared/links/linked-items.spec.ts`. A second run had 129 failures: timeouts ("Test timed out in 5000ms") followed by cascading "Cannot configure the test module" errors, while the host's load average was about 146. No contacts spec failed in either run, and this fix touched no shared code. The parent should re-run `.taskcheck` when the machine is less loaded.

Lint/type-check/build:
- `cd web && npm run lint` — Pass (All files pass linting)
- Type-checking ran as part of the Vitest/Angular build of the focused run — Pass

`.taskcheck`:
- Not run (the web part of it was run as `npm run check`, see above)

Remaining unresolved findings:
1 (CR-1, out of scope for the fixer)

## Final status

All validated findings fixed and validation passed (the focused contacts suite and lint pass). The full web suite failed under host overload, outside contacts code. The parent must re-run it.
