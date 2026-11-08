# Fix Report

Date: 2026-11-08

Task: mail-load-older-ui — load older messages from the server (web and Android UI)

Scope: current task; findings CR-1, CR-2, CR-3 from `reviews/mail-load-older-ui/review.md` and TW-1, TW-2 from `reviews/mail-load-older-ui/tests.md`. Production fixes were made in commit 91ab39ebc by an earlier, interrupted fixer run; this run verified them against the current code, added one missing regression test and ran the validation.

## Fixed findings

### CR-1 / TW-1 — loadingOlder stays true after load()/restore()

Files changed:
- `web/src/app/features/mail/list/mail-list-store.ts` (91ab39ebc)
- `web/src/app/features/mail/list/mail-list-store.spec.ts` (91ab39ebc)

Problem:
`load()` and `restore()` bumped `loadSeq`, so the running `loadOlder()` skipped its `finally` reset and `loadingOlder` stayed true, blocking the button.

Fix:
New `resetOlder()` (called by `load()` and `restore()`) sets `loadingOlder`, `olderError` and `olderNote` back. Regression test "ends the progress when the list is reloaded or restored while it loads older".

Validation:
- `npm --prefix web run check` — Pass

### CR-2 — loadOlder overwrites optimistic action state

Files changed:
- `web/src/app/features/mail/list/mail-list-store.ts` (91ab39ebc)
- `web/src/app/features/mail/list/mail-list-store.spec.ts` (91ab39ebc, plus one test added in this run)

Problem:
After the server load, `loadOlder()` re-read the list and set `items` without regard to running actions, undoing optimistic changes (e.g. an archived message reappearing).

Fix:
New `showUpTo()` follows the `refresh()` pattern: waits for `running === 0` via `actionsSettled()`, records `mutationSeq` before reading, and reads again if an action ran or changed the list meanwhile. Waiters are released in `apply()` after `running--` (which is in `finally`), so they cannot hang longer than the running action; if `load()`/`restore()` happens while waiting, the waiter returns on the `loadSeq` check without reading or showing anything. Tests: "does not undo an action that ran while it read the list...", "reads the list again when an action changed it while it was read" (91ab39ebc) and, added in this run, "ends when the list is reloaded while it waits for an action" (checks the promise resolves, no extra list read, progress ended).

Validation:
- `npm --prefix web run check` — Pass

### CR-3 — "nothing older" shown as an error with the failure prefix

Files changed:
- `web/src/app/features/mail/list/mail-list-store.ts` (91ab39ebc)
- `web/src/app/features/mail/list/mail-list-page.html` (91ab39ebc)
- `web/src/app/features/mail/list/mail-list-page.spec.ts` (91ab39ebc)

Problem:
Zero loaded messages was put into `olderError` and rendered as "Nothing older loaded: There are no older messages on the server." in error styling.

Fix:
Separate `olderNote` signal rendered as a neutral `p.note` with `role="status"`; failures keep the prefixed `.error`. Page tests assert both.

Validation:
- `npm --prefix web run check` — Pass

### TW-2 — messages.de.xlf missing calendar.invite.* units and outdated capture.save

Files changed:
- `web/src/locale/messages.de.xlf` (91ab39ebc)
- `web/src/locale/messages.xlf` (91ab39ebc, re-extracted)

Problem:
German catalog lacked the 16 `calendar.invite.*` units and had an outdated `capture.save` source, failing `messages-de.spec.ts`.

Fix:
16 German units added (16 in both catalogs now); `capture.save` source matches ("Save to Inbox").

Validation:
- `npm --prefix web run check` (includes `web/src/locale/messages-de.spec.ts`) — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- Store and page specs ran as part of `npm --prefix web run check` (vitest via `ng test`; the spec files cannot be run directly with `npx vitest`, which lacks the Angular test globals).

Full test suite:
- `npm --prefix web run check` (api:check, lint, e2e:typecheck, test:ci, ng build) — Pass; 193 test files, 4550 tests passed. `ng build` only prints the existing initial bundle budget warning.

Lint/type-check/build:
- Included in `npm --prefix web run check` — Pass

`.taskcheck`:
- Not run (left to the parent worker; Android and server not changed by this run)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
