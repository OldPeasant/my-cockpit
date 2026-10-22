# Fix Report

Date: 2026-10-22

Task: web-dashboard - Dashboard

Scope: findings CR-1..CR-6 in `reviews/web-dashboard/review.md` and the dead-code note (`DashboardPage.eventStart`) in `reviews/web-dashboard/tests.md`, on top of HEAD eeed1dda1. Only files in `web/src/app/features/dashboard/` were changed.

## Fixed findings

### CR-1 — Quick customize clicks during an unanswered settings PATCH lose earlier changes

Files changed:
- `web/src/app/features/dashboard/dashboard-layout.ts`
- `web/src/app/features/dashboard/dashboard-layout.spec.ts`

Problem:
`change()` built the new layout from `slots()` when the user clicked. A save queued behind an unanswered PATCH did not show yet, so the next click started from the same old layout and overwrote the earlier change.

Fix:
`DashboardLayout` now keeps its own save queue and a `pending` signal with the changes that have not been handed to `SettingsService` yet. `slots()` is the stored layout plus the pending changes, so every click shows at once and the next click builds on it. Each change is computed inside its queued step, from the stored layout as the previous save left it (accepted, or rolled back by `SettingsService`). A refused change therefore drops out and the later changes still apply. `reset()` goes through the same queue, so a change right after a reset builds on the default layout. The settings are still loaded (eagerly) before the first change is computed.

Validation:
- New specs: "builds quick changes on each other while a save is unanswered" (move Today twice, then hide two widgets before any PATCH answers; checks the display at once, every PATCH body, the If-Match chain and the final layout), "drops a refused change but keeps the quick changes made after it", "builds a change made right after a reset on the default layout".
- `npx ng test --watch=false --include 'src/app/features/dashboard/**/*.spec.ts'` — Pass

### CR-2 — "N+ unread mail" can show "0+"

Files changed:
- `web/src/app/features/dashboard/dashboard-data.ts`
- `web/src/app/features/dashboard/dashboard-data.spec.ts`

Problem:
`unreadMail.more` was `!!nextCursor`, so a large mailbox showed "0+" or "3+" even with exactly 0 or 3 unread messages.

Fix:
`more` is now `unread > 0 && !!nextCursor`, so the "+" shows only for a count above 0. The tasks count is unchanged.

Validation:
- Updated spec "counts no unread mail when the mail page has no items" (now `more: false`). New spec "flags the unread mail count as cut only when the cut page has unread mail".
- Focused dashboard tests — Pass

### CR-3 — Weekly review: "overdue" styling and the Start button use different thresholds

Files changed:
- `web/src/app/features/dashboard/dashboard-page.html`
- `web/src/app/features/dashboard/dashboard-page.spec.ts`

Problem:
The red figure used `days > reviewDueDays`, the "Start a weekly review" button used `days >= reviewDueDays`.

Fix:
Both use `days >= reviewDueDays` (7).

Validation:
- New spec "marks the weekly review as due after %i days" for 6 (neither) and 7 (both).
- Focused dashboard tests — Pass

### CR-4 — The `WEEKLY_REVIEW` change-event subscription never fires

Files changed:
- `web/src/app/features/dashboard/dashboard-data.ts`
- `web/src/app/features/dashboard/dashboard-data.spec.ts`

Problem:
The server publishes no `WEEKLY_REVIEW` change event.

Fix:
The `weeklyReview` source list is now empty. The `SOURCES` doc comment says that the widget loads again only when the page opens, when it is shown again, and after an SSE resync. In the spec, the table row for `WEEKLY_REVIEW` became a `pinnedNotes`/`NOTE` row. A new spec checks that the weekly review does not reload on change events. The resync spec still covers it.

Validation:
- Focused dashboard tests — Pass

### CR-5 — A hidden widget shown again keeps stale data; `show()` sends a second request while loading

Files changed:
- `web/src/app/features/dashboard/dashboard-data.ts`
- `web/src/app/features/dashboard/dashboard-data.spec.ts`
- `web/src/app/features/dashboard/dashboard-page.spec.ts`

Problem:
`show()` reloaded only widgets that were not `ready`. A ready widget shown again kept stale data, and a widget still loading got a second request.

Fix:
`show()` compares against the previously shown set. A `ready` widget that was hidden and is shown again reloads quietly, so its old data stays on screen. A failed widget, or one never requested, loads visibly. A widget whose first load is still running (status `loading` with a request number recorded) is left alone. Widgets that stay shown are not reloaded, for example after a move.

Validation:
- New data specs: "reloads a loaded widget quietly when it is shown again after being hidden" and "does not request a widget again while its first load is running". New page spec: "keeps a widget's data on screen while it reloads after being shown again". The existing page spec "rolls back a refused layout change" now flushes the quiet reload of the Inbox widget, which is shown again after the rollback.
- Focused dashboard tests — Pass

### CR-6 — Misleading page test name and comment

Files changed:
- `web/src/app/features/dashboard/dashboard-page.spec.ts`

Problem:
"moves a widget down and up from the customize panel" only moved a widget down. Its comment ("A widget shown again after the move loads its data") contradicted its assertion that no request is pending.

Fix:
Renamed the test to "moves a widget down from the customize panel; the first one cannot move up". The comment now reads "A move shows no hidden widget, so no widget loads again." The up move is covered by the test-writer's existing "moves a widget up from the customize panel". The settings-load failure test the review asked for already exists ("shows the default layout when the settings fail, without letting it be changed"), so nothing was added for it.

Validation:
- Focused dashboard tests — Pass

### TW-note — Unused `DashboardPage.eventStart()`

Files changed:
- `web/src/app/features/dashboard/dashboard-page.ts`

Problem:
Nothing called the method (dead code, and the only uncovered function).

Fix:
Removed the method and its now unused `OccurrenceResponse` import.

Validation:
- `npx ng lint --max-warnings=0` — Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx prettier --write src/app/features/dashboard` — done
- `cd web && npx ng lint --max-warnings=0` — Pass (All files pass linting)
- `cd web && npx ng test --watch=false --include 'src/app/features/dashboard/**/*.spec.ts'` — Pass, 3 files, 77 tests (was 67)

Full test suite:
- `cd web && npm run check` (api:check, lint, e2e:typecheck, test:ci, ng build) — Pass, exit 0. 164 files, 3343 tests. The build shows the known initial-bundle budget warning (676.62 kB of 500 kB). It is a warning only, and this change did not cause it.

Lint/type-check/build:
- Included in `npm run check` above — Pass

`.taskcheck`:
- Not run. `scripts/check.sh` also runs the Gradle server and Android checks, which this helper cannot run. The parent runs it. No server or Android files changed.

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
