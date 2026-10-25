# Code Review

Date: 2026-10-26

Task: web-journal - Journal UI (SPEC.md §8.5)

Scope: re-review of the whole task on the final code, `git diff 67d344023..164a790e8 -- web` (production code, Vitest specs, Playwright spec, shell spec edits). The focus is the only code change since the previous review, commit 164a790e8 (HEAD). It adds 1 parameterized test (2 cases, `keep-mine` and `use-theirs`) to `web/src/app/features/journal/journal-page.spec.ts`. `git show --stat 164a790e8` confirms that only that spec and `reviews/` changed. Production code is unchanged since d74da6ffe.

Requirements:
- tasks/web-journal.md acceptance criteria and recorded assumptions (Notes)
- tasks/web-journal-rollover.md (follow-up task for the deferred findings CR-1, CR-2, CR-3)
- SPEC.md §8.5 (Journal), §4.2 (ETag/If-Match)
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project conventions
- Previous review of this task (CR-1, CR-2, CR-3 below are carried over)

Git range:
- Branch: main
- HEAD: 164a790e8946273f3451f3b0d05661970ac99fe4
- Base/start: 67d344023bcf6ea4ffe0bb5c72f29aee651960f9
- Commits reviewed: 9 (14c237056, faefe15dc, 10466ff0a, c51e08ae7, 066cbb472, d74da6ffe, e2407df42, b3502a86b, 164a790e8)
- Uncommitted changes: only `tasks/web-journal.md` (lease timestamp, task state, not code; not reviewed)

Commands run during this review (in `web/`):
- `npx ng test --watch=false --include 'src/app/features/journal/**/*.spec.ts'` -> 5 files, 97 tests passed
- `npx eslint src/app/features/journal` -> no findings
- `npx prettier --check src/app/features/journal e2e/journal` -> all files formatted
- The reviewer did not run Playwright or Docker (as instructed). Criterion 1 evidence in the task file records the Playwright run against the compose e2e stack.

Summary: 0 P0, 0 P1, 0 P2, 3 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | "Today" does not move on at midnight in a page left open (carried over; disposition: deferred to web-journal-rollover)**
    - File: `web/src/app/features/journal/journal-page.ts:143`, `web/src/app/features/journal/journal-page.ts:331-335`
    - Category: Correctness
    - Problem: `today` comes from the heatmap, and `load()` fetches the heatmap only while `today()` is null. The page instance is kept for every day, so `today` is loaded once for the lifetime of the page.
    - Why it matters: In a tab left open overnight, the previous day is still labelled "Today". `tasks/web-journal-rollover.md` covers it.
    - Suggested fix: As planned in web-journal-rollover: refresh the heatmap and `today` on `visibilitychange` or a timer.

- [ ] **CR-2 | Edits in conflict are dropped without a prompt when the user leaves the day (carried over; disposition: deferred to web-journal-rollover)**
    - File: `web/src/app/features/journal/journal-page.ts:225`, `web/src/app/features/journal/journal-page.ts:290`
    - Category: Correctness
    - Problem: `flush()` returns without saving while `saveState === 'conflict'`. `open()` keeps the user on the day only for `'error' && dirty()`, so opening another day in conflict state drops the edits. The new test in 164a790e8 (and its sibling at `journal-page.spec.ts:1260`) relies on this current behavior: it navigates away from a day in conflict and asserts that no PUT is sent.
    - Why it matters: The banner says the edits are not saved yet, but they are dropped on navigation. When web-journal-rollover adds the prompt, these tests must be adapted.
    - Suggested fix: As planned in web-journal-rollover: prompt the user to keep mine, discard or stay.

- [ ] **CR-3 | A late heatmap response from a superseded load overwrites the newer heatmap (carried over; disposition: deferred to web-journal-rollover)**
    - File: `web/src/app/features/journal/journal-page.ts:143`, `web/src/app/features/journal/journal-page.ts:331-335`
    - Category: Correctness
    - Problem: When the route changes before the first heatmap arrived, both loads request the heatmap. `loadHeatmap()` sets `heatmap` and `today` without checking `loadSeq`, so the older response writes them even when it arrives last, possibly after a save marked the day, which then appears unmarked until reload.
    - Why it matters: Narrow window, server data stays correct, hence P3. `tasks/web-journal-rollover.md` criterion "A heatmap response that arrives after a newer load no longer overwrites the newer heatmap/today" covers it.
    - Suggested fix: Pass `seq` to `loadHeatmap()` and apply the result only when `seq === this.loadSeq`, or share one in-flight heatmap promise.

## New test in 164a790e8, checked

- `does not show a "${action}" reload failure that arrives after another day was opened` (`journal-page.spec.ts:1292`, runs for `keep-mine` and `use-theirs`): opens 24 with version 2, edits, the autosave PUT gets 412 (conflict), the user clicks the conflict action, which starts the reload GET of 24. Before it answers, the route changes to 22; 22 loads; then the reload of 24 fails with 503. It asserts the URL and editor show 22, the state is "Saved", no `.banner` is shown and no PUT is sent.
- Traced against the code: navigation calls `open('2026-10-22')`; `leave()` -> `flush()` returns at line 225 (conflict); the `'error'` check at line 290 does not apply; `load()` increments `loadSeq`. The late failure then reaches the `catch` of `keepMine()` (line 268) or `useTheirs()` (line 252) with a stale `seq`, so `saveError` is not set. Without these guards, `saveError` would render the `.banner` alert (`journal-page.html:147-148`) on day 22 and the test would fail, so the test exercises the guard it names. It complements the success-path sibling test at line 1260, closing the failure path of the conflict reloads (the same pattern 692159200/b3502a86b added for the day loads).
- The test uses the real routes and the HTTP testing backend, asserts observable behavior (URL, editor value, status text, DOM, requests), switches fake timers back to real ones before navigation like its sibling, and only adds lines. No existing test was removed or weakened.

## Checked without findings

- ETag/If-Match (SPEC.md §4.2): existing entries are saved with `If-Match: "<version>"`, new ones without it. Saves are serialized through `inFlight`; 409/412/428 are handled as a conflict.
- Out-of-order results (apart from CR-3): `loadSeq` guards the entry, on-this-day and conflict reloads on success and failure; `openSeq` lets the newest route win while `leave()` waits.
- Loading and error states: load errors show "Try again"; save errors show a retry and keep the user on the day; an on-this-day failure is shown inline.
- Route matcher keeps one page instance for `/journal` and `/journal/:date`; invalid and future dates redirect to `/journal`.
- Security: no logging of entry contents, no `innerHTML`; the preview uses the existing `NoteMarkdownView`; all calls go through the generated client.
- i18n: user-facing texts use `$localize`/`i18n` with ids.
- Shell specs no longer expect a placeholder journal; the Playwright spec matches the UI and API contract.

## Evidence note

`python tasks.py verify web-journal` shows criterion 1 verified and criterion 2 ("Vitest ... ; `cd web && npm run check` passes") still unverified at review time. The reviewer confirmed the journal Vitest specs (97 passed), eslint and prettier on the journal files, but did not run the full `npm run check`. The parent must run it on the final code and record it with `tasks.py verify` before `done`.

## Review conclusion

No material issues found.
