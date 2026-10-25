# Fix Report

Date: 2026-10-25

Task: bookmarks-snapshot - Readable snapshots

Scope: current task, finding CR-1 of `reviews/bookmarks-snapshot/review.md` (approved approach from the parent worker); CR-2 and CR-3 examined only

## Fixed findings

### CR-1 — Page-wide wrappers are removed before scoring and nothing falls back

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/ReadableExtractor.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/snapshot/ReadableExtractorEdgeCaseTests.kt`

Problem:
`mainContent` removed every `<form>` (in `NEVER_CONTENT`) and every element with an "unlikely" class/id before scoring, together with its whole subtree. A wrapper such as ASP.NET's `<form id="aspnetForm">` around the body or `<div class="layout has-sidebar"><article>...</article></div>` took the main content with it, which gave a 502 `NO_CONTENT` or a truncated snapshot. Confirmed in the code before the change: `KEEP_TAGS` protected only `article`/`main` elements themselves, not their ancestors.

Fix:
- `form` moved out of `NEVER_CONTENT` (form controls `input`, `button`, `select`, `textarea` are still always removed). Forms and unlikely class/id elements are now removed in a separate step that is enabled by a `stripUnlikely` flag and that keeps an element when it is or contains `article`, `main` or `[role=main]` (new `wrapsMain`, constant `MAIN_ELEMENTS`). The unlikely-role removal (`UNLIKELY_ROLES`) stays in both passes, with the same `wrapsMain` guard. `unlikely()` now checks only class/id.
- Like Mozilla Readability: when the sanitized result has fewer than `MIN_TEXT_LENGTH` (500) characters, `extract` parses the page again and runs `mainContent` with `stripUnlikely = false`. The longer result wins, and on a tie the first result is kept. Scripts, styles, iframes, hidden elements and the rest of `NEVER_CONTENT` are still removed in the retry. Sanitization is unchanged: both results go through the same `Cleaner(SAFELIST)` and `removeEmpty` (extracted into `cleaned`). The Cleaner drops a kept `<form>` tag but keeps the text inside it. The title is still taken from the first parse.
- KDoc of the object and of `mainContent` updated. The existing test that expects no `<form` in the output still passes.
- Four regression tests were added. A form wrapper around an article checks that the text is kept and that no `<form` or `__VIEWSTATE` appears in the output. A form wrapper without an article checks the fallback pass. An unlikely-class wrapper around an article checks that the article is kept and that the sidebar inside the wrapper is still dropped. An unlikely-class wrapper without a main element checks the fallback pass.

Validation:
- `server/gradlew -p server ktlintFormat` — Pass
- `server/gradlew -p server ktlintCheck -q` — Pass
- `server/gradlew -p server test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*'` — Pass (50 tests: 8 + 12 + 12 + 18, 0 failures, 0 skipped; 46 existing tests plus 4 new)

## Not fixed

### CR-2 — Snapshots are silently truncated at the metadata fetch limit

Status:
Out of scope (for this fixer)

Reason:
The parent worker did not approve a fix. The parent decides on it, either by recording it as an assumption or by accepting it as a follow-up. The suggested fixes (a separate size limit or a truncation flag on `FetchedPage`) are outside this task's touched files.

### CR-3 — The snapshot service duplicates BookmarkService's change bookkeeping

Status:
Out of scope (for this fixer)

Reason:
The parent worker did not approve a fix. The parent decides on it (accepted as is, or a follow-up). `BookmarkSnapshotService.kt` was not changed.

## Validation summary

Focused tests:
- `server/gradlew -p server test --tests 'ch.sonensei.cockpit.bookmarks.snapshot.*'` — Pass (50 tests)

Full test suite:
- Not run by the fixer. The parent worker runs `server/gradlew -p server check`.

Lint/type-check/build:
- `server/gradlew -p server ktlintFormat` — Pass; `server/gradlew -p server ktlintCheck -q` — Pass; compilation as part of the test run — Pass

`.taskcheck`:
- Not run. It is slow and the parent worker runs it before `done`.

Remaining unresolved findings:
2 (CR-2, CR-3, left to the parent worker to decide)

## Final status

All validated findings fixed and validation passed. CR-1 is fixed. CR-2 and CR-3 were not in the approved scope and are left for the parent worker to decide.
