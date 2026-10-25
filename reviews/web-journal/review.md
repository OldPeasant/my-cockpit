# Code Review

Date: 2026-10-25

Task: web-journal - Journal UI

Scope: re-review of the whole task on the final code, `git diff 9e5279e40..HEAD -- web` (production code, Vitest specs, Playwright spec, shell spec edits). The focus is the only code change since the previous review, commit 692159200 (HEAD). It adds 2 tests to `web/src/app/features/journal/journal-page.spec.ts`. `git show --stat` for HEAD, 27c8e06f1 and 25b35c33f confirms that only `journal-page.spec.ts` and `reviews/` changed after the last fix commit 7c24c1e01. Production code is unchanged since then.

Requirements:
- tasks/web-journal.md acceptance criteria and recorded assumptions (Notes, including the earlier P3 findings accepted as follow-ups)
- tasks/web-journal-rollover.md (follow-up task for the accepted findings)
- SPEC.md §8.5 (Journal), §4.2 (ETag/If-Match)
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project conventions
- Previous review of this task (CR-1/CR-2 below are carried over unchanged)

Git range:
- Branch: main
- HEAD: 692159200e6123b544de9b6393fe0a0a721badfc
- Base/start: 9e5279e40277f1b7be5819f8f39d10b9763317ed
- Commits reviewed: 10 (505fa2903, 1c906c875, 32fb28e2b, 0b8d2e883, 79059018c, 7c24c1e01, a02f276a5, 25b35c33f, 27c8e06f1, 692159200)
- Uncommitted changes: none at review time (`git status --short` empty)

Commands run during this review (in `web/`):
- `npx ng test --watch=false --include 'src/app/features/journal/**/*.spec.ts'` -> 5 files, 95 tests passed
- `npx eslint src/app/features/journal` -> no findings
- `npx prettier --check src/app/features/journal e2e/journal` -> all files formatted
- The reviewer did not re-run Playwright. Criterion 1 evidence in the task file records `npm run e2e -- journal/journal.spec.ts` against the compose e2e stack -> 31 passed.

Summary: 0 P0, 0 P1, 0 P2, 3 P3

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
    - Problem: `today` comes from the heatmap, and `load()` fetches the heatmap only while `today()` is null. The page instance is kept for every day, so `today` is loaded once for the lifetime of the page.
    - Why it matters: In a tab left open overnight, the previous day is still labelled "Today". The task Notes record this finding, and `tasks/web-journal-rollover.md` criterion 1 covers it.
    - Suggested fix: Follow the plan in web-journal-rollover: refresh the heatmap and `today` on `visibilitychange` or a timer.

- [ ] **CR-2 | Edits in conflict are dropped without a prompt when the user leaves the day (carried over; disposition: deferred to web-journal-rollover)**
    - File: `web/src/app/features/journal/journal-page.ts:225`, `web/src/app/features/journal/journal-page.ts:290`
    - Category: Correctness
    - Problem: `flush()` returns without saving while `saveState === 'conflict'`. `open()` keeps the user on the day only for `'error' && dirty()`, so opening another day in conflict state drops the edits.
    - Why it matters: The banner says the edits are not saved yet, but the edits are dropped when the user opens another day. `tasks/web-journal-rollover.md` criterion 2 covers this.
    - Suggested fix: Follow the plan in web-journal-rollover: prompt the user to keep mine, discard or stay.

