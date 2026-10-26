# Fix Report

Date: 2026-10-26

Task: itip-api (POST /calendar/events/{id}/invitations and the web Send invitations dialog)

Scope: the findings CR-1 and CR-2 from `reviews/itip-api/review.md`, plus the Low UX finding in
`reviews/itip-api/tests.md` (same issue as CR-1). Changes by the fixer sub-agent, limited to the web
invitation dialog and its spec. No server code changed.

## Fixed findings

### CR-1 (P3) - No-accounts hint shown when loading the mail accounts failed

Also covers: the Low UX finding in `reviews/itip-api/tests.md`.

Files changed:
- `web/src/app/features/calendar/edit/invitation-dialog.ts`
- `web/src/app/features/calendar/edit/invitation-dialog.spec.ts`

Problem:
When `listMailAccounts` failed, the dialog showed the load error and also "Add a mail account in the
settings to send invitations.", because the empty-accounts branch could not tell "none set up" from
"could not load". There was no way to retry without closing the dialog.

Fix:
A load failure now goes to its own `loadError` signal (send failures stay in `error`). The template
checks it before the empty-accounts branch, so the no-accounts hint only shows when loading succeeded
with no accounts. The failure block shows the message and a "Try again" button (i18n id
`@@calendar.retry`, same as the event dialog and calendar view) that calls the new `load()` method;
`ngOnInit` delegates to `load()`. Send stays disabled while loading or without an account.

Spec changes: the two load-failure tests now also assert that the no-accounts hint is absent. The
no-accounts test asserts that no "Try again" button is shown. A new test, "loads the mail accounts
again after a failure", clicks Try again, checks that the accounts are fetched a second time and the
alert is gone, and then sends over the reloaded account.

Validation:
- `npx ng test --watch=false --include='src/app/features/calendar/edit/*.spec.ts'` - Pass

### CR-2 (P3) - "To:" line lists the organizer, whom the server never mails

Files changed:
- `web/src/app/features/calendar/edit/invitation-dialog.ts`
- `web/src/app/features/calendar/edit/invitation-dialog.spec.ts`

Problem:
The dialog listed every saved guest, including the organizer. ItipService leaves the organizer out.

Fix:
A computed `recipients` signal filters out the attendees whose address matches the selected account's
`emailAddress`, case-insensitively. It tracks the account selection through `toSignal` on the
`accountId` control's `valueChanges`, so the list updates when the user picks another account. While
the accounts load, or when loading fails, no account is selected and all guests are shown.

Known limitation (accepted by the parent worker): the server takes the organizer from the account's
default identity email first and only then from the account's email address. The dialog does not model
identities, so when a default identity has a different address, the dialog may still list the
organizer. The server's response (`recipients`) remains authoritative for what was actually sent.

Spec: the new test "leaves the address of the chosen account out of the guests" opens the dialog with
the guests `Jane@Example.test`, `anna@example.test` and `jd@example.test`. With the first account
(`jane@example.test`), it checks that Jane is left out despite the different case. After it selects the
second account (`jd@example.test`), it checks that Jane is back and jd is left out.

Validation:
- `npx ng test --watch=false --include='src/app/features/calendar/edit/*.spec.ts'` - Pass

## Not fixed

None.

## Validation summary

All commands were run from `web/`.

Focused tests:
- `npx prettier --write src/app/features/calendar/edit/invitation-dialog.ts src/app/features/calendar/edit/invitation-dialog.spec.ts` - files already formatted, unchanged
- `npx ng test --watch=false --include='src/app/features/calendar/edit/*.spec.ts'` - 8 files, 167 tests passed

Full test suite:
- Not run by the fixer (the parent worker runs `.taskcheck`)

Lint/type-check/build:
- `npm run lint` (`ng lint --max-warnings=0`) - All files pass linting
- The type-check ran as part of the `ng test` build and passed

`.taskcheck`:
- Not run by the fixer (the parent worker runs it before `done`)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
