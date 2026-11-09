# Code Review

Date: 2026-11-09

Task: web-file-links-nopreview - Web: linked items for files without a preview

Scope: committed task changes `git diff ff3981898 f3fff539f` (identical web code to the rebased HEAD 7959c4179, checked with `git diff --stat f3fff539f 7959c4179 -- web`, which is empty)

Requirements:
- tasks/web-file-links-nopreview.md (acceptance criteria and recorded assumption)
- SPEC.md §4.3 (links everywhere, Android parity), §8.2 (file preview)
- AGENTS.md project notes (i18n, conventions, e2e selectors)
- Parent instructions: correctness, a11y, i18n, tests, e2e selectors

Git range:
- Branch: main
- HEAD: 7959c4179 (rebased task commit; reviewed code commit f3fff539f)
- Base/start: ff3981898
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | openPreview doc comment no longer describes the single-file case**
    - File: `web/src/app/features/files/preview/preview-dialog.ts:147`
    - Category: Quality
    - Problem: The comment still reads "Opens the preview of `id`, paging through the previewable ones of `files`." Since this change, when `id` has no preview type the dialog shows only that file (`previewableOrAlone`, line 141) and does not page. `PreviewDialogData.files` (line 24) and the class comment were updated, but the exported entry point's comment was not.
    - Why it matters: `openPreview` is the function callers read; the comment suggests a non-previewable id would be skipped in favour of the folder's previewable files, which is the old behavior.
    - Suggested fix: Extend the comment, e.g. "...; a file without a preview is shown alone (notice, download, linked items)."

## Notes (no findings)

- Correctness: `previewableOrAlone` returns just the clicked file when it has no preview kind, so `kind()` is `null`, `@case (null)` renders the notice, the header keeps Linked items, Download and Close, and paging controls are hidden (`files.length > 1`). ArrowLeft/ArrowRight are no-ops with one file. An unknown id still falls back to the first previewable file, and an empty list still renders nothing; both are covered by the updated dialog spec. A null or missing mime yields `previewKind` null and therefore the notice, which is the intended fallback.
- The linked-items panel for the file uses `{ type: 'FILE', id: f.id }`, unchanged from gtd-links-everywhere; the dialog spec verifies the `/api/v1/links` request carries the zip id.
- Files page: every file row is now a `button.open` that calls `preview(entry)`; non-previewable files show the generic file icon instead of a thumbnail, so no thumbnail request is made for them. Download stays in the row menu (`files.download`) and in the dialog. This matches the recorded assumption.
- A11y: the row button has an accessible name ("Open NAME" / "Preview NAME"); the notice uses `role="status"` like the existing loading note; the dialog keeps `ariaLabelledBy: 'preview-title'`.
- i18n: `files.openFile`, `files.preview.unsupported` and `files.preview.downloadFile` are in both `messages.xlf` and `messages.de.xlf` with German targets; the removed `files.downloadFile` is gone from both. The `messages.xlf` diff otherwise only shifts line numbers (no units from other tasks were added).
- E2E selectors: grepped `web/e2e` for `a.open`, row link/button names and `Download` locators. File rows are only addressed by `Preview invoice.pdf` (button, unchanged) and `.entries .entry .name` (still present); the `a.open` uses are mail rows. The files-browser e2e downloads through the row menu, which is unchanged. No e2e locator relies on the removed "Download NAME" row link.
- Style budget: `preview-dialog.scss` is 3361 bytes source, under the 4 kB component warning.
- Tests: the committed `files-page.spec.ts` change checks the label and icon of the non-previewable row but does not click it; uncommitted working-tree additions (by the test-writer, outside this committed scope) add a click-through test and a German locale check. Not reported as a finding since the dialog behavior itself is covered by the committed `preview-dialog.spec.ts` and the click handler is shared with the previewable path.
- Evidence: `tasks.py verify` shows criterion 2 recorded, criteria 1 and 3 not yet recorded; the parent worker still has to record them.

## Review conclusion

No material issues found.
