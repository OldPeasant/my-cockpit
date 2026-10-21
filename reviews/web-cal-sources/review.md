# Code Review

Date: 2026-10-21

Task: web-cal-sources - Settings: calendars and integrations

Scope: `git diff 3060f4b1b3c57308549774d15244fd89f57228cd..9b26e84addda3d5b15477d5de308fbd102c0f6a5`. I reviewed the whole task on the final code and left out `tasks/` and `reviews/`. Task commits: fdd28477, dfda0e45, b393254c, ec1a0ea4, 85a719ea, bfc71787, 08ab8780, a550bf69, fb41e72c, 9b26e84a. 448c168c merges the wip branch. Committed changes only, and the working tree was clean.

Requirements:
- tasks/web-cal-sources.md: acceptance criteria 1-3 and the four recorded assumptions (server POST /api/v1/calendar/sources, the OAuth callback on /settings/integrations, reconnect through sessionStorage, the shared CalendarSourcesService)
- SPEC.md §4.2 (Problem Details, ETag/If-Match), §6.1, §6.4, §11
- docs/ARCHITECTURE.md conventions, AGENTS.md project notes
- Parent instructions: re-review the final code and check the earlier findings

Git range:
- Branch: main
- HEAD: 9b26e84addda3d5b15477d5de308fbd102c0f6a5
- Base/start: 3060f4b1b3c57308549774d15244fd89f57228cd
- Commits reviewed: 10 task commits, plus the 448c168c merge and tasks-only claim, heartbeat and release commits

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification of earlier findings

- **First review, CR-1 (reconnect adds a calendar source to mail-only accounts): fixed.**
  - `CalendarSourcesService.reconnect()` saves the connection id in sessionStorage.
  - `takeReconnect()` in `IntegrationsSettings.finishOAuth()` skips `addSource` for that connection.
  - `connect()` clears any stale entry.
  - Tests cover this.
- **First review, CR-2 (an older reload overwrites a newer change): fixed.** `reload()` and `load()` use a sequence id. `apply()` drops older answers and keeps a calendar whose version on the page is higher (`calendars-settings.ts:320-335`).
- **First review, CR-3 (the OAuth hint text): fixed.** The hint now names Settings → Integrations.
- **First review, CR-4 (an add that succeeded is reported as failed when the reload fails): fixed.** `reloadAfterAdd()` handles this, and `notShown()` gives the status text.
- **First review, CR-5 (`NewCalendarSource.toString` leaks the password and URL): fixed.** See `CalendarSourceService.kt:30-32`. Both the request DTO and the domain class mask the URL and the password.
- **First review, CR-6 (aria for "Sync now" while syncing): fixed.** `syncLabel()` and `aria-busy` handle this.
- **Second review, CR-1 (a late PATCH answer replaces newer reload state): fixed in a550bf69.**
  - `change()` merges `saved` only when `saved.version >= c.version` (`calendars-settings.ts:286-289`).
  - The test `keeps a newer state from a reload when a change answers after it` reproduces the scenario.
- **Third review, P3 (a default change that succeeded is reported as failed when the follow-up reload fails): still open.** No commit after that review changed this path. It is carried forward below as CR-1.

## Whole-implementation re-check

- **Criterion 1: met in the code.**
  - Forms: local calendar (name, color), CalDAV (http(s) URL, user, app password, optional name), ICS (http(s) or webcal(s) URL).
  - Google and Microsoft redirect to `/api/v1/oauth/{google|microsoft}/start?purpose=CALENDAR`. If the provider is not configured, a hint is shown instead.
  - Color, visibility and the default calendar are changed with PATCH and `If-Match`. A 409 or 412 shows "changed elsewhere" and reloads.
- **Criterion 2: met in the code.** For each external source, Integrations shows:
  - the last sync, or "Never";
  - the last attempt, when it differs from the last sync;
  - the next sync and the last error;
  - "Sync now" with a busy state, a per-source in-flight guard and the answer merged by id.

  A sync request that fails is shown on its own, apart from the source's own sync error. The OAuth callback parameters match `OAuthController` (`oauth`, `provider` slug, `purpose`, `connectionId`, `error`). The URL is cleared with `replaceUrl`.
- **Server endpoint:**
  - It validates each type and refuses LOCAL and user info in URLs.
  - The CalDAV password is stored as `EncryptedSecret`, and responses contain no config or secrets.
  - Errors are Problem Details. It returns 201, or 200 for an OAuth connection that is already linked.
  - It publishes one `CALENDAR_SOURCE` CREATED change event. The new test checks that no event is published on 200 or 400.
  - Anonymous requests get 401 (new test).
  - `api/openapi.yaml` and the generated client match the controller. `npm run check` runs `api:check`.
- **Security:** the OAuth outcome, the error text and source names are interpolated, not bound as HTML. No `innerHTML` or `bypassSecurity` appears under `features/settings`. The provider path segment is limited to `google` or `microsoft`.
- **Routes:** the placeholders are replaced, and `settings.routes.spec.ts` is updated.
- **Tests run by this reviewer:** `cd web && npx ng test --watch=false --include 'src/app/features/settings/**/*.spec.ts'` -> 13 files, 292 tests passed. The test writer's report (`tests.md`) records `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.calendar.*'` -> 850 tests passed, and `npm run check` passed.
- **Acceptance evidence:** `tasks.py verify web-cal-sources` lists criteria 1-3 as not yet verified. No criterion is claimed without evidence. The parent still has to record them, including `cd web && npm run check` for criterion 3 and a `.taskcheck` run that passes (`tests.md` reports the flaky `IcsFeedClientTests`, which is outside this task).

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
    - Problem: When the PATCH with `isDefault: true` succeeds, `change()` runs `await this.reload()` inside the same `try`. If that GET fails (network or 5xx), the `catch` sets `saveError` to "<name> could not be changed: Something went wrong.", although the server already made the calendar the default. `saved` is never merged in this path, so the page keeps the old flags and the old `version`. The retry reload in the `catch` (line 293) will probably fail as well, and the next change of that calendar then gets a 412. This is the same pattern CR-4 of the first review fixed for adds (`reloadAfterAdd`). No test covers a successful default PATCH followed by a failed GET.
    - Why it matters: The user is told that a committed change failed. The case is narrow, and the user can recover through the 412 message or a page reload, so the priority is low.
    - Suggested fix: Merge `saved` first, with the same version guard as the non-default path. Then reload, and if the reload fails, show a status such as "saved; reload the page to see all defaults" instead of a save error. Add a spec where the default PATCH succeeds and the following GET fails.

## Review conclusion

No material issues found. All earlier P1 to P3 findings except one are fixed and tested on the final code. The remaining CR-1 is a low-priority hardening carried over from the previous review. The parent can fix it or accept it in `quality.json`.
