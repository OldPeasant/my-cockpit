# Code Review

Date: 2026-10-25

Task: web-journal - Journal UI

Scope: re-review after fixes of the whole task on the final code, `git diff 9005f32273ea0ec9099dcd6b727dfbee07cef392..HEAD -- web/` (production code, committed Vitest specs and the Playwright spec). Reviewed revision: af930887952bb6f0e117763e0b7f50831e943e97.

Requirements:
- tasks/web-journal.md acceptance criteria and recorded assumptions (Notes, including CR-5/CR-6 of the previous review accepted as P3 follow-ups)
- SPEC.md §8.5 (Journal), §4.2 (ETag/If-Match)
- docs/ARCHITECTURE.md, AGENTS.md project conventions
- Previous review (findings CR-1..CR-6) and reviews/web-journal/fixes.md
- Generated API client and `JournalService` usage, shared `MarkdownEditor` (`features/notes/editor/markdown-editor.ts`) upload contract

Git range:
- Branch: main
- HEAD: af930887952bb6f0e117763e0b7f50831e943e97
- Base/start: 9005f32273ea0ec9099dcd6b727dfbee07cef392
- Commits reviewed: 6 (3da83659f, 51b538487, c42b5d0a0, 79207a749, de03e977c, af9308879)
- Uncommitted changes: none at review time

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | "Today" does not move on at midnight in a page left open (previous CR-5, accepted follow-up)**
    - File: `web/src/app/features/journal/journal-page.ts:143`, `web/src/app/features/journal/journal-page.ts:334`
    - Category: Correctness
    - Problem: `today` comes from the heatmap, which is loaded only while `today()` is null. Since the CR-3 fix the page instance is kept for every day, so the heatmap and `today` are now loaded once for the whole time the journal stays open (before, a move to or from `/journal` reloaded them). A tab left open overnight still labels the previous day "Today", "Next day" stays disabled, and `/journal` opens yesterday's entry.
    - Why it matters: Small, but it contradicts "'Today' opens today's entry" for a page that is often left open. Still present at HEAD, recorded in the task Notes as a follow-up.
    - Suggested fix: Refresh the heatmap and `today` when the page becomes visible again (`visibilitychange`) or on a long timer, and reopen the day when it was today.

- [ ] **CR-2 | Edits in conflict are dropped without a prompt when the user leaves the day (previous CR-6, accepted follow-up)**
    - File: `web/src/app/features/journal/journal-page.ts:225`, `web/src/app/features/journal/journal-page.ts:290`
    - Category: Correctness
    - Problem: `flush()` does not save while `saveState === 'conflict'`, and `open()` keeps the user on the day only for `'error' && dirty()`. In conflict state (now also reached through 428), moving to another day shows that day and drops the edits; leaving the section drops them the same way.
    - Why it matters: The banner says "Your edits are not saved yet", but one click on another day throws them away. Still present at HEAD, recorded in the task Notes as a follow-up.
    - Suggested fix: Treat `'conflict' && dirty()` like `'error'` in `open()`, so the user stays on the day until they choose "Discard my edits" or "Save my version".

## Fixes of the previous review, checked

- Previous CR-1 (428 loop): `persist` (`journal-page.ts:379`) now treats 409, 412 and 428 as a conflict. The server answers 428 only for a PUT without `If-Match` on an existing entry, which the client sends only when `entry()` is null, so 428 always means "created elsewhere". `keepMine` GETs the current entry and saves with `If-Match: "<version>"`; if the entry was deleted meanwhile (GET 404 -> null) the save creates it again. Covered by the spec "treats an entry created elsewhere (428) as a conflict and saves my version over it" (real HTTP testing backend, checks no `If-Match` first, then `"3"`).
- Previous CR-2 (no committed unit specs): `journal-dates.spec.ts`, `journal-heatmap.spec.ts`, `journal-page.spec.ts`, `journal.routes.spec.ts`, `journal.service.spec.ts` are committed. They cover first-edit creation without `If-Match`, later saves with `If-Match`, whitespace-only not creating, conflict/428/error paths, the heatmap (week start, range, selection, mood updates), and future/invalid date redirects. `npx ng test --watch=false --include 'src/app/features/journal/**/*.spec.ts'` -> 5 files, 87 tests passed (run during this review).
- Previous CR-3 (page recreated between `/journal` and `/journal/:date`): `journal.routes.ts` now has a single route with the `journalDay` matcher (`[]` -> today, `[date]` -> `date` param, deeper paths -> no match), so the default `RouteReuseStrategy` keeps the instance and every move goes through `open()` -> `leave()` -> load. Title, lazy load and the `/journal/:date` deep link used by search are unchanged. Interrupted moves are still resolved by `openSeq`; a move back to the shown day returns early and cancels a pending one; the "stays on a failed save" redirect returns to the same day without a reload. The page spec now uses the real `journal.routes` and asserts the same instance across today -> yesterday -> today with yesterday's GET waiting for today's PUT.
- Previous CR-4 (upload ending during Preview): `uploadImage` chains a settle step one microtask behind the editor's `.then(ok, fail)` handler (registered first, but its second step runs after the editor's handler). The editor dispatches its replacement synchronously and `valueChange` is emitted synchronously, so by the time `settleUpload` runs `body()` no longer contains the placeholder when the editor is shown; nothing is replaced or reported twice. When the editor is gone (Preview, or a newer editor instance whose `view` differs from the one that started the upload) the page replaces the placeholder in `body()`, schedules a save (not when destroyed, `leave()` saves then) or reports the failure. Placeholders stay unique across editor instances because the counter `nextUpload` is module-level. `leave()` now waits for every tracked upload and saves once more. Covered by specs for success and failure during Preview and "reports a failed upload once while the editor is shown" with the real `MarkdownEditor`.

## Checked without findings

- ETag/If-Match (SPEC.md §4.2): `If-Match: "<version>"` for existing entries, none for creation; saves serialized through `inFlight`; edits during a save rescheduled.
- Out-of-order results: `loadSeq` guards entry, on-this-day and conflict reloads; `persist` ignores results for a day no longer shown; `openSeq` lets the newest route win while `leave()` waits.
- Loading and error states: load errors show "Try again"; save errors show retry; conflicts show the banner; on-this-day failure is shown inline without blocking the entry.
- Heatmap date math (UTC-only), week start from settings, roving tabindex and `aria-current`; `markDay` keeps items sorted and ignores dates outside the range.
- Security: no logging of entry contents, no `innerHTML`, the preview uses the existing read-only `NoteMarkdownView`; all API calls go through the generated client with `SILENT_ERRORS`.
- i18n: user texts use `$localize`/`i18n` with ids.
- Shell specs (`app.spec.ts`, `app.i18n.spec.ts`) no longer expect a placeholder journal and still check the placeholder component through a test-only route.
- Playwright spec `web/e2e/journal/journal.spec.ts` matches the UI (status texts, roles, heatmap `data-date`/`data-mood`, preview image) and the API contract (201 on create, `If-Match` on delete).

## Evidence note

`python tasks.py verify web-journal` shows both criteria still unverified at review time. Criterion 1 is a running-behavior criterion: the fixer report states the e2e spec was only typechecked, not run. Evidence for criterion 1 needs the Playwright spec run against the stack ARCHITECTURE.md names (or a `--skip` with the reason); a passing Vitest suite is not evidence for it.

## Review conclusion

No material issues found.
