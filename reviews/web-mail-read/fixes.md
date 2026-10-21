# Fix Report

Date: 2026-10-21

Task: web-mail-read - Mail reading view

Scope: current task. The findings are CR-1..CR-6 from `reviews/web-mail-read/review.md` and the two observations under "Bugs found" in `reviews/web-mail-read/tests.md`. Base commit 1223f488. Most fixes are in commit 9e7fc450, from an earlier fixer session that was interrupted. This session reviewed that commit and added one regression test, which is uncommitted in the working tree. All changes are inside `web/src/app/features/mail/read/`.

## Fixed findings

### CR-1: Mail frame can grow but never shrink

Files changed:
- `web/src/app/features/mail/read/mail-frame.ts`
- `web/src/app/features/mail/read/mail-frame.spec.ts`

Problem:
`resize()` measured `documentElement.scrollHeight`. In standards mode that value is never smaller than the frame's own height, so the frame could grow but never shrink.

Fix:
`resize()` now measures `contentDocument.body.scrollHeight`. The body has `margin:0`, so this is the content height. A new spec test changes the body height from 900 to 300 while the root stays at 900, then checks that the frame shrinks to 300px after the ResizeObserver fires.

Validation:
- `cd web && npm run check`: Pass

### CR-2: A text preview from a previous message can show under the current message

Files changed:
- `web/src/app/features/mail/read/mail-read-page.ts`
- `web/src/app/features/mail/read/mail-read-page.spec.ts`

Problem:
A finished text preview was applied if the current preview had the same `partId`. Part ids repeat across messages.

Fix:
`showPreview` keeps the `Preview` object it created and applies the result only while `this.preview() === pending`. A newer preview (on any message) or a navigation, which clears the preview, therefore discards a late answer, whether it succeeds or fails. Two tests cover this: a late success and a late failure of message A's part "4" while message B's part "4" is being previewed.

Validation:
- `cd web && npm run check`: Pass

### CR-3 (and tests.md observation 1): For an allowlisted sender, a failed proxied load fails the whole view

Files changed:
- `web/src/app/features/mail/read/mail-read-page.ts`
- `web/src/app/features/mail/read/mail-read-page.spec.ts`

Problem:
For an allowlisted sender, `open()` loads the message a second time with `PROXY`. If that second request failed, the outer catch set the view to `failed` and threw away the blocked copy, which had loaded fine.

Fix:
The second request now has its own try/catch. On failure, the page shows the blocked copy, leaves `loadImages` false (so the "Load images" banner offers a retry) and reports the error through `notifications.error`. The catch checks the same `seq` and `destroyed` guards as the other load paths. Tests:
- (from 9e7fc450) the blocked copy is shown, there is no alert, the notification fires, and "Load images" then loads the proxied copy;
- (added in this session) if that failure arrives after the user has moved to another message, it is ignored: no notification, and the new message stays shown. This covers the stale-load guard of the new catch branch.

Validation:
- `cd web && npm run check`: Pass

### CR-5: Changing the route parameter and stale load responses are untested

Files changed:
- `web/src/app/features/mail/read/mail-read-page.spec.ts`

Problem:
No test covered the `seq` guard against out-of-order answers after the `:id` changes.

Fix:
The "navigating to another message" block emits a second id through `params` and uses deferred promises. It covers:
- the loading state while the next message loads;
- an older load that answers or fails after the newer one;
- proxied images of the previous message, whether they arrive or fail;
- the preview closing on navigation;
- the CR-2 and CR-3 stale cases;
- the per-message saving state.

I checked that the guard paths named in the finding (the initial `get` and the `showImages` result after navigation) are covered, and that all tests pass.

Validation:
- `cd web && npm run check`: Pass

### CR-6: Read view imports an internal helper of the files feature

Files changed:
- `web/src/app/features/mail/read/format-bytes.ts` (new)
- `web/src/app/features/mail/read/format-bytes.spec.ts` (new)
- `web/src/app/features/mail/read/mail-read-page.ts`

Problem:
`formatBytes` was imported from `features/files/upload/format-bytes`, an internal file of another feature.

Fix:
The mail read feature now has its own small `formatBytes`, with the same behavior and its own spec. Moving it to `shared/` would touch the files feature, which is outside this task's touches. The read view no longer depends on the files feature's internals.

Validation:
- `cd web && npm run check`: Pass

### tests.md observation 2: `saving` set not keyed per message

Files changed:
- `web/src/app/features/mail/read/mail-read-page.ts`
- `web/src/app/features/mail/read/mail-read-page.html`

Problem:
`saving` held bare part ids. After navigating, the same part id on the next message stayed disabled until the earlier save finished.

Fix:
The `saving` keys are now the message id and part id joined by a slash (`savingKey`). The template uses `isSaving(part)`, which checks the shown message's id. The double-save guard per attachment still works (the existing test "saves a part once while saving, then reports a failure and allows a retry" passes). A new test checks that the next message's part "4" can be saved while message A's save is still running, and that each button turns enabled again only when its own save ends.

Validation:
- `cd web && npm run check`: Pass

## Not fixed

### CR-4: "Save to Files" target folder not reconciled with SPEC.md §8.2

Status:
Out of scope

Reason:
Deferred to the new task `mail-attachment-to-files`, as the parent worker decided. Picking `/Attachments/Mail` depends on the Files API and is tracked there. The code was not changed.

## Validation summary

Focused tests:
- `web/src/app/features/mail/read/*.spec.ts` ran inside `npm run test:ci`: Pass. This includes the new test "ignores a failed proxied load for an allowed sender that answers after navigating".

Full test suite:
- `cd web && npm run test:ci`: Pass (119 test files, 2353 tests)

Lint/type-check/build:
- `cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build): Pass. `ng lint` reports "All files pass linting." `ng build` prints a warning that the initial bundle exceeds the 500 kB budget (657.71 kB). The warning concerns the initial chunks and not the lazy mail chunk, and the build still succeeds.

`.taskcheck`:
- Not run by the fixer. `scripts/check.sh` also runs the server and Android parts, which this fix does not touch. The web part of it, `npm run check`, passed. The parent worker runs `.taskcheck` before `done`.

Remaining unresolved findings:
1 (CR-4, deferred to `mail-attachment-to-files`)

## Final status

All validated findings fixed and validation passed. CR-4 is deferred out of scope to `mail-attachment-to-files`.