- [ ] **CR-3 | A late heatmap response from a superseded load overwrites the newer heatmap**
    - File: `web/src/app/features/journal/journal-page.ts:143`, `web/src/app/features/journal/journal-page.ts:331-335`
    - Category: Correctness
    - Problem: When the route changes before the first heatmap arrived, both loads request the heatmap. The new test at `journal-page.spec.ts:932` asserts that two heatmap requests are sent. `loadHeatmap()` sets `heatmap` and `today` without checking `loadSeq`, so the older response always writes them, even when it arrives last. The `seq` check at line 144 stops the old load only after the stale data was written. Here is a sequence that loses data: the newer heatmap arrives, the day loads, and the user's first edit is saved. `markDay` then adds the day to the heatmap, and the slow first heatmap response arrives and replaces it with the server state from before the save. The day is unmarked until the page is reloaded.
    - Why it matters: The window is narrow: it needs a slow first request, a route change and a save within that time, and the data stays correct on the server. For that reason this is P3. Still, it is an out-of-order write of shared state that the page guards everywhere else (entry, on-this-day, conflict reloads).
    - Suggested fix: Pass `seq` to `loadHeatmap()` and set `heatmap`/`today` only when `seq === this.loadSeq`. Another option is to share one in-flight heatmap promise between loads. In the test at line 932, assert that a day saved before the late heatmap arrives stays marked. This fits naturally into web-journal-rollover, which reworks heatmap refresh anyway.

## New tests in 692159200, checked

- "ignores the failures of a day that was left before they arrived" (`journal-page.spec.ts:905`): opens 24, then 23, then 22 without waiting. It answers 22 first and then makes 23's entry and on-this-day requests fail with 503. It asserts that no alert is shown, the editor keeps 22's text, the state is "Saved" and 22's on-this-day list is unchanged. This exercises the `seq === this.loadSeq` guards in the `catch` branches of `load()` (line 157) and `loadOnThisDay()` (line 343), the failure path of the out-of-order checks. Earlier tests covered only late successes.
- "opens only the day chosen last when the route changes before the first heatmap arrived" (`journal-page.spec.ts:932`): `/journal` and then `/journal/2026-10-20` before any heatmap arrives. The newer heatmap answers first and the older one last. The test asserts that today's entry and on-this-day are never requested, and that the URL, title, editor text and heatmap selection show the 20th. This exercises the `seq !== this.loadSeq` return after `loadHeatmap()` (line 144). I traced it against the code: `open(null)` with `today` null gives target `''`. Line 287 does not return early because `target` is falsy, so load 1 starts. Then `open('2026-10-20')` starts load 2. When load 1 finishes, it returns at line 144. The assertions hold for the reason the test name gives. The test does not cover the stale heatmap write in CR-3.

Both tests use the real routes and the HTTP testing backend. They assert observable behavior (requests, editor value, status, URL, DOM) rather than internals, and they add only lines. No existing test was removed or weakened.

## Checked without findings

- ETag/If-Match (SPEC.md §4.2): existing entries are saved with `If-Match: "<version>"` and new ones without it. Saves are serialized through `inFlight`, edits made during a save are rescheduled, and 409/412/428 are handled as a conflict.
- Out-of-order results (apart from CR-3): `loadSeq` guards the entry, on-this-day and conflict reloads, on both success and failure. `persist` ignores results for a day that is no longer shown. With `openSeq`, the newest route wins while `leave()` waits.
- Loading and error states: load errors show "Try again". Save errors show a retry and keep the user on the day. An on-this-day failure is shown inline and does not block the entry.
- Route matcher `journalDay` keeps one page instance for `/journal` and `/journal/:date`. Invalid and future dates redirect to `/journal`.
- Security: no logging of entry contents and no `innerHTML`. The preview uses the existing read-only `NoteMarkdownView`. All calls go through the generated client.
- i18n: user-facing texts use `$localize`/`i18n` with ids.
- Shell specs (`app.spec.ts`, `app.i18n.spec.ts`) no longer expect a placeholder journal.
- The Playwright spec matches the UI and API contract.

## Evidence note

Criterion 1 is verified in the task file with the Playwright run against the compose e2e stack. Criterion 2 ("Vitest ... ; `cd web && npm run check` passes") is still unverified at review time. The reviewer confirmed the journal Vitest specs (95 passed), eslint and prettier on the journal files, but did not run the full `npm run check`. The parent must run it on the final code and record it with `tasks.py verify`.

## Review conclusion

No material issues found.
