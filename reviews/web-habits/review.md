# Code Review

Date: 2026-10-22

Task: web-habits - Habits UI

Scope: `git diff 40a04b17..098d7c4c -- web/` (commits 2226ef19 and 098d7c4c; all under `web/src/app/features/habits/` plus one line in `web/src/app/app.spec.ts`)

Requirements:
- tasks/web-habits.md acceptance criteria 1-2 and recorded assumptions (multi-count habits count up per tap and wrap to 0; no delete/history grid; messages.xlf left to a dedicated extract)
- SPEC.md §8.5 (habits: schedule, target count, reminder, archived, one-tap check, streaks, 30-day completion), §4.2 (If-Match)
- AGENTS.md project notes (conventions, i18n, generated API)
- Server contract: `server/src/main/kotlin/ch/sonensei/cockpit/journal/habits/api/HabitController.kt`, `HabitDtos.kt`, `domain/HabitService.kt`; generated client `web/src/app/api/generated/models/habit-request.ts`
- Existing web conventions (ICU plurals in other feature templates, `SILENT_ERRORS` usage)

Git range:
- Branch: main
- HEAD: 098d7c4c0867aec48db18066c38ee68b64fbe9bc
- Base/start: 40a04b17293e02c853fb96c1f775770355949039
- Commits reviewed: 2 (2226ef19, 098d7c4c; the remaining commits in the range only touch `tasks/`)

Summary: 0 P0, 0 P1, 1 P2, 3 P3

Verified as correct:
- API usage matches the server: PATCH sends `If-Match: "<version>"` (`habits.service.ts:34`), PUT check sends `{count}` with no If-Match (the server needs none), list uses `archived` query param. Create/edit send only the fields for the chosen schedule; the server clears the others. An empty reminder goes out as `null`, which clears it on PATCH (`habit-display.ts:130`, covered by spec `habits-page.spec.ts:193` and `habit-display.spec.ts:72`). The `as string | undefined` cast works around the generated type having no `null` and has a comment saying so.
- One-tap check: the button is disabled while busy, so a double tap does not send two requests. The response replaces the row. A failed check shows a per-row `role="alert"` message, and the button becomes usable again.
- Loading/error: the initial load and view switch use a sequence guard (`loadSeq`). A failed load shows a retry button. The form validates name, weekdays, times per week and target (NaN included).
- a11y: the check button has `aria-pressed` and a localized `aria-label`. Glyphs are `aria-hidden`. Lists are labelled by their group headings, and the show toggle is a labelled group.
- i18n: all user-visible strings use `i18n`/`$localize` with ids. `@@habits.schedule.daily` is reused with identical text in TS and template, which is fine.
- `app.spec.ts` change (adds `/habits` to BUILT) is the required one-liner for the shell placeholder test.
- Tests cover the check toggle (check, uncheck, multi-count, failure) and streak display (days/weeks, longest, 30-day rate, no-due-days dash), as criterion 2 asks.
- `npm run check` was not re-run by this reviewer (read-only inspection); the parent must keep its own evidence for criterion 2.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | Conflict reload after a failed edit is not sequenced and can overwrite the other view's list**
    - File: `web/src/app/features/habits/habits-page.ts:139` and `:194-201`
    - Category: Correctness
    - Problem: On a 409/412 during an edit save, `save()` fires `void this.reloadKeepingForm()`, which calls `service.list(this.archived())` and then `this.habits.set(habits)` unconditionally. It ignores `loadSeq`. `saving` goes back to false in the `finally` straight away, so the user can click "Archived" while this reload is still running. `setArchived` then starts a sequenced `load(true)`. If the older active-list request resolves last, `habits` is set to the active habits while the toggle shows "Archived". This is the out-of-order async result the review checklist warns about. A conflict reload can also race a `load()` started by `run()` on a 409/412 from archive/unarchive.
    - Why it matters: The wrong list appears under the Archived view, with check buttons and Edit available, until the next reload. Actions taken there act on habits the user believes are in the other view.
    - Suggested fix: Take a sequence number in `reloadKeepingForm` (`const seq = ++this.loadSeq`) and only apply the result when `seq === this.loadSeq` and `archived` has not changed. Alternatively, skip the result when the view changed. Add a spec: a 412 on edit, then switch to Archived, then flush the archived list before the conflict reload.

## P3 Low

- [ ] **CR-2 | Streak plural built with `count === 1` in TS instead of an ICU plural**
    - File: `web/src/app/features/habits/habit-display.ts:32-40`
    - Category: Quality
    - Problem: `streakLabel` picks between four `$localize` strings with a hard-coded English rule (`count === 1`). Other features use ICU `plural` in templates (e.g. `gtd/lists/waiting-page.html:26` `{days, plural, =1 {1 day} other {...}}`, `files/browser/files-page.html:10`).
    - Why it matters: Translators cannot express locales whose plural rules differ from English (e.g. French singular for 0, Slavic few/many forms). It is also inconsistent with the project's i18n pattern.
    - Suggested fix: Render the streak in the template with an ICU plural per unit (`{n, plural, =1 {1 day} other {{{n}} days}}`), or otherwise fit the existing ICU convention.

- [ ] **CR-3 | A page left open past midnight checks yesterday while saying "for today"**
    - File: `web/src/app/features/habits/habits-page.ts:148`
    - Category: Correctness
    - Problem: `check()` sends `habit.stats.today` from the last load. The server accepts any date that is not in the future (`HabitService.setCheck`). On a tab left open overnight, a tap therefore writes the previous day's count, while the label says "Check ... for today". The returned stats then show the real today. The recorded assumption ("the check always targets the server's stats.today") covers the choice of date source but not the stale-day case.
    - Why it matters: Checks get recorded on the wrong day without any warning, which corrupts streaks for a page that is meant to be left open.
    - Suggested fix: Reload the list when the page becomes visible or focused again, or when a check response's `stats.today` differs from the date that was sent. Then warn or re-apply the check for the new day. At minimum, record the stale-tab behavior in the assumption.

- [ ] **CR-4 | Conflict and 404 handling paths have no tests**
    - File: `web/src/app/features/habits/habits-page.ts:139`, `:211-215`
    - Category: Tests
    - Problem: No spec covers an edit save that fails with 412 (the form stays open with the error, the list reloads, and a second save uses the new version). No spec covers a check or archive that gets a 404 (the row is dropped) or a 409/412 on archive (the list reloads).
    - Why it matters: These are the regression-prone optimistic-locking paths SPEC.md §4.2 requires. CR-1 lives in them.
    - Suggested fix: Add page specs for 412 on edit (assert the error text, the reload GET, and the next PATCH with the new `If-Match`) and for 404 on a check (assert the row is removed).

## Review conclusion

Material issues found; fix before completing the task. CR-1 should be fixed. CR-2 to CR-4 are low priority and can be fixed or explicitly dispositioned.
