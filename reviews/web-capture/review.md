# Code Review

Date: 2026-10-21

Task: web-capture - Quick capture dialog

Scope: the final committed code of task web-capture, `git diff 814f9162..5b4d21b1 -- web`. The commits are 368b8e94 (dialog), ef8659ac (e2e spec), b009c1e7 (more tests, first review), b8e3f934 (stale preview fix) and 5b4d21b1 (fix for the second review's CR-1, input typed during a Shift+Enter save). Shell commands were denied in this session, so `git diff`/`git show` could not be run. The parent supplied the commit range, and the files were read directly from the working tree: `web/src/app/features/capture/capture-dialog.ts`, `capture-dialog.html` and `capture-dialog.spec.ts`. The production files are taken to match 5b4d21b1, as fixes.md states. The spec file may contain uncommitted test-writer additions. Files the last commit did not change (service, providers, uuidv7, scss, `web/e2e/capture/capture.spec.ts`) were reviewed in full in the previous review of b8e3f934 and had no findings. Playwright was not run, because the parent runs it.

Requirements:
- tasks/web-capture.md acceptance criteria and its recorded assumptions
- SPEC.md section 4.5 (quick capture), as referenced by the task
- docs/ARCHITECTURE.md and AGENTS.md project notes
- reviews/web-capture/fixes.md (the fix for the second review's CR-1)

Git range:
- Branch: main
- HEAD: 5b4d21b152aa6e148b72186450085892c7d669d4
- Base/start: 814f916218670ae23183382e41c9241a72dd706a
- Commits reviewed: 5

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

Checked and found in order:
- Fix for the previous CR-1 (`capture-dialog.ts:136-169`): `submit()` saves the raw text, raw notes and file list before the `await`. After a successful keep-open save it clears text and notes only if they still equal those raw values. Files are removed with the module-level `without()` helper (`capture-dialog.ts:178-185`), which takes away one entry per submitted name. Duplicate names are handled correctly, and files added during the save remain. The draft id is replaced after every successful save, so text kept from that window is saved later under a fresh UUIDv7. The close path (Enter) and the failure path (draft and id kept, `saving` reset in `finally`) are unchanged. One edge case: if the user changes the text and then changes it back to exactly the submitted value during the save, it is still cleared. That text is identical to what was just saved, so no input is lost.
- The new Vitest case (`capture-dialog.spec.ts:322-351`) covers text typed and a file pasted while the save is running, notes that did not change, the saved count, and a follow-up Shift+Enter that sends the kept text under a new id. It asserts behavior, not implementation details. Fake timers stop the debounced preview from firing, so `http.verify()` stays clean.
- Criteria 1-3: the previous review's conclusions still hold. The debounced, silent preview never shows a reading of an older text. The paste/drop placeholder never sends files. Enter saves and closes, and Shift+Enter saves and keeps the dialog open. The UUIDv7 id is kept across failed saves. The `saving` guard blocks double submits. Vitest covers preview, save and double-submit protection. `npm run check` was not run in this review, because the parent runs it and `.taskcheck`.
- Criterion 4: the e2e spec was not changed by the last commit. The parent's Playwright run against the e2e stack must provide the evidence.
- Architecture, integration and security: the change stays inside `features/capture`, adds no dependencies, renders no unsafe HTML and logs nothing.

## Review conclusion

No material issues found.
