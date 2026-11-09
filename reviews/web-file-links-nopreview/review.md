# Code Review

Date: 2026-11-09

Task: web-file-links-nopreview - Web: linked items for files without a preview

Scope: committed task changes `git diff ff3981898 3ad50d319` (round 2). Round 1 reviewed the production code at 7959c4179; this round re-reads the full range and focuses on what changed since: the test-writer commit ba206fb64 (files-page.spec.ts, preview-dialog.spec.ts, messages-de.spec.ts) and the fixer commit 3ad50d319 (preview-dialog.ts doc comment).

Requirements:
- tasks/web-file-links-nopreview.md (acceptance criteria and recorded assumption)
- SPEC.md §4.3 (links everywhere, Android parity), §8.2 (file preview)
- AGENTS.md project notes (i18n, conventions, e2e selectors)
- reviews/web-file-links-nopreview/fixes.md (CR-1 fix)
- Parent instructions for round 2

Git range:
- Branch: main
- HEAD: 3ad50d319
- Base/start: ff3981898
- Commits reviewed: 5 (83e655a52 and 024077eae are task-board only; 7959c4179 implementation; ba206fb64 tests and reports; 3ad50d319 CR-1 fix)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Round-1 finding status

- CR-1 (P3, openPreview doc comment no longer describes the single-file case): resolved. `web/src/app/features/files/preview/preview-dialog.ts:147-150` now says the dialog pages through the previewable files and that a file without a preview is shown alone (notice, download, linked items), which matches `previewableOrAlone`. The commit changes only the comment; no behavior changed.

## Notes (no findings)

- Production code: the only production change since round 1 is the CR-1 comment. `git diff 7959c4179 3ad50d319 -- web` shows only the comment plus the three spec files. The round-1 correctness, a11y, i18n, e2e selector and style budget observations still apply.
- files-page.spec.ts: the new test clicks the "Open Cards.zip" row in a folder that also has two images and checks the result. The dialog shows that file alone (no `.position`, no `button.step`, ArrowRight does nothing), shows the notice with a download link to `/api/v1/files/{id}/content`, and offers the Linked items toggle. No request starts with `/api/v1/files/{zip id}`, so opening it neither downloads the file nor fetches a preview. This covers the gap noted in round 1, where the row click was not exercised, and it covers criterion 1 end to end at unit level.
- preview-dialog.spec.ts: the DOCX test covers a second unsupported MIME type. It checks that `kind()` is null, there is no viewer element, both download links (header and notice) point to the content URL, the dialog shows the file alone, and Close works. `http.verify` in afterEach checks that no preview was requested. The other new test checks that opening on a previewable file still skips the zip while paging, which guards the unchanged path against regression.
- messages-de.spec.ts: the new test checks the English sources and German targets of the three new units. It also checks that the removed `files.downloadFile` unit is gone. The placeholder rendering in the expected strings uses the same notation as the existing `mail.olderFailed` test in that file.
- The tests assert visible behavior: DOM text, accessible names, hrefs and HTTP traffic. They do not test internals, and no existing test was weakened or removed in the range.
- Evidence: criterion 2 is recorded. Criteria 1 and 3 are still unticked in the task file, so the parent worker must record them with `tasks.py verify`, including a `sh scripts/check.sh` run for criterion 3. The fixer reports focused files-feature tests passing (13 files, 238 tests). I did not re-run tests in this round.

## Review conclusion

No material issues found.
