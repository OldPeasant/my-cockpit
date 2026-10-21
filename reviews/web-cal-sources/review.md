# Code Review

Date: 2026-10-21

Task: web-cal-sources - Settings: calendars and integrations

Scope: `git diff ea2ffcbb..HEAD`, excluding `tasks/`, `reviews/` and `web/src/locale/messages.xlf`. Committed changes only. This is the final re-review after fix round 2, which addressed P3 CR-1 of the previous review in 5a80ff52.

Requirements:
- tasks/web-cal-sources.md acceptance criteria and recorded assumptions
- SPEC.md §4.2 (Problem Details, ETag/If-Match), §6.1, §6.4, §11
- docs/ARCHITECTURE.md conventions, AGENTS.md project notes
- Parent instructions: verify the round-2 fix and review the whole implementation

Git range:
- Branch: main
- HEAD: 5a80ff522cc8568e313156149cc7fedb42e491db
- Base/start: ea2ffcbb1662918e762126c2e3f8044816c462ae
- Commits reviewed: 8 (108d5b68, 10eb551a, a15cc9d7, b6eb88f4, 6137543a, 67406e44, 3e7144b2, 5a80ff52)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification of the previous finding

- **CR-1 (round 2), a late PATCH answer replaces a newer reload state: fixed.** `change()` in `calendars-settings.ts:286-289` now merges `saved` only when `saved.version >= c.version`. `apply()` uses the same rule for reloads. The new spec test `keeps a newer state from a reload when a change answers after it` models the scenario from the review: the hide answer (v4) arrives after a reload (v5). The test asserts a single default and `If-Match: "5"` on the next change.

## Whole-implementation re-check

- **Criterion 1:** I found the following in place:
  - forms for a local calendar (name and color), CalDAV (URL, user name, app password, optional name), ICS (http(s) or webcal URL) and Google/Microsoft (a redirect to `/api/v1/oauth/{provider}/start?purpose=CALENDAR`, or a hint when the provider is not configured);
  - per-calendar color, visibility and "Make default" through PATCH with `If-Match`, with 409 and 412 mapped to a "changed elsewhere" message followed by a reload.
- **Criterion 2:** for each external source, the Integrations page shows last sync, last attempt, next sync, the last error, and "Sync now" with a busy state and its aria-label. A failed sync request is shown separately from a failed sync. The OAuth callback creates the GOOGLE/MICROSOFT source, and a reconnect does not, as the recorded assumptions describe.
- **Server endpoint `POST /api/v1/calendar/sources`:**
  - It validates each type and rejects URLs that contain user info.
  - It stores the CalDAV password as `EncryptedSecret`.
  - Responses contain no config or credentials.
  - The `toString` of both the request and the domain object masks the URL and password.
  - Errors are Problem Details. It returns 201, or 200 for an OAuth connection that is already linked.
  - The generated client and `api/openapi.yaml` are updated.
- **Security:** the OAuth outcome and the error text are interpolated, not bound as HTML. There is no unsafe URL construction: the provider slug is limited to `google` or `microsoft`.
- **Routes:** the two placeholders are replaced, and the routes spec is updated.
- **Tests:** `cd web && npx ng test --watch=false --include 'src/app/features/settings/**/*.spec.ts'` -> 13 files, 289 tests passed. This run may include uncommitted test-writer files in the working tree.
- **Acceptance evidence:** `tasks.py verify web-cal-sources` lists all three criteria as not yet verified. No criterion is claimed without evidence. The parent still has to record them, including `cd web && npm run check` for criterion 3.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A default change that succeeded is reported as failed when the reload after it fails**
    - File: `web/src/app/features/settings/calendars/calendars-settings.ts:283`
    - Category: Correctness
    - Problem: When the PATCH with `isDefault: true` succeeds, `change()` calls `await this.reload()` inside the same `try`. If that GET fails (a network blip, a 5xx), the `catch` shows "<name> could not be changed: Something went wrong." even though the server already made the calendar the default. The `saved` result is also not merged into the list, so the page keeps the old default flags and the old `version`. The next change of that calendar then gets a 412. This is the same pattern that CR-4 of the first review fixed for adds (`reloadAfterAdd`).
    - Why it matters: The user is told that a committed change failed. The case is narrow, and the user can recover through the 412 path or by reloading the page, so the priority is low.
    - Suggested fix: Merge `saved` first, as in the non-default path, then reload. If the reload fails, show a status such as "saved; reload the page", not a save error. Add a test where the PATCH succeeds and the follow-up GET fails.

## Review conclusion

No material issues found. The round-2 fix is correct and tested. The remaining CR-1 is a low-priority hardening that the parent can fix or accept when recording dispositions.
