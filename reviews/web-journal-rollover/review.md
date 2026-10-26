# Code Review

Date: 2026-10-26

Task: web-journal-rollover - Journal: Today rolls over at midnight, prompt before dropping conflicting edits

Scope: re-review of the final revision. `git diff 201761c39..HEAD -- web/src/app/features/journal/` covers the whole task diff (implementation, tests and the fix commit). This review also checks the fixes for CR-1 and CR-2 from the previous review (described in `reviews/web-journal-rollover/fixes.md`). During the review the working tree had one uncommitted change, `web/src/app/features/journal/journal-page.spec.ts`, from the test-writer running in parallel. That change was included in the test run but is not reviewed here as committed code.

Requirements:
- tasks/web-journal-rollover.md acceptance criteria and its 3 recorded assumptions (Notes)
- reviews/web-journal/review.md CR-1, CR-2, CR-3 (the findings this task resolves)
- Previous review of this task (CR-1, CR-2) and reviews/web-journal-rollover/fixes.md
- SPEC.md §8.5, §4.2; docs/ARCHITECTURE.md (web stack: zoneless Angular, i18n); AGENTS.md conventions

Git range:
- Branch: main
- HEAD: b214899447c0e5b1d1ac49e88755847b142a94d6
- Base/start: 201761c39b9bc14189a34aca560d03e0e32f3ba0
- Commits reviewed: 3 (0d0177b0d feature; 20b4aaa92 tests and review reports; b21489944 fix for CR-1/CR-2)
- Uncommitted changes: the test-writer's `journal-page.spec.ts`, as described in Scope

Commands run during this review (in `web/`):
- `npx ng test --watch=false --include='src/app/features/journal/**/*.spec.ts'` -> 6 files, 134 tests passed
- `npx eslint src/app/features/journal` -> no findings
- `npx prettier --check src/app/features/journal` -> all files formatted
- `python tasks.py verify web-journal-rollover` -> all 4 criteria still unverified at review time

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

- **CR-1 (second keep-mine started by the leave prompt): fixed.** `keepMine()` (`journal-page.ts:287-296`) now tracks the running save as `keeping: { seq, done }` and returns the same promise to any caller on the same load. Only the save's own `finally` clears it, and only when it still owns the slot (`keeping.done === done`), so an older load's save cannot clear a newer one. `canLeave()` (`:317-328`) captures the running promise before it opens the prompt. On "Save my version" it awaits that promise instead of starting a new GET, and leaves only if `saveState` is `'saved'`. If the banner's save fails again (412 or a GET error), the state stays `'conflict'` and the user stays on the day. Two regression tests cover this ("waits for "Save my version" from the banner instead of saving again": one GET and one PUT with `If-Match "3"`, then the other day opens; "stays when "Save my version" from the banner ends in a conflict again"). The paths for "Discard" and "Stay" are unchanged.
- **CR-2 (`savedSince` replay untested, misleading comment): fixed.** The field comment (`:137-141`) now matches the behavior: the map is cleared when a heatmap request starts and replayed on its answer. The test "keeps a day saved while the new heatmap loads in that heatmap" (`journal-page.spec.ts:1098`) completes a PUT while the midnight heatmap request is pending. The heatmap then answers without that day, and the test asserts that the day is still marked with its mood and counted.

## Acceptance criteria, checked against the code

1. **Today rolls over at midnight:** `setInterval(DAY_CHECK_MS)` (cleared on destroy) and the `document:visibilitychange` listener call `checkDay()`. It compares `dayIn(settings.timeZone)` with `today()` and reloads the heatmap (`heatmap.to` stays the source of 'today'). On `/journal` it then calls `open(null)`, which saves the old day under its own date through `leave()`/`flush()` and loads the new day. A day in conflict moves to `/journal/<old>` with `replaceUrl`, and the guard allows this without a prompt. A day opened by its date is kept. This matches the recorded assumptions. The tests cover all of these cases.
2. **Prompt before dropping edits in conflict:** the `canDeactivate: [leaveDay]` guard on the shared matcher route passes the target date, null for today, or undefined for leaving the journal to `canLeave()`. `LeavePrompt`/`LeaveDialog` use `$localize`/`i18n` ids, and Escape or the backdrop resolves to 'stay'. "Discard my edits" in the banner suppresses the prompt through `discardSeq`. The tests cover stay, discard, keep-mine success or 412, a keep-mine that is already running, leaving the journal, and no prompt without a conflict.
3. **Stale heatmap:** `heatmapSeq` ignores answers older than the newest request. `savedSince` re-applies saves made while the request ran. The `!this.today()` guard in `load()` only fires for a superseded load. The tests cover out-of-order answers, overlapping day checks, and a save during a pending request.
4. **Vitest / `npm run check`:** the journal specs pass, and eslint and prettier are clean on the final code. The fixer reports `npm run check` passing (176 files, 3702 tests). The reviewer did not run the full `npm run check`. The parent worker still has to record this evidence.

## Checked without findings

- No secrets, no logging of entry contents, no `innerHTML`. The dialog contains only static, localized text.
- The keep-mine promise cannot leak across days: `saveMine(seq)` returns early once `loadSeq` changed, and a new load gets its own `keeping` entry.
- The minute interval is cleared in `onDestroy`. The app is zoneless (ARCHITECTURE.md), so the interval does not affect app stability.
- The changes stay inside the task's `touches` (`web/src/app/features/journal`).

## Notes for the parent worker

- No acceptance criterion has recorded evidence yet (`tasks.py verify` shows all 4 unchecked).
- The test-writer's uncommitted change to `journal-page.spec.ts` was present during this review. Committing it changes code under `web/`, so under docs/QUALITY.md "Freshness" this review becomes stale and has to be repeated on the revision that includes it. Run prettier on it before committing.

## Review conclusion

No material issues found.
