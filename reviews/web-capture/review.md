# Code Review

Date: 2026-10-21

Task: web-capture - Quick capture dialog

Scope: the final committed code of task web-capture, `git diff 814f9162..b8e3f934`: commits 368b8e94 (dialog), ef8659ac (e2e spec), b009c1e7 (more tests, first review) and b8e3f934 (fix for the earlier CR-1, a stale preview). The reviewer could not run shell commands in this session, so the parent supplied the range and the files were read directly: everything under `web/src/app/features/capture/` (dialog ts/html/scss, `capture.service.ts`, `capture.providers.ts`, `uuidv7.ts`, `capture-dialog.spec.ts`) and `web/e2e/capture/capture.spec.ts`. A test-writer may be editing spec files in the working tree at the same time, so the spec content reviewed here may contain uncommitted additions. The production files are taken to match b8e3f934. Playwright was not run, because the parent runs it.

Requirements:
- tasks/web-capture.md acceptance criteria and its recorded assumptions
- SPEC.md section 4.5 (quick capture), as referenced by the task
- docs/ARCHITECTURE.md and AGENTS.md project notes (feature folders, i18n, theme tokens, generated API client, error handling)
- web/src/app/features/README.md and web/src/app/shell/shell-tokens.ts (the `CAPTURE_DIALOG` contract)
- reviews/web-capture/fixes.md (the fix for the earlier CR-1 and CR-2)

Git range:
- Branch: main
- HEAD: b8e3f934
- Base/start: 814f916218670ae23183382e41c9241a72dd706a
- Commits reviewed: 4

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Shift+Enter wipes text typed while the save is still running**
    - File: `web/src/app/features/capture/capture-dialog.ts:149-152` (with the text input in `capture-dialog.html:18-32`, which stays editable while `saving()` is true)
    - Category: Correctness
    - Problem: `submit(true)` reads `text()` and `notes()` before the `await`. When the save resolves, it calls `this.text.set('')`, `this.notes.set('')` and `this.files.set([])` without checking for changes. The input is not disabled or read-only during the save. If the user presses Shift+Enter and starts typing the next capture before the server answers (the "save and add another" flow invites exactly this), those characters, and any notes or files added in the meantime, are discarded silently when the response arrives.
    - Why it matters: the dialog loses user input in the rapid-capture flow that the Shift+Enter criterion exists for. The window lasts one save round trip, so it is small on a LAN and grows on slow or mobile connections.
    - Suggested fix: on success, clear a field only if it still holds the value that was submitted. For example, compare `this.text().trim() === text` and `this.notes().trim() === notes` before resetting, and only remove the file names that existed at submit time. Another option is to make the fields read-only while `saving()` is true. Add a Vitest case: Shift+Enter, type into the field, flush the save, and assert that the new text survives.

Checked and found in order:
- Criterion 1: the preview is debounced (250 ms), trimmed, deduplicated and silent (`SILENT_ERRORS`). `switchMap` drops older requests. The CR-1 fix from the earlier review is correct: `previewResult` carries the text it was computed for, and the `preview` computed shows a reading only when that text equals `text().trim()`. A stale, failed, or cleared preview therefore falls back to the syntax hint. Returning to an earlier text inside the debounce window correctly shows that text's reading again. Notes are optional, and empty or whitespace-only notes are left out. The paste/drop placeholder lists file names, never reads file contents, and never sends `fileIds`. Enter saves and closes with the task. Shift+Enter saves, clears the fields, refocuses and counts. IME composition is respected. Ctrl/Cmd+Enter in the notes field is a recorded assumption.
- Criterion 2: `uuidv7.ts` follows RFC 9562 (48-bit big-endian milliseconds, version nibble 7, variant bits 10, `crypto.getRandomValues`). The draft id is kept after a failed save, replaced only after a success, and the `saving` guard blocks a double submit (by key, button or a direct `submit()` call).
- Criterion 3: Vitest covers the preview (debounce, rendering, failure, out-of-order and failing older responses, a stale reading after an edit and after Shift+Enter), save (Enter, Shift+Enter, button, blank text, double submit, retry with the same id, interceptor-reported errors), the attachment placeholder, and the dialog without a `MatDialogRef`. `npm run check` was not re-run in this review. fixes.md records it as passing on the fixed code.
- Criterion 4: `web/e2e/capture/capture.spec.ts` opens the dialog with `Q`, saves two captures with Shift+Enter and one with Enter, checks the live preview, and asserts that the inbox list and the API each hold exactly one item per capture. The criterion's evidence must come from the parent's Playwright run against the e2e stack.
- Architecture and integration: the code stays inside `features/capture`. `CAPTURE_DIALOG` is provided by `provideCapture()` and the dialog is lazily imported. Calls go through the generated client in `CaptureService`. Strings are i18n-tagged, and styles use only theme tokens. Save errors are reported once by the global interceptor.
- Security: no unsafe HTML binding, no file contents read or sent, and nothing sensitive logged.

## Review conclusion

No material issues found.
