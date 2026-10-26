# Code Review

Date: 2026-10-26

Task: web-journal-rollover - Journal: Today rolls over at midnight, prompt before dropping conflicting edits

Scope: `git diff e6fe2984b..HEAD -- web/src/app/features/journal/` (committed changes of the task). Uncommitted test-writer files present during the review (`journal.routes.spec.ts` modified, `leave-dialog.spec.ts` new) were read and run, but are work in progress and not part of the committed scope.

Requirements:
- tasks/web-journal-rollover.md acceptance criteria and its 3 recorded assumptions (Notes)
- reviews/web-journal/review.md CR-1, CR-2, CR-3 (the findings this task resolves)
- SPEC.md §8.5, §4.2; docs/ARCHITECTURE.md (web stack: zoneless Angular, i18n); AGENTS.md conventions
- Existing convention for prompts: `web/src/app/features/calendar/edit/scope-dialog.ts` (`ScopePrompt`)

Git range:
- Branch: main
- HEAD: 70f57d663
- Base/start: e6fe2984bfdb907ad2fc2392898e4d735129cbfb
- Commits reviewed: 4 (e9bcada61, 73a7bdf13, a0c51e05b: assumptions in the task file only; 70f57d663: implementation and tests)
- Uncommitted changes: test-writer files listed above (not reviewed as committed code)

Commands run during this review (in `web/`):
- `npx ng test --watch=false --include='src/app/features/journal/journal-page.spec.ts' --include='src/app/features/journal/journal-dates.spec.ts'` (committed specs) -> 2 files, 89 tests passed
- `npx ng test --watch=false --include='src/app/features/journal/**/*.spec.ts'` -> first run while the test-writer was editing: 6 failures in the uncommitted `leave-dialog.spec.ts`; re-run a few minutes later: 6 files, 123 tests passed
- `npx eslint src/app/features/journal` -> no findings
- `npx prettier --check src/app/features/journal` -> only the uncommitted `leave-dialog.spec.ts` is not formatted (committed files are clean)
- `python tasks.py verify web-journal-rollover` -> all 4 criteria still unverified at review time

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | "Save my version" in the leave prompt can start a second keep-mine while the banner's one is still running**
    - File: `web/src/app/features/journal/journal-page.ts:301-308` (with `keepMine()` at 282-295)
    - Category: Correctness
    - Problem: When the user clicks "Keep mine" in the conflict banner and navigates away before its GET answers, `saveState` is still `'conflict'`, so `canLeave()` prompts (the updated test at `journal-page.spec.ts:1513`/`1546` confirms the prompt appears in this case). Choosing "Save my version" then calls `keepMine()` a second time, so two GETs run. If the second GET's answer (sent before the first PUT) arrives after that PUT has completed, `keepMine()` sets `entry` back to the older version. `flush()` then sees a difference and sends a PUT with the stale `If-Match`, which gets 412. The page shows a conflict caused by its own save, and `canLeave()` returns false.
    - Why it matters: The user's text is saved (the first PUT succeeded), but they see a false "changed elsewhere" and stay on the day. This needs a specific response order, so it is P3.
    - Suggested fix: Track a running keep-mine (e.g. keep its promise) and have `canLeave()` await that promise instead of starting another one. Or skip the prompt while a keep-mine is in progress and let the navigation wait for it.

- [ ] **CR-2 | The heatmap replay of saves made while a heatmap request runs (`savedSince`) is not covered by a test**
    - File: `web/src/app/features/journal/journal-page.ts:137-138`, `:395`, `:400`, `:458`
    - Category: Tests
    - Problem: `loadHeatmap()` clears `savedSince`, and after the answer it re-applies every entry saved in the meantime. This keeps a midnight heatmap refresh from un-marking a day saved during the request. None of the new tests completes a save while a heatmap request is pending. In the midnight test (`journal-page.spec.ts` "moves Today on...") the PUT is sent only after the heatmap was flushed. Removing the replay would leave the suite green. Also, the field comment says "while a heatmap request runs", but `markDay()` records every save, and the map is cleared only when the next request starts.
    - Why it matters: This is the same kind of out-of-order heatmap write that criterion 3 addresses. Without a test it can regress silently.
    - Suggested fix: Add a test: an autosave PUT completes while the midnight heatmap request is pending, the heatmap then answers without that day, and the day is still marked. Adjust the comment, or clear the map after the replay.

## Acceptance criteria, checked against the code

1. **Today rolls over at midnight** (criterion 1, CR-1 of web-journal): a `setInterval(DAY_CHECK_MS)` timer (cleared on destroy) and the `document:visibilitychange` listener call `checkDay()`. It compares `dayIn(settings.timeZone)` with `today()`, reloads the heatmap, and on `/journal` calls `open(null)`. Through `leave()` -> `flush()`, that saves the old day under its own date (captured in `persist`) before loading the new day. If that save fails and the edits are dirty, `open()` keeps the old day under `/journal/<old>` (`isToday()` is already false). A day in conflict is moved to `/journal/<old>` with `replaceUrl`. The guard lets this through without a prompt because `next === date()`. This matches the recorded assumptions. Concurrent checks (timer plus visibilitychange) are safe: the older heatmap is ignored, and `openSeq` lets only one `open()` proceed. Tests cover the timer, visibilitychange, a day opened by date, a conflict, and a heatmap failure with a later retry.
2. **Prompt before dropping edits in conflict** (criterion 2, CR-2 of web-journal): the `canDeactivate: [leaveDay]` guard on the shared matcher route runs for param changes (`/journal` <-> `/journal/:date`) and for leaving the journal. It passes the target date, null for today, or undefined for another page. `LeavePrompt` follows the existing `ScopePrompt` pattern. Escape and backdrop resolve to 'stay'. The texts use `$localize`/`i18n` ids. "Discard my edits" in the banner suppresses the prompt through `discardSeq`. Tests cover stay, discard, keep-mine success, keep-mine failing with 412, leaving the journal, and no prompt without a conflict.
3. **Stale heatmap** (criterion 3, CR-3 of web-journal): `heatmapSeq` drops answers older than the newest request. The existing out-of-order test now flushes a differing older heatmap (`to` = 24th, no entry on the 19th) and asserts the newer heatmap and today remain. The added `!this.today()` check in `load()` only fires for a superseded load.
4. **Vitest / npm run check**: the committed journal specs pass and eslint is clean. The full `npm run check` was not run by the reviewer.

## Checked without findings

- No secrets, no logging of entry contents, no `innerHTML`. The new dialog is static text.
- `dayIn()` falls back to the browser's time zone for a missing or invalid zone, and is tested for zones ahead of and behind UTC.
- Zoneless app (ARCHITECTURE.md), so the minute interval does not affect app stability. It is cleared in `onDestroy`.
- The changes stay inside the task's `touches` (`web/src/app/features/journal`). `messages.xlf` is regenerated by a dedicated extract, following the repository's practice (commit 80411290d).

## Notes for the parent worker

- No acceptance criterion has recorded evidence yet (`tasks.py verify` shows all 4 unchecked). Criterion 4 needs a real `cd web && npm run check` on the final code.
- The test-writer's uncommitted `leave-dialog.spec.ts` is not Prettier-formatted at review time, which would fail `npm run check`. Format it before committing.

## Review conclusion

No material issues found.
