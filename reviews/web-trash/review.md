# Code Review

Date: 2026-10-20

Task: web-trash - Trash view

Scope: committed changes of task web-trash, `95ebd9a760be566448e3e9349a4ebcc91b097ff2..75fbe74c7be08043faa1f25913c6924b5c2de309`: commit 4eb28e9 (implementation: `web/src/app/features/trash/*`, `web/src/app/app.spec.ts`) and commit 75fbe74 (more tests in `trash-page.spec.ts` and `trash.service.spec.ts`, plus `reviews/web-trash/tests.md`). This review replaces the earlier one, which was written before 75fbe74 and is now stale.

Requirements:
- tasks/web-trash.md: acceptance criteria and the two recorded assumptions (restore snackbar, a 404 drops the row, countdown rounding and the <=3 days urgency, "Show more" paging with 50 per page; the app.spec.ts edit outside `touches`)
- SPEC.md §4.2: soft delete, a Trash view, purge after 30 days, cursor pagination, Problem Details, externalized UI strings
- docs/ARCHITECTURE.md: web conventions, i18n, lint, tests, coverage
- AGENTS.md project notes
- api/openapi.yaml: `listTrash`, `restoreTrashItem`, `purgeTrashItem`, `TrashItemResponse`, `TrashPageResponse`

Git range:
- Branch: main
- HEAD: 75fbe74c7be08043faa1f25913c6924b5c2de309
- Base/start: 95ebd9a760be566448e3e9349a4ebcc91b097ff2
- Commits reviewed: 2 (4eb28e9, 75fbe74)
- Uncommitted changes: only `reviews/web-trash/review.md` (this report)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Commands run

- `cd web && npm run lint`: "All files pass linting."
- `cd web && npx ng test --watch=false --include='src/app/features/trash/**/*.spec.ts' --include='src/app/app.spec.ts'`: 3 test files passed, 85 of 85 tests passed.
- `python tasks.py verify web-trash` (read-only listing): both criteria are still unticked. Recording the evidence is the parent worker's job. Nothing is claimed without evidence.
- I did not run the full `npm run check` (api:check, e2e typecheck, full test:ci, build). That run belongs to the parent before it ticks criterion 2.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | New trash strings are not in the extracted message catalog**
    - File: `web/src/locale/messages.xlf:1178`
    - Category: Quality
    - Problem: The task adds about 20 new `i18n`/`$localize` ids (`@@trash.hint`, `@@trash.restore`, `@@trash.purgeIn`, `@@trash.purge.title`, ...). `messages.xlf` still lists only the `trash.routes.ts` source of `@@nav.trash`, and `npm run i18n:extract` was not re-run. Earlier web feature tasks (p0-web-api-client, p0-web-auth, web-reminders) regenerated the catalog with their strings.
    - Why it matters: The strings are externalized in the source, so SPEC.md §4.2 is met. But the catalog that translators would start from (German later) does not have them, until some later task happens to re-extract.
    - Suggested fix: Run `cd web && npm run i18n:extract` and commit the regenerated `messages.xlf`. The file is outside the task's `touches`, so recording an assumption is enough. Or leave it to the next task that extracts. That is acceptable, because the file is generated.

## Verification notes

- **Criterion 1 (list, dates, countdown, restore, delete with confirmation):**
  - **Listing:** `trash-page.html` renders every `TrashItemResponse` with its type icon and label (`ItemTypeIcon`, `itemTypeLabel`), title, optional subtitle, `deletedAt` as a `mediumDate`, and an ICU plural: "purged today", "purged in 1 day" or "purged in N days".
  - **Countdown:** the value comes from the server's `purgeAt` via `daysUntilPurge`. It rounds up and never goes below 0, as the assumption says.
  - **Restore:** calls `POST /api/v1/trash/{type}/{id}/restore`.
  - **Permanent delete:** calls `DELETE /api/v1/trash/{type}/{id}`, but only after `PurgeDialog` closes with `true`. Cancel has initial focus. Escape, a backdrop click or a navigation close it with `undefined` or `false`, and both count as no.
  - **Route:** it now lazy-loads the page instead of the placeholder.
- **Criterion 2 (tests):** `trash-page.spec.ts` (33 tests) and `trash.service.spec.ts` (7 tests) cover:
  - restore: success, 409, 404, network error and unexpected errors;
  - permanent delete: confirm, cancel, Escape, failure and 404;
  - busy and disabled state per row, and out-of-order replies on different rows;
  - focus handling after a row is removed;
  - the load error and retry;
  - paging: cursor, de-duplication, the button disabled while loading, last page, error and retry;
  - the boundaries of the countdown and of the urgency class.
  The service tests check the HTTP methods, paths, `limit=50`, the cursor, and that errors are silent (no toast). The tests check visible behavior through the DOM and the HTTP testing controller. They do not mock the component's internals. The one `vi.spyOn` on `TrashService.restore` only simulates a non-HTTP failure.
- **Contract alignment:**
  - `limit` 50 is within 1..200.
  - `nextCursor` is optional and handled as such.
  - Problem Details become `ApiError.userMessage`.
  - Every call sets `SILENT_ERRORS`, so the interceptor does not toast as well as the inline alert.
  - The server's keyset cursor means that removing rows locally cannot shift later pages.
  - Rows already shown are filtered out when a page is appended.
- **Async and state:**
  - Per-item `busy` is set before the first await, which blocks double submits.
  - Stale first-page loads and stale later pages are ignored via `loadSeq`.
  - `loadingMore` is reset in `finally`.
  - A failed later page keeps "Show more" available.
  - Actions on different rows settle independently.
  - A 404 from a write that was already committed elsewhere just drops the row.
- **Security:** titles, subtitles and error messages are rendered only through interpolation (escaped). There is no `innerHTML`. No new dependencies, configuration or logging. Unexpected errors show a generic message without exception details (tested).
- **Scope:** the `app.spec.ts` edit outside `touches` only moves `/trash` from the placeholder checks to the built page, and it is recorded as an assumption. No other files outside the feature folder changed.

## Review conclusion

No material issues found. CR-1 is a non-blocking P3 cleanup.
