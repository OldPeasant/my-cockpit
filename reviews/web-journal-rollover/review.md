# Code Review

Date: 2026-10-26

Task: web-journal-rollover - Journal: Today rolls over at midnight, prompt before dropping conflicting edits

Scope: re-review of the final revision 4fc12be9b. `git diff 201761c39..4fc12be9b -- web/src/app/features/journal/` covers the whole task diff (implementation, tests, the fix commit and the test-writer's round 2 tests). This review also checks the fixes for CR-1 and CR-2 from the first review (described in `reviews/web-journal-rollover/fixes.md`). The working tree was clean at review time.

Requirements:
- tasks/web-journal-rollover.md acceptance criteria and its 3 recorded assumptions (Notes)
- reviews/web-journal/review.md CR-1, CR-2, CR-3 (the findings this task resolves)
- First review of this task (CR-1, CR-2) and reviews/web-journal-rollover/fixes.md
- SPEC.md §8.5, §4.2; docs/ARCHITECTURE.md (web stack: zoneless Angular, i18n); docs/QUALITY.md; AGENTS.md conventions

Git range:
- Branch: main
- HEAD (reviewed revision): 4fc12be9b4cbb4ed13a50d4895b83515ae3e0249
- Base/start: 201761c39b9bc14189a34aca560d03e0e32f3ba0
- Commits reviewed: 4 (0d0177b0d feature; 20b4aaa92 tests and review reports; b21489944 fix for CR-1/CR-2; 4fc12be9b test-writer round 2: 4 new specs in `journal-page.spec.ts` plus reports)
- Uncommitted changes: none

Commands run during this review (in `web/`, on 4fc12be9b):
- `npx ng test --watch=false --include='src/app/features/journal/**/*.spec.ts'` -> 6 files, 138 tests passed
- `npx eslint src/app/features/journal` -> no findings
- `npx prettier --check src/app/features/journal` -> all files formatted
- `python tasks.py verify web-journal-rollover` (before 4fc12be9b) -> all 4 criteria still unverified

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification of the previous findings

- **CR-1 (second keep-mine started by the leave prompt): fixed.** `keepMine()` (`journal-page.ts:287-296`) now tracks the running save as `keeping: { seq, done }` and returns the same promise to any caller on the same load. Only the save's own `finally` clears it, and only when it still owns the slot (`keeping.done === done`), so an older load's save cannot clear a newer one. `canLeave()` (`:317-328`) captures the running promise before it opens the prompt. On "Save my version" it awaits that promise instead of starting a new GET, and leaves only if `saveState` is `'saved'`. If the banner's save fails again (412 or a GET error), the state stays `'conflict'` and the user stays on the day. The paths for "Discard" and "Stay" are unchanged.
- **CR-2 (`savedSince` replay untested, misleading comment): fixed.** The field comment (`:137-141`) now matches the behavior: the map is cleared when a heatmap request starts and replayed on its answer. The test "keeps a day saved while the new heatmap loads in that heatmap" (`journal-page.spec.ts:1098`) completes a PUT while the midnight heatmap request is pending. The heatmap then answers without that day, and the test asserts that the day is still marked with its mood and counted.

## Tests added in 4fc12be9b (reviewed)

The 4 new specs in the 'leaving a conflict' describe (`journal-page.spec.ts:1331-1454`) only add tests. `journal-page.ts` is unchanged since b21489944. Each spec asserts observable behavior: requests sent, the If-Match header, the PUT body, the URL, the editor text and the save state. None of them asserts internal fields.
- Choosing "Stay" while the banner's save runs returns without waiting for it, and the save still completes (`Saved`, no request for the other day).
- Clicking "Keep mine" twice sends one GET and one PUT. This covers the join in `keepMine()`.
- After a keep-mine ends in a 412, a new click starts a new GET and PUT (`If-Match "5"`). This shows that `finally` releases the finished attempt.
- A late-ending keep-mine of the old day does not clear the running one of the new day: leaving with "Save my version" sends no second GET and saves `/2026-10-22` with `If-Match "3"`. This is the regression test for the `keeping.done === done` ownership check.

The last spec switches to fake timers partway through and back to real timers. It restores real timers before it continues, and the whole file passes, so the switch does not leak into other tests.

## Acceptance criteria, checked against the code

1. **Today rolls over at midnight:** `setInterval(DAY_CHECK_MS)` (cleared on destroy) and the `document:visibilitychange` listener call `checkDay()`. It compares `dayIn(settings.timeZone)` with `today()` and reloads the heatmap (`heatmap.to` stays the source of 'today'). On `/journal` it then calls `open(null)`, which saves the old day under its own date through `leave()`/`flush()` and loads the new day. A day in conflict moves to `/journal/<old>` with `replaceUrl`, and the guard allows this without a prompt. A day opened by its date is kept. This matches the recorded assumptions. Tests cover each case.
2. **Prompt before dropping edits in conflict:** the `canDeactivate: [leaveDay]` guard on the shared matcher route passes the target date, null for today, or undefined for leaving the journal to `canLeave()`. `LeavePrompt`/`LeaveDialog` use `$localize`/`i18n` ids, and Escape or the backdrop resolves to 'stay'. "Discard my edits" in the banner suppresses the prompt through `discardSeq`. Tests cover:
   - stay and discard;
   - keep-mine success, and keep-mine with 412;
   - a keep-mine already running, combined with stay, keep-mine or a repeated conflict;
   - leaving the journal;
   - no prompt when there is no conflict.
3. **Stale heatmap:** `heatmapSeq` ignores answers older than the newest request. `savedSince` re-applies saves made while the request ran. The `!this.today()` guard in `load()` only fires for a superseded load. Tests cover out-of-order answers, overlapping day checks, and a save while a heatmap request is pending.
4. **Vitest / `npm run check`:** the journal specs pass (138 tests), and eslint and prettier are clean on 4fc12be9b. The reviewer did not run the full `npm run check` on this revision. The parent worker has to run it and record the evidence.

## Checked without findings

- No secrets, no logging of entry contents, no `innerHTML`. The dialog contains only static, localized text.
- The keep-mine promise cannot leak across days: `saveMine(seq)` returns early once `loadSeq` changed, and a new load gets its own `keeping` entry.
- The minute interval is cleared in `onDestroy`. The app is zoneless (ARCHITECTURE.md), so the interval does not affect app stability.
- The changes stay inside the task's `touches` (`web/src/app/features/journal`).

## Notes for the parent worker

- `reviewed_revision` for `quality.json`: 4fc12be9b4cbb4ed13a50d4895b83515ae3e0249. Committing this report (which changes only `reviews/`) keeps the evidence fresh.
- Record evidence for all 4 criteria with `tasks.py verify`, including a real `cd web && npm run check` for criterion 4.

## Review conclusion

No material issues found.
