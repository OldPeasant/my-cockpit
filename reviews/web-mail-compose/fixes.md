# Fix Report

Date: 2026-10-22

Task: web-mail-compose - Mail compose, reply, forward

Scope: findings CR-1 to CR-5 in `reviews/web-mail-compose/review.md` and TW-1, TW-2 in `reviews/web-mail-compose/tests.md`. Changes stay in `web/src/app/features/mail/compose/`.

## Fixed findings

### CR-1 / TW-1 / TW-2 — A sender change outside the write queue leaves the draft in the old account

Files changed:
- `web/src/app/features/mail/compose/mail-compose-page.ts`
- `web/src/app/features/mail/compose/mail-compose-page.spec.ts`

Problem:
`changeSender` set `accountId` and checked `stored()` synchronously. Two cases went wrong. If a first create was still in flight, the draft was created in the old account while From showed the new one. If the move's create failed, the From field still showed the new account, autosave and Send kept using the old draft, and the rejection was not handled.

Fix:
- `accountId` is still the sender the user picked (what From shows). A new private `draftAccountId`, set in `apply()` from `draft.accountId`, records which account actually holds the draft.
- `changeSender` sets `accountId`, marks an unsaved draft as edited, and queues `followSender()` through `write()`.
- `followSender()` decides when it runs, after any save already in flight. If the draft is stored and `draftAccountId` differs from the picked account, it recreates the draft there with the current content (without the old account's `identityId`, so the new account uses its default identity) and then discards the old copy. If the draft can't move by then (attachments or reply headers), or the create fails, From is reset to `draftAccountId`. The reset is skipped if the user picked yet another account in the meantime.
- A failed move is reported with `NotificationService.error`. The `.catch` in `changeSender` handles the rejection, so there is no unhandled rejection.
- `ownSent()` now checks the right account, because `accountId` matches the draft's account whenever Send runs.
- About the instruction that "`apply` should keep accountId consistent": `apply` does not overwrite `accountId`, because that would undo a sender picked during an in-flight create (the TW-2 case). It records the real account in `draftAccountId` instead, and `followSender` brings the two back in line.

Validation:
- TW-1 test (`reports a failed move ... keeps showing the account holding the draft`) — Pass, unchanged
- TW-2 test (`stores the draft in the account picked while its first save was running`) — Pass, unchanged
- New: `reports a failed move once and shows the account holding the draft in From` (exactly one error, the From select shows the old account, the later update and Send go to the old draft) — Pass
- New: `moves nothing when the sender is changed and changed back during the first save` — Pass
- New: `starts the moved draft with the new account's default identity` — Pass

### CR-2 — Enter in the Subject field sends the message

Files changed:
- `web/src/app/features/mail/compose/mail-compose-page.html`
- `web/src/app/features/mail/compose/mail-compose-page.spec.ts`

Problem:
The Send button was `type="submit"` and the form's `(submit)` called `send()`. Pressing Enter in Subject triggered implicit form submission and sent the mail. Native validation of the `type="email"` chip inputs could also block a Send click without explanation.

Fix:
- Send is now `type="button"` with `(click)="send()"`.
- The form has `novalidate`, and `(submit)` only calls `preventDefault()`.

Validation:
- New: `does not send when the form is submitted (Enter in Subject); the Send button sends` (checks: no submit button, `noValidate`, a submit event is prevented and sends nothing, clicking Send sends) — Pass

### CR-3 — The URL is not updated when a new draft is created or moved

Files changed:
- `web/src/app/features/mail/compose/mail-compose-page.ts`
- `web/src/app/features/mail/compose/mail-compose-page.spec.ts`

Problem:
`?draft=` was set only for reply, reply all and forward. Reloading a new mail after its first save started another draft. Reloading after a sender move opened the discarded draft and returned 404.

Fix:
- New `showInUrl(id)`, called from `apply()`. When the draft id differs from the one the URL already holds (`urlDraftId`, taken from the snapshot in `open()`), it calls `router.navigate([], { relativeTo: route, queryParams: { draft }, replaceUrl: true })`.
- This covers the first create, a moved draft and a reply/forward start. The reply-specific navigate in `open()` was folded into this.
- `showInUrl` does nothing once the page is left (new `left` flag set on destroy, so a save on leaving cannot pull the user back) or the draft is finished.
- The page still reads its query snapshot once, so it is not re-initialised.

Validation:
- New: `puts a new draft into the URL once it is first saved, and only then` — Pass
- New: `leaves the URL of an opened draft as it is` — Pass
- New: `puts the moved draft into the URL` — Pass
- New: `does not touch the URL when the draft is first saved on leaving the page` — Pass
- Existing reply test (`navigate([], {queryParams: {draft}, replaceUrl: true})`) — Pass

### CR-4 — Missing tests for edits during a save and for sender move failure and race

Files changed:
- `web/src/app/features/mail/compose/mail-compose-page.spec.ts`
- `web/src/app/features/mail/compose/file-picker-dialog.spec.ts`
- `web/src/app/features/mail/compose/mail-compose.service.spec.ts`

Problem:
These paths had no tests. The test-writer has since covered edits made during an in-flight save (the `writes in flight` describe) and added TW-1 and TW-2.

Fix:
Added the tests listed under CR-1, CR-2, CR-3 and CR-5.

Validation:
- Mail suite — 392 passed

### CR-5 — The Files picker silently shows only the first 200 entries

Files changed:
- `web/src/app/features/mail/compose/mail-compose.service.ts`
- `web/src/app/features/mail/compose/file-picker-dialog.ts`
- `web/src/app/features/mail/compose/file-picker-dialog.spec.ts`
- `web/src/app/features/mail/compose/mail-compose.service.spec.ts`
- `web/src/app/features/mail/compose/mail-compose-page.spec.ts` (fake service return type only)

Problem:
`files()` asked for 200 entries and dropped `nextCursor`, so larger folders were cut off with no hint.

Fix:
- `files()` now returns `FolderListing { entries, more }`, where `more` is `!!nextCursor`. The limit is the new constant `FILES_LIMIT = 200`.
- The dialog shows the note "Only the first 200 entries of this folder are shown." (`@@mailCompose.pickMore`) when `more` is true.
- No "Load more" button was added, to keep the change small.

Validation:
- New service test `says when a drive folder holds more entries than listed`, and the root listing test now checks the limit and `more: false` — Pass
- New dialog test `says when a folder holds more entries than listed, and not for a complete one` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include 'src/app/features/mail/**/*.spec.ts'` — Pass: 18 files, 392 tests passed (before the fixes: 380 passed, 2 failed)

Full test suite:
- `cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build) — Pass: 155 files, 3161 tests passed, build succeeded. The build prints the initial-bundle budget warning (676.66 kB > 500 kB). That bundle is the app's initial chunk, and these changes don't touch it.

Lint/type-check/build:
- `cd web && npx ng lint --max-warnings=0` — Pass: all files pass linting
- `cd web && npx prettier --check src/app/features/mail` — Pass
- `cd web && npx ng build` — Pass, with the same budget warning

`.taskcheck`:
- Not run. It includes Gradle, which this helper does not run. The parent runs it.
- Playwright (`web/e2e/mail-compose/compose.spec.ts`) was not run, by instruction. It clicks the Send button by role and name, which still works with `type="button"`.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
