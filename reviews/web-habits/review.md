# Code Review

Date: 2026-10-22

Task: web-habits - Habits UI

Scope: `git diff 91479195047fb9c5755b6846ec9021d1b387f38e..700f355a -- web/`, the task commits 91fd3a73..700f355a. This is the final code: `web/src/app/features/habits/` (page, form, display helpers, service, routes, specs, testing helper) and the one-line `'/habits'` addition to BUILT in `web/src/app/app.spec.ts`. This third review checks the round-2 fixes in 953d7e6b (CR-1 and CR-2 of the second review) and the regression specs in 700f355a, and reviews the whole final code again.

Requirements:
- tasks/web-habits.md, acceptance criteria 1-2 and the recorded assumptions (multi-count habits count up per tap and wrap to 0; the check targets `stats.today`; no delete action or history grid; messages.xlf is left to a dedicated extract)
- SPEC.md §8.5 (habits, one-tap check, streaks, 30-day completion) and §4.2 (If-Match / optimistic locking)
- AGENTS.md project notes (i18n, conventions)
- Server contract: `server/.../journal/habits/api/HabitController.kt` (PATCH needs If-Match; the check PUT has none) and `api/openapi.yaml`
- Previous review rounds and reviews/web-habits/fixes.md, reviews/web-habits/tests.md

Git range:
- Branch: main
- HEAD: 700f355a
- Base/start: 91479195047fb9c5755b6846ec9021d1b387f38e
- Commits reviewed: 7 (91fd3a73, 32e29161, 77c76f62, 980687e2, 4731a876, 953d7e6b, 700f355a)
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Verification of the previous findings:
- CR-1 (a late quiet reload overwrote local changes) is fixed. `changeSeq` (`habits-page.ts:80`) goes up when a change in `run()` (`:249`) or `save()` (`:140`) starts and again when it finishes (`:144`, `:148`, `:158`, `:252`, `:255`). `reloadQuietly()` (`:233-242`) applies its result only when both `loadSeq` and `changeSeq` are unchanged. Every write path is covered: check, archive/unarchive, edit and create. The failure branches count up before they start their own conflict reload, so a failed change does not drop the reload it starts. Specs in 700f355a cover both orders (the reload answers before and after the change) on the check, archive, edit and create paths, and the next tap after a dropped reload sends the correct count (`count: 3`).
- CR-2 (a conflict on another row cleared an open edit form) is fixed. `run()` now calls `reloadQuietly()` on 409/412 (`:261`), so the list is not set to null. The edit form's `li` is tracked by id and `HabitForm` copies `initial` only in `ngOnInit`, so the typed values survive. The spec `keeps the typed values of an open edit form when a check on another habit gets a 412` checks that the typed name, the other row's error and the reloaded count are kept, and that the next save sends the reloaded version as `If-Match`. The changed expectation in `reloads the habits after a check conflict...` (no loading flash) matches the intended behavior and still asserts the reloaded state and the row error. It was not weakened.

Also verified on the final code:
- API usage matches the generated client and the server. PATCH sends `If-Match: "<version>"`. The check PUT sends `{count}` without If-Match, as the controller documents. The list call sends `archived`. All calls use `SILENT_ERRORS`, and errors are shown in place.
- Criterion 1: due and not due groups, the one-tap check (`aria-pressed`, a localized `aria-label`, disabled while busy), current and best streak with ICU plurals for days and weeks, 30-day completion (a dash without due days), create and edit with validation, and archive/unarchive.
- Criterion 2: the specs cover the check toggle (check, uncheck, multi-count wrap, double tap, failure, 404) and the streak display (0/1/2, days and weeks). This reviewer did not run `npm run check` because the review is read-only. tests.md and fixes.md record a pass. Both criteria are still unverified in `tasks.py verify web-habits`, so the parent must record its own evidence before `done`.
- No secrets. No user content is logged.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A dropped quiet reload is never retried, so a conflicted row or a stale `stats.today` remains**
    - File: `web/src/app/features/habits/habits-page.ts:238` (with `:160`, `:223`, `:261`)
    - Category: Correctness
    - Problem: `reloadQuietly()` now discards its result when any change on any row starts or finishes while the reload is pending. Nothing loads the list again afterwards. Two paths can be traced in the code:
        1. An edit save on A gets a 412, and its reload starts (`:160`). A check on B that is still running then finishes (`:252`). The reload is dropped, so A keeps its old version. The next save sends the stale `If-Match` and gets a second 412 before a reload finally succeeds. The same happens with a 409/412 on archive (`:261`). The comment at `:228-229` ("so saving the open form again works") is not true in this case.
        2. After the tab becomes visible again past midnight, the visibility reload starts (`:223`). If the user taps any check before the reload answers, the reload is dropped. Only the tapped row gets fresh stats from its PUT response. Every other row keeps yesterday's `stats.today`, so their next taps record checks on yesterday until the tab is hidden and shown again.
    - Why it matters: Both cases recover, through a second conflict or another visibility change. The midnight case, however, silently records checks on the wrong day, which is the case the visibility reload was added to prevent (previous CR-3). The window is narrow: the user must act while the GET is in flight.
    - Suggested fix: When the result is dropped only because of `changeSeq`, reload again once no change is running (for example, set a `reloadPending` flag and call `reloadQuietly()` from the end of `run()` and `save()` when `busy` is empty and `saving` is false). Another option is to merge the result per row and skip only the habits that changed since the reload started. Add a spec for the save-412-while-another-check-finishes case.

## Review conclusion

No material issues found. The round-2 fixes for CR-1 and CR-2 are correct and covered by regression specs. The remaining P3 is a narrow liveness gap. It can be fixed, or dispositioned in `quality.json` as a known limitation together with the existing stale-tab note.
