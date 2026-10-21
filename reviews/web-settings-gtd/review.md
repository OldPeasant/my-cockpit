# Code Review

Date: 2026-10-21

Task: web-settings-gtd - Settings: contexts and areas

Scope: final review of the whole task at HEAD 8f449804, covering `web/src/app/features/settings/gtd`. Task commits: 8ff8cda2 (implementation), a08903ed (tests and reports), 407b1b96 (fixes for TW-1 and CR-1..3), 4dcbc19a and 8f449804 (test additions and reports only). This review looks at committed changes only. The production code through 407b1b96 was already reviewed in full in the previous round, which had no findings. This round confirms that the production code has not changed since then and reviews the new tests.

Requirements:
- tasks/web-settings-gtd.md acceptance criteria (1) and (2), plus its recorded assumptions
- tasks/gtd-delete-reassign.md (the follow-up task that holds the server-side fix for CR-1 from the first review)
- SPEC.md §5.1 (Areas of focus, Contexts)
- api/openapi.yaml: contexts, areas, their order endpoints, listTasks/updateTask, listProjects/updateProject
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- reviews/web-settings-gtd/fixes.md, tests.md and the previous review.md

Git range:
- Branch: main
- HEAD: 8f449804a8402ef2531a5d3b5b695ca3337fc4e3
- Base/start: 05c06f7510a27ce4f64d0b6a5fc0a45729bf823d (merge-base with main; the original task base was 3aa4bbbd3a9abc422a576d0cf520bbcdebc6fb81)
- Commits reviewed: 5 task commits (8ff8cda2, a08903ed, 407b1b96, 4dcbc19a, 8f449804). ca789d37 only adds tasks/gtd-delete-reassign.md.

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Production code unchanged since the last code review:
- `git diff 407b1b96..HEAD -- web/ ':!*.spec.ts'` is empty. Outside `reviews/` and `tasks/`, the only files that changed since 407b1b96 are `gtd-settings.spec.ts` and `gtd-settings.service.spec.ts`. The earlier findings and how they were resolved still hold:
  - CR-2 / TW-1: `restoreOrder` in `gtd-settings.ts:298-305`.
  - CR-3: `ReassignError` wraps only the move step, at `gtd-settings.service.ts:32-37` and `:183-189`.
  - CR-1: mitigated by the dialog hint. The root cause is tracked in tasks/gtd-delete-reassign.md.

Review of the new tests (4dcbc19a, 8f449804), each checked against the current production code:
- Service, "ReassignError when the projects to move cannot be listed": correct. `deleteArea` with a target lists projects inside `reassigning()`, so a 503 is wrapped and keeps the `ApiError` as its `cause`. The test also checks that no PATCH or DELETE is sent and that nothing is toasted.
- Service, "plain API error when deleting without a move fails": correct. The DELETE at `gtd-settings.service.ts:163` is outside the wrapper.
- Component, "keeps the old color and version ... when saving a color fails": correct. `setColor` leaves the list as it is on failure, so the swatch keeps `#3366ff` (HOME's color) and the next PATCH sends `If-Match: "1"`. A 400 is not stale, so no reload happens. The next `save()` clears the error.
- Component, "reloads the list when the color change finds the context changed elsewhere": correct. A 412 goes through `isStale` to `load('context')`.
- Component, area reorder plus concurrent rename, and "list reloaded during a failing reorder": correct. `restoreOrder` sorts the current list by the old snapshot. Items missing from the snapshot (`@office`) go last, and items the reload removed (`@errands`) stay gone. The versions saved meanwhile (`"2"`) are kept for the next PATCH. Together these tests pin down the TW-1/CR-2 contract more firmly.
- Component, "starts one delete only for a double click": correct and meaningful. `remove()` checks `busy` and `run()` sets it synchronously before the first await, so the second click returns early. `flushAssignedTasks` uses `expectOne`, so a duplicate task request would fail the test, and the test also checks for exactly one dialog.
- Component, "generic message ... not an API error": correct. `message()` unwraps a `ReassignError` with a non-stale cause and falls back to "Something went wrong." for a non-`ApiError`. Spying on `deleteArea` is a reasonable way to inject a non-HTTP cause. The wrapping itself is covered by the service tests, so the mock hides no behavior under test.
- No existing assertion was removed or weakened in these commits: both spec diffs only add code, apart from one import line that now also imports `ReassignError`. The test data is fictitious (`@office`, `@cell`, "Fictitious launch", "fictitious failure"), and there are no secrets or personal data.

Acceptance criteria:
- (1) Create, rename, reorder (drag and up/down buttons) and delete with clear-or-move work for contexts (with a clearable color) and for areas. Requests follow the OpenAPI contract. Unchanged since the previous review, which found no issues.
- (2) The Vitest specs cover reorder (success, failure, a concurrent rename, color change or reload, bounds, busy gate) and delete (count, cancel, clear, move, failure in the move or listing step, failure in the DELETE, conflicts, double click). They do so at the component, service and dialog level.

Verification limits: this review was read-only, and I did not run the test suite myself (an earlier attempt to run it was blocked by the sandbox). The parent worker must confirm with its own `npm run check` / settings spec run that the suite at HEAD passes, before it records criterion (2).

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.
