# Code Review

Date: 2026-11-23

Task: web-gtd-tickler-chip - Web: Tickler as list choice, 'new today' marker in Next

Scope: `git diff b2a2bad83..19c60cb42` (single task commit, committed changes only)

Requirements:
- tasks/web-gtd-tickler-chip.md acceptance criteria (background: tasks/and-gtd-tickler-chip.md)
- AGENTS.md project notes (touches, i18n, lint conventions)
- docs/ARCHITECTURE.md conventions

Git range:
- Branch: main
- HEAD: 19c60cb42
- Base/start: b2a2bad83d4fd470bbf03a92657f6a6d162f3908
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 2 P3

Checked and found correct:
- `moveFor`: an unchanged tickler (any stored list) yields no move; a new or changed tickler moves to
  `INBOX` with its date; leaving Tickler for any list (including the stored one) sends a list move
  without `ticklerDate`, which clears it, as the previous code already relied on; Scheduled carries
  its date. Switching Scheduled -> Tickler -> back yields no move and no patch (`hasChanges` false).
- `patchFor` uses the effective list, so hidden scheduled/waiting fields are not patched while Tickler
  is chosen; after a tickler move the patch is computed against the server's INBOX response.
- `validateDraft`: Tickler requires a date, a changed date must be after today, an unchanged
  (possibly past) stored date is accepted; scheduled/waiting checks are skipped while Tickler overrides.
- `chooseList` restores the task's stored tickler date when Tickler is chosen again.
- Next view: badge is text (`New today` / `Neu heute`) plus an accent edge; `newTodayFirst` is a stable
  partition applied after the area filter; server filters and presets are untouched.
- i18n: `messages.xlf` and `messages.de.xlf` have identical trans-unit id sets; new ids
  (`gtd.tickler.date`, `gtd.tickler.hint`, `gtd.next.newToday`, `taskDetail.error.ticklerRequired`)
  are translated; the obsolete `inbox.prompt.ticklerDate` and `taskDetail.ticklerHint` were removed.
  Form, List option and Inbox action all use "Tickler" / "Wiedervorlage" with the required hint.
- Accessibility: tickler input is `required`, `min` = tomorrow, `aria-invalid`, and `aria-describedby`
  pointing to the error or the hint (also in the Inbox prompt).
- Unit tests cover each criterion (task-draft, task-detail, next-view, inbox-page specs).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | "New today" uses a date captured when the view was created**
    - File: `web/src/app/features/gtd/next/next-view.ts:104` (used at `:124` and in `next-view.html:166`)
    - Category: Correctness
    - Problem: `today` is a plain field set once by `localDate()` at construction. The new badge and
      ordering depend on it, so a Next view left open past midnight keeps marking yesterday's released
      tasks as "New today" and listing them first until it is re-created. The criterion says "from the
      next day on they show and sort normally". The field itself predates the task, but this task
      makes the marker depend on it.
    - Why it matters: Small and only for long-open tabs, but the marker would be wrong in exactly the
      "morning after" case the feature is about.
    - Suggested fix: Derive the date when the list is computed (e.g. a signal refreshed on reload/focus,
      or `localDate()` read inside the `tasks` computed together with the store reload), or record it as
      a known limitation.

- [ ] **CR-2 | Formatting-only edits outside the task's `touches`**
    - File: `web/src/app/features/gtd/data/task-store.ts:215`, `web/src/app/features/gtd/ui/context-chips.ts:12`
    - Category: Quality
    - Problem: Two files outside the declared `touches` (`task-detail`, `next`, `inbox`, `locale`) got
      formatter reflows with no behavior change.
    - Why it matters: AGENTS.md asks tasks to stay inside `touches` so parallel tasks don't collide;
      unrelated churn raises merge-conflict risk for agents working in `gtd/data` or `gtd/ui`.
    - Suggested fix: Drop these hunks unless the formatter or lint check requires them; if it does, keep
      them and leave them as they are.

## Review conclusion

No material issues found.
