# Code Review

Date: 2026-10-25

Task: web-journal - Journal UI

Scope: re-review of the whole task on the final code, `git diff 50b5c1b7f HEAD -- web` (production code, Vitest specs, Playwright spec, shell spec edits), with focus on the only code change since the previous review: commit d35d1d13e (6 new tests in `web/src/app/features/journal/journal-page.spec.ts`). `git diff --stat f9601e8c6 HEAD` confirms no production code changed after the last fix commit (only `reviews/`, `tasks/web-journal-rollover.md` and the page spec).

Requirements:
- tasks/web-journal.md acceptance criteria and recorded assumptions (Notes, including CR-5/CR-6 of the first review accepted as P3 follow-ups)
- tasks/web-journal-rollover.md (follow-up task for the accepted findings)
- SPEC.md §8.5 (Journal), §4.2 (ETag/If-Match)
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project conventions
- Previous review of this task (CR-1/CR-2 below are carried over unchanged)

Git range:
- Branch: main
- HEAD: d35d1d13e9f69a16f15d04cbf09e9e2dfd30068a
- Base/start: 50b5c1b7f
- Commits reviewed: 9 task commits (ce5575b03, 333f9ba8a, 65997de45, 053c3d566, 90aa5dcdd, f9601e8c6, c26db2c96, b19f77104, d35d1d13e); the other commits since the base only touch `tasks/`
- Uncommitted changes: none at review time
- Note: the hashes given in the review request (13bcad8fb..974f46385, HEAD 7c10bbfd5) are not in the current history; the branch was rewritten (rebase/merge onto main). The commit contents match the description (last commit adds the tests, production code unchanged), so the review covers the current HEAD above.

Commands run during this review:
- `npx ng test --watch=false --include 'src/app/features/journal/**/*.spec.ts'` (in `web/`) -> 5 files, 93 tests passed
- `npx eslint src/app/features/journal` (in `web/`) -> no findings
- `npx prettier --check src/app/features/journal e2e/journal` (in `web/`) -> all files formatted
- Playwright not re-run by the reviewer (parent reports `npm run e2e -- journal/journal.spec.ts` against the compose e2e stack -> 31 passed, recorded as criterion 1 evidence in the task file)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | "Today" does not move on at midnight in a page left open (carried over; disposition: deferred to web-journal-rollover)**
    - File: `web/src/app/features/journal/journal-page.ts:143`, `web/src/app/features/journal/journal-page.ts:334`
    - Category: Correctness
    - Problem: `today` is set from the heatmap, which `load()` fetches only while `today()` is null. The page instance is kept for every day, so `today` is loaded once for the lifetime of the page. A tab left open overnight still labels the previous day "Today", "Next day" stays disabled and `/journal` opens yesterday's entry.
    - Why it matters: Contradicts "'Today' opens today's entry" for a page that is often left open. Still present at HEAD; recorded in the task Notes and covered by `tasks/web-journal-rollover.md` criterion 1.
    - Suggested fix: Refresh heatmap and `today` on `visibilitychange` or a timer, and reopen the day when it was today (as planned in web-journal-rollover).

- [ ] **CR-2 | Edits in conflict are dropped without a prompt when the user leaves the day (carried over; disposition: deferred to web-journal-rollover)**
    - File: `web/src/app/features/journal/journal-page.ts:225`, `web/src/app/features/journal/journal-page.ts:290`
    - Category: Correctness
    - Problem: `flush()` returns without saving while `saveState === 'conflict'`, and `open()` keeps the user on the day only for `'error' && dirty()`. In conflict state (409/412/428) moving to another day shows that day and drops the edits.
    - Why it matters: The banner says the edits are not saved yet, but one click on another day discards them. Still present at HEAD; recorded in the task Notes and covered by `tasks/web-journal-rollover.md` criterion 2.
    - Suggested fix: Treat `'conflict' && dirty()` like `'error'` in `open()` or prompt (keep mine / discard / stay), as planned in web-journal-rollover.

## New tests in d35d1d13e, checked

- "puts an image in the new editor when its upload ends after going back from Preview": asserts the image replaces the placeholder exactly once in the re-created editor, a single PUT with `If-Match: "2"`, no further autosave and state "Saved". Exercises `settleUpload` returning early when the editor already replaced the placeholder (`journal-page.ts:324`).
- "saves an image still uploading when the journal is left": asserts the first PUT on leaving has no upload placeholder, navigation completes, the PUT after the upload ends carries the image with `If-Match: "3"`, and no autosave runs after destruction (`journal-page.ts:307-312`, `:328`).
- "stays on today when its save failed": asserts the URL stays `/journal`, no GET for the previous day, the text is kept and state "Not saved" (`journal-page.ts:290-295`).
- "opens only the last day chosen while the save of the day left is running": asserts the intermediate day is never loaded, the same page instance shows the last route, and no extra PUT (`openSeq`, `journal-page.ts:281-289`).
- Two parameterized tests for keep-mine / use-theirs reloads arriving after another day was opened: assert the late reload neither overwrites the newer day nor triggers a PUT (`loadSeq` guards, `journal-page.ts:247-269`).

The tests use the real routes and HTTP testing backend, assert observable behavior (requests, headers, editor value, status text, URL) rather than internals, and close regression gaps around the previously fixed CR-1/CR-3/CR-4 paths. No existing test was removed or weakened (the commit only adds lines).

## Checked without findings

- ETag/If-Match (SPEC.md §4.2): `If-Match: "<version>"` for existing entries, none for creation; saves serialized through `inFlight`; edits during a save rescheduled; 409/412/428 handled as conflict.
- Out-of-order results: `loadSeq` guards entry, on-this-day and conflict reloads; `persist` ignores results for a day no longer shown; `openSeq` lets the newest route win while `leave()` waits.
- Loading and error states: load errors show "Try again"; save errors show retry and keep the user on the day; on-this-day failure is shown inline without blocking the entry.
- Route matcher `journalDay` keeps one page instance for `/journal` and `/journal/:date`; invalid and future dates redirect to `/journal`.
- Security: no logging of entry contents, no `innerHTML`; preview via the existing read-only `NoteMarkdownView`; all calls through the generated client with `SILENT_ERRORS`.
- i18n: user texts use `$localize`/`i18n` with ids.
- Shell specs (`app.spec.ts`, `app.i18n.spec.ts`) no longer expect a placeholder journal.
- Playwright spec matches the UI and API contract (201 on create, `If-Match` on delete, fictitious data, server-side "today").

## Evidence note

The task file shows criterion 1 verified with the Playwright run against the compose e2e stack. Criterion 2 (`Vitest ... ; cd web && npm run check passes`) is still unverified at review time; the reviewer confirmed the journal Vitest specs (93 passed), eslint and prettier on the journal files, but did not run the full `npm run check`. The parent must run it on the final code and record it with `tasks.py verify`.

## Review conclusion

No material issues found.
