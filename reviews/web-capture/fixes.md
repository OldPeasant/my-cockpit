# Fix Report

Date: 2026-10-21

Task: web-capture - Quick capture dialog

Scope: current task, finding CR-1 of `reviews/web-capture/review.md` (second review, of HEAD b8e3f934; base 814f9162). The fixes for the first review's CR-1 and CR-2 are in the git history of this file.

## Fixed findings

### CR-1 — Shift+Enter wipes text typed while the save is still running

Files changed:
- `web/src/app/features/capture/capture-dialog.ts`
- `web/src/app/features/capture/capture-dialog.spec.ts`

Problem:
After a successful Shift+Enter save, `submit()` cleared text, notes and files without any check. The fields stay editable during the save, so anything typed, written or pasted while the request was running was discarded when the response arrived.

Fix:
`submit()` now keeps the raw text, raw notes and the file list as they were at submit time. On a successful keep-open save, it clears the text only if `text()` still equals the submitted raw value, and the notes only if `notes()` still equals theirs. For files, it removes only the submitted entries: a small module-level `without()` helper removes one entry per submitted name, so files added during the save stay. The draft id is still replaced after every successful save, so the surviving text becomes a new capture with a new id. Enter (close) and failure paths are unchanged.

A Vitest case was added: `Shift+Enter keeps text, notes and files added while the save is running`. It presses Shift+Enter on "First" with notes, then types "Second" and pastes a file before the save resolves. After flushing the save, it checks that "Second" and the pasted file survive, that the unchanged notes are cleared and that the count is 1. A second Shift+Enter then sends "Second" with a new UUIDv7 id, after which the fields are empty. Against the previous code the test fails (`expected '' to be 'Second'`; checked by temporarily restoring the HEAD version of `capture-dialog.ts`). With the fix it passes.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/capture/*.spec.ts'` — Pass (4 files, 50 tests)
- `cd web && npm run lint` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/capture/*.spec.ts'` — Pass, 4 files, 50 tests
- Regression check: with the HEAD `capture-dialog.ts` restored temporarily, the new test fails (1 failed, 49 passed). The fix was then restored and the suite passes again.

Full test suite:
- Not run by the fixer (the parent runs `npm run check` and `.taskcheck`)

Lint/type-check/build:
- `cd web && npm run lint` — Pass ("All files pass linting.")
- Type check: the Angular test build compiles the changed files without errors. Full `ng build` was not run.

`.taskcheck`:
- Not run (left to the parent worker)

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
