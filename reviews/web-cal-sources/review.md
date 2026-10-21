# Code Review

Date: 2026-10-21

Task: web-cal-sources - Settings: calendars and integrations

Scope: `git diff 7d07e18c..HEAD` (task commits c98bfaef server POST /api/v1/calendar/sources + regenerated OpenAPI/client; ed1dd89a + 52e9a519 web settings Calendars/Integrations, routes; c0323f47/3df94192 assumption notes). Committed changes only.

Requirements:
- tasks/web-cal-sources.md acceptance criteria and recorded assumptions
- SPEC.md §4.1 (secrets), §4.2 (Problem Details, ETag/If-Match), §6.1, §6.4, §11
- docs/ARCHITECTURE.md conventions, AGENTS.md project notes
- Parent instructions (credential handling, SSRF/URL validation, OAuth return handling, a11y, tests)

Git range:
- Branch: main
- HEAD: 52e9a51989e4609a10651836200dba4a725a5a0a
- Base/start: 7d07e18cf944c834f8017fe8377eef7a55acce08
- Commits reviewed: 5

Summary: 0 P0, 0 P1, 2 P2, 4 P3

Checked without findings: the request DTO masks url/password in `toString`; the response never returns config or credentials (the server test asserts this); the CalDAV password is stored as `EncryptedSecret`; URLs with user info are refused; the ICS feed fetch keeps its existing SSRF guard in `IcsFeedClient`; CalDAV requests stay on the URL's origin (existing `CalDavClient`); config keys match the adapters (`url`, `username`, `connectionId`); validation errors are Problem Details via `InvalidRequestException`; calendar PATCH sends `If-Match` and handles 409/412; the OAuth callback query contract (`oauth`, `provider`, `purpose`, `connectionId`, `error`) matches `OAuthController`, and the outcome text is rendered through interpolation, not as HTML. Acceptance evidence has not been recorded yet (`tasks.py verify` shows all three criteria open), so no criterion is claimed without evidence.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

- [ ] **CR-1 | "Reconnect" always restarts OAuth with purpose=CALENDAR and so adds a calendar source to mail-only accounts**
    - File: `web/src/app/features/settings/integrations/integrations-settings.ts:109` (with `calendar-sources.service.ts` `connect()`, and `finishOAuth` at line 135)
    - Category: Correctness
    - Problem: The Accounts list shows every OAuth connection, including ones made for MAIL or CONTACTS. `reconnect()` calls `service.connect()`, which always sends `purpose=CALENDAR`. When the browser comes back, `finishOAuth` sees `purpose=CALENDAR` and POSTs `/calendar/sources` for that connection, then syncs it. The spec `lists accounts and reconnects an expired one` asserts `?purpose=CALENDAR` for a reconnect.
    - Why it matters: Renewing access for a mail-only Google or Microsoft account quietly adds and syncs a calendar source the user never asked for. This contradicts the recorded assumption that MAIL/CONTACTS connections "only show 'account connected'".
    - Suggested fix: Let `connect()` take a purpose and, for a reconnect, pass a purpose that does not create a source. Either reuse the connection's original purpose if the API exposes it, or skip source creation when a source already exists. Otherwise, only offer Reconnect for connections a calendar source uses. Add a test that a reconnect of a non-calendar connection does not POST a source.

- [ ] **CR-2 | An older full reload can overwrite a newer calendar change**
    - File: `web/src/app/features/settings/calendars/calendars-settings.ts:271-273` (`change`) and `:290-297` (`reload`)
    - Category: Correctness
    - Problem: `change()` merges a non-default PATCH result into `calendars` in place, but a default change, and every failed change, calls `reload()`, which sets the whole list. Only the calendar being changed is disabled. Example: the user clicks "Make default" on A and toggles B. A's reload GET can read B before B's PATCH commits but arrive after B's merge. It then puts B's old state and old `version` back in the list. B's toggle visibly reverts, and the next change to B gets a 412 ("changed elsewhere").
    - Why it matters: The UI shows a state that differs from the server, and the next edit fails. Recovery needs another round trip. This is the "older request overwrites a newer one" defect class.
    - Suggested fix: Give each reload a request id and drop stale results, or keep the newer `version` per calendar when applying a reload. Another option is to disable all calendar controls while a change that reloads is in flight. Add a test with interleaved responses.

## P3 Low

- [ ] **CR-3 | The OAuth hint text says "sends you back here", but the callback lands on Integrations**
    - File: `web/src/app/features/settings/calendars/calendars-settings.html:248` and `:272`
    - Category: Quality
    - Problem: The Google and Microsoft hints on the Calendars page say the provider "sends you back here". `OAuthController.INTEGRATIONS_PAGE` redirects to `/settings/integrations`.
    - Why it matters: The user-facing text is misleading.
    - Suggested fix: Say the provider returns you to Settings → Integrations, or fix the text after deciding on the landing page.

- [ ] **CR-4 | An add that succeeded is reported as failed when the follow-up reload fails**
    - File: `web/src/app/features/settings/calendars/calendars-settings.ts:164-166` (`addLocal`), `:180-181` with `:224` (`addCaldav`/`fetchCalendars`), `:239-247` (`add`)
    - Category: Correctness
    - Problem: `reload()` runs inside the `run` callback after the create has succeeded and the form has been reset. If the GET of calendars or sources fails, `add()` catches the error and shows the generic "Something went wrong." as `addError`. The source or calendar already exists.
    - Why it matters: The user may add it again. The server only deduplicates OAuth sources, so a second CalDAV or ICS source is created.
    - Suggested fix: Treat a reload failure after a successful create as a warning, for example "added; refresh to see it", not as an add error.

- [ ] **CR-5 | `NewCalendarSource` is a data class whose generated `toString` includes the plaintext password**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSourceService.kt:21-28`
    - Category: Security
    - Problem: `CreateCalendarSourceRequest` masks `url` and `password` in `toString`, but the domain object it is copied into does not. Nothing logs it today. Any future log, exception message or debugger dump of `NewCalendarSource` would expose the CalDAV password and the ICS URL token.
    - Why it matters: Logging secrets breaks a project convention, and this is an easy way to do it by accident.
    - Suggested fix: Override `toString` the same way as the request DTO.

- [ ] **CR-6 | Screen readers do not hear "Syncing…" on the sync button**
    - File: `web/src/app/features/settings/integrations/integrations-settings.html:41`
    - Category: Quality
    - Problem: `[attr.aria-label]="syncLabel(source)"` ("Sync <name> now") replaces the button's visible text, so assistive technology never hears the "Syncing…" state. No `aria-busy` or live status is set either.
    - Why it matters: Screen-reader users get no feedback that a sync is running (a11y).
    - Suggested fix: Make the aria-label depend on the state (for example "Syncing <name>…"), or set `aria-busy` and announce the result in a `role="status"` region.

## Review conclusion

Material issues found; fix before completing the task. CR-1 and CR-2 are the material ones. CR-3 to CR-6 are low-priority improvements.
