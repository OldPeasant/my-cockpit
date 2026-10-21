# Code Review

Date: 2026-10-21

Task: web-cal-overlay - Calendar task overlay and cross-links

Scope: committed changes of the task, `git diff ba01de89..0b0eab4c -- . ':!tasks' ':!reviews'` (commits 46ecf1c0, 783a23bd, 0b0eab4c). Uncommitted changes: none.

Requirements:
- tasks/web-cal-overlay.md (acceptance criteria and the four ASSUMPTION notes)
- SPEC.md §6.3 (UI: optional task overlay in day/week, event detail with linked items, "Create task from event" / "Create note for event" with pre-filled title + date)
- docs/ARCHITECTURE.md, docs/QUALITY.md, AGENTS.md project notes

Git range:
- Branch: main
- HEAD: 0b0eab4cb62147b210e95c35b17a4796a53163bf
- Base/start: ba01de89aa4d16281f526dde55aec27a223d4a8e
- Commits reviewed: 3

Commands run (read-only):
- `git log --oneline ba01de89..0b0eab4c` -> 3 commits
- `git diff --stat ba01de89..0b0eab4c -- .. ':!../tasks' ':!../reviews'` -> 20 files, +1449/-20
- `git diff ba01de89..0b0eab4c -- <server, api, gtd/data, calendar routes/edit, generated client, calendar views>` and full reads of `overlay/task-overlay.ts`, `overlay/event-links.ts`, `overlay/event-links.service.ts`, `e2e/calendar-overlay/overlay.spec.ts`, `gtd/data/list-store.ts`, `gtd/data/task-store.ts`, `shared/links/linked-items.ts`, `edit/event-dialog.ts` (load), `edit/event-editor.ts`, `CalendarEventController.kt` (scope defaults)
- No tests were run by the reviewer; the parent's results (calendar Vitest 234 passed, server date-range/tickler/task web tests passed, Playwright overlay spec 2 passed against the docker compose e2e stack) were taken as reported.

Summary: 0 P0, 0 P1, 1 P2, 3 P3

Checked without findings: server `from`/`to` validation (both-or-neither, reversed, malformed, max range) and the JPA predicate (due OR tickler in range replaces the tickler-hiding rule only with a range), regenerated `openapi.yaml` and `list-tasks.ts` match the controller, paging in `TaskStore.fetch`, out-of-order list answers (ListStore request sequence), overlay error and retry path, localStorage read/write failures, overlay only in day/week, read-only overlay entries (`editable: false`) and click-through to `/tasks/{id}`, `/calendar/events/:id` redirect (id URL-encoded) and one-shot open of the linked event, scope defaults of a deep-linked override or series (server defaults: override -> THIS, master -> ALL, consistent with the assumption), double-submit guard of the create actions, link-failed vs create-failed messages, i18n of all new strings, `role="alert"` on errors and `aria-describedby` on the toggle.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | "Create task from event" does not pre-fill the event's date**
    - File: `web/src/app/features/calendar/overlay/event-links.service.ts:74`
    - Category: Correctness
    - Problem: SPEC.md §6.3 says "Create task from event" and "Create note for event" are created "(pre-filled title + date)". The note gets the date in its title (line 84), but the task is created with only `{ title: occurrence.title, list: 'INBOX' }`, no date in any form (neither `dueDate` nor in the title). The task's assumption note describes the behaviour ("Inbox task titled like the event") but does not say that it drops the date the spec asks for, so a human reviewing assumptions would not see the deviation.
    - Why it matters: a follow-up task created from several occurrences of a recurring meeting gets identical titles with no date, which is exactly what the spec's "+ date" prevents.
    - Suggested fix: pre-fill the date as well, e.g. the same `<title> (<yyyy-MM-dd>)` title as the note via `occurrenceDate`, or a `dueDate` of the occurrence date; otherwise record explicitly with `tasks.py assume` that the task deliberately has no date and why. Update the "creates an Inbox task" test in `event-links.spec.ts` accordingly.

## P3 Low

- [ ] **CR-2 | New `from`/`to` branch of `matchesTask` has no unit test**
    - File: `web/src/app/features/gtd/data/task-store.ts:167`
    - Category: Tests
    - Problem: `matchesTask` got a date-range branch (due OR tickler within the range, future ticklers included), but `task-store.spec.ts` (`describe('matchesTask')`, line 534) was not extended; the calendar specs exercise the store with fakes and do not cover optimistic updates leaving or entering the range.
    - Why it matters: this function must mirror the server rule (`TaskService.list`); a drift (e.g. a task moved into a tickler inside the range disappearing optimistically) would not be caught.
    - Suggested fix: add `matchesTask` cases for a range: due inside/outside, tickler inside (also in the future), neither date, and the bounds inclusive.

- [ ] **CR-3 | Range error message is off by one**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/gtd/api/TaskController.kt:396`
    - Category: Quality
    - Problem: the check rejects `DAYS.between(from, to) >= 366`, so `to = from + 366 days` is rejected while the message says `to` "must be at most 366 days after from". The allowed maximum is 365 days after `from` (a 366-day inclusive range, which is what the OpenAPI description says).
    - Why it matters: the Problem Details detail contradicts the actual rule for API clients.
    - Suggested fix: word it as a range length ("the range must span at most 366 days") or say "at most 365 days after from".

- [ ] **CR-4 | Turning the overlay off keeps refetching tasks on every task change**
    - File: `web/src/app/features/calendar/overlay/task-overlay.ts:58`
    - Category: Quality
    - Problem: `setEnabled(false)` only hides the entries; the `TaskStore` keeps its last query, and `ListStore` refetches it on every TASK change event and on SSE resync (`list-store.ts:87-93`). After the overlay was on once, the calendar page keeps issuing `GET /api/v1/tasks?from=..&to=..` (all pages) for data it does not show, for as long as the page is open.
    - Why it matters: needless requests while the user works with tasks in another tab or device.
    - Suggested fix: skip the refetch while disabled (e.g. let `TaskOverlay` own the change-event refresh, or reset/recreate the store query on disable and load again on enable), and cover it with a spec.

## Review conclusion

Material issues found; fix before completing the task. (CR-1 is a spec deviation that either needs the date pre-filled or an explicit assumption; CR-2 to CR-4 are minor.)
