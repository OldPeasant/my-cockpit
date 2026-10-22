# Code Review

Date: 2026-10-22

Task: web-habits - Habits UI

Scope: `git diff dcbfae15..b9162130 -- web/`. This is the final code after the fixes. It covers `web/src/app/features/habits/` (page, form, display helpers, service, routes, specs, testing helper) and the one-line `/habits` addition to BUILT in `web/src/app/app.spec.ts`. This is a second review: it checks the fixes for CR-1..CR-4 of the first review and reviews the whole final code again.

Requirements:
- tasks/web-habits.md, acceptance criteria 1-2 and the recorded assumptions (multi-count habits count up per tap and wrap to 0; the check targets `stats.today`; no delete or history grid; messages.xlf left to a dedicated extract)
- SPEC.md §8.5 (habits, one-tap check, streaks, 30-day completion) and §4.2 (If-Match / optimistic locking)
- AGENTS.md project notes (i18n, conventions)
- Previous review (CR-1..CR-4) and reviews/web-habits/fixes.md
- Existing web conventions (ICU plurals as in `gtd/lists/waiting-page.html`, `SILENT_ERRORS`, `DOCUMENT` visibility listener as in `core/notifications/reminder-notifications-toggle.ts`)

Git range:
- Branch: main
- HEAD: b9162130404aaccfce7a13f7a437ecde1a5919df
- Base/start: dcbfae15eaeafd1591491534f472fffc8ed3d9bb
- Commits reviewed: 4 (47d7cc80, 2fa4b0bc, cc3b59d4, b9162130)
- Uncommitted changes: `web/src/app/features/habits/habits-page.spec.ts` is modified in the working tree. The test-writer is adding specs to it at the same time. That change is not part of the reviewed code. It is mentioned under CR-1 only because one of its new specs demonstrates that finding.

Summary: 0 P0, 0 P1, 1 P2, 1 P3

Verification of the previous findings:
- CR-1 (unsequenced conflict reload) is fixed. `reloadQuietly()` (`habits-page.ts:221-229`) records `loadSeq` and applies its result only if no `load()` started in the meantime. It deliberately does not increment `loadSeq`, so a pending `load()` can never be orphaned in "Loading". Two committed specs cover both directions: a view switch (`habits-page.spec.ts:616`) and a newer load of the same view (`:743`).
- CR-2 (English plural rule in TS) is fixed. Streaks now render through one `#streak` template with ICU plurals `@@habits.streak.days` / `@@habits.streak.weeks` (`habits-page.html:225-237`). This matches the project convention. `streakLabel` and its old ids are gone, and the page spec at `:638` covers 0, 1 and 2.
- CR-3 (stale `stats.today` after midnight) is mitigated. The page reloads on `visibilitychange` to visible. It skips the reload while a form is open, a save is running or a row is busy, retries a failed load, and removes the listener through `DestroyRef` (`habits-page.ts:77-85`, `:207-214`). The remaining gap, a tap on a stale tab that never became hidden, is noted in fixes.md. This is acceptable for the scope. See CR-1 below for a race that this new reload introduces.
- CR-4 (missing conflict and 404 tests) is resolved. Specs cover a 412 on edit followed by a re-save with the new If-Match (`:399`), a failed reload after a 409 (`:424`), a check conflict (`:344`), a 412 on archive (`:360`) and a 404 on check and archive (`:371`).

Also verified on the final code:
- API usage matches the generated client and the server contract. PATCH sends `If-Match: "<version>"`. The PUT check sends `{count}`. The list call sends the `archived` param. All errors are silent and shown in place.
- Criterion 1: the list is grouped into due and not due, with a one-tap check (`aria-pressed`, localized label, disabled while busy), current and best streak with their unit, 30-day completion (with a dash when there were no due days), create and edit through `HabitForm` with validation, and archive/unarchive.
- Criterion 2: the specs cover the check toggle (check, uncheck, multi-count wrap, double tap, failure) and the streak display. `npm run check` was not run by this reviewer (read-only inspection). fixes.md records a pass, and the parent must keep its own evidence.
- The `app.spec.ts` change is the required one-liner for the shell placeholder test.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | A quiet reload that answers late overwrites changes made while it was pending**
    - File: `web/src/app/features/habits/habits-page.ts:221-225` (called from `refresh()` `:212` and `save()` `:149`)
    - Category: Correctness
    - Problem: `reloadQuietly()` drops its result only when a `load()` started in the meantime. Local changes do not count. A check (`replace`, `:158`), an archive or unarchive (`:166`) or a create (`:142`) can complete while the reload is still pending, and the later reload result then replaces them with `habits.set(habits)`. The visibility reload makes this an ordinary path: the user returns to the tab and taps a check right away. `refresh()` only guards against a busy row at the moment the reload starts, not afterwards. The GET for the whole list (stats for every habit) can be read before the PUT commits and still answer after the PUT response. In that case the check flips back to unchecked and the streak drops back. An archived habit reappears in the active list with its old version, so the next action on it gets a 412. A multi-count habit shows the old count, so the next tap sends the same count again and seems to do nothing. The test-writer's new, still uncommitted spec `keeps a check made while the reload on becoming visible is still pending` (`habits-page.spec.ts:766` in the working tree) reproduces exactly this. Against the committed code it fails: the reload flush resets `aria-pressed` to `false`.
    - Why it matters: This is the out-of-order async case the review checklist names: independent methods writing the same `habits` state without a shared request identity. The user sees their tap undone and may tap again, which on a multi-count habit records one count too many. If the spec is committed as written, `npm run check` (criterion 2) fails.
    - Suggested fix: Give local changes a generation as well. For example, keep a `changeSeq` that `run()` and `save()` increment when they apply a result, have `reloadQuietly()` capture it together with `loadSeq`, and drop its result if either changed. Alternatively, skip the reload result while any row is busy and for rows changed since the reload started. Keep the test-writer's spec as the regression test.

## P3 Low

- [ ] **CR-2 | A conflict on another row discards the unsaved edits in an open edit form**
    - File: `web/src/app/features/habits/habits-page.ts:243` (with `load()` `:94` and `habits-page.html:58-87`, `:99-107`)
    - Category: Correctness
    - Problem: When a check or an archive/unarchive on habit B fails with 409/412, `run()` calls `load()`. `load()` first sets `habits` to `null`, which replaces the whole list, including habit A's open edit form, with "Loading habits…". When the list comes back, A's row is rebuilt with a new `HabitForm` whose `ngOnInit` copies the server values again. The text the user typed into A's form is lost. The archive and check buttons of other rows stay enabled while an edit form is open, so the user can trigger this. The rest of the page avoids it: the edit-conflict path and the visibility path use `reloadQuietly()` precisely so that "an open form keeps its values" (comment at `:217-219`), and `refresh()` skips the reload entirely while a form is open.
    - Why it matters: Unsaved user input is silently thrown away after a concurrent change from another device. It is rare, but it is inconsistent with the protection the page gives the form elsewhere.
    - Suggested fix: Use `reloadQuietly()` instead of `load()` for the conflict reload in `run()` (the row error stays visible either way), or use it at least while `editing()` is set. Add a spec: an open edit form on A, then a 412 on archiving B; after the reload, A's form still holds the typed name.

## Review conclusion

Material issues found; fix before completing the task. Fix CR-1: it is a real out-of-order update on the normal tab-return path, and the test-writer's pending spec fails against the current code. CR-2 is low priority and can be fixed or explicitly dispositioned.
