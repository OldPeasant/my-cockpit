# Fix Report

Date: 2026-10-21

Task: web-cal-sources - Settings: calendars and integrations

Scope: current task; findings CR-1 to CR-6 of `reviews/web-cal-sources/review.md` (base a3dc6c79, task commits d7951812, 3186cd00, 84cc7f65, 33c27141, e031a67f). The fixes are uncommitted in the working tree, for the parent worker to commit.

## Fixed findings

### CR-1 - "Reconnect" adds a calendar source to mail-only accounts

Files changed:
- `web/src/app/features/settings/calendars/calendar-sources.service.ts`
- `web/src/app/features/settings/integrations/integrations-settings.ts`
- `web/src/app/features/settings/calendars/calendar-sources.service.spec.ts`
- `web/src/app/features/settings/integrations/integrations-settings.spec.ts`

Problem:
Reconnect always started OAuth with `purpose=CALENDAR`. The callback then POSTed `/calendar/sources` for that connection, even when the connection was made for MAIL or CONTACTS only.

Fix:
The API does not say what a connection was made for. `OAuthConnectionResponse` has no purpose, every connection gets the combined scopes (`OAuthScopes.authorization`), and `OAuthConnection.kt` says the purpose only tells the client where to continue. `CalendarSourceResponse` has no connection id either, so the client cannot tell which connections back a calendar source. So the service now has `reconnect(provider, connectionId)`. It saves the connection id in `sessionStorage` (key `cockpit.oauth.reconnect`, per tab, so it survives the redirect round trip) before it leaves for the consent screen. `takeReconnect(connectionId)` reads the id and clears it. `connect()` clears a stale one. When the callback's `connectionId` matches the saved id, `finishOAuth` only shows "<Provider> account reconnected." and skips the POST. A connection that already had a calendar source keeps it, and the scheduler syncs it with the renewed tokens. A callback for another connection still creates the source as before.

Residual risk: if `sessionStorage` is unavailable (access throws), the code falls back to the old behavior and the callback creates a calendar source.

Tests: `does not turn a reconnected account into a calendar source (it may be for mail only)` (no POST, outcome text, storage cleared), `still adds a calendar source when the pending reconnect was for another account`, the extended `lists accounts and reconnects an expired one` (storage set), and the service test `remembers a reconnect for its own connection only, once, and forgets it on a new connect`.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'` - Pass (13 files, 279 tests)
- `cd web && npm run check` - Pass

### CR-2 - An older full reload can overwrite a newer calendar change

Files changed:
- `web/src/app/features/settings/calendars/calendars-settings.ts`
- `web/src/app/features/settings/calendars/calendars-settings.spec.ts`

Problem:
`reload()` (after a default change or a failed change) replaced the whole list. An answer read before another calendar's PATCH committed could restore that calendar's old state and `version`.

Fix:
Both `load()` and `reload()` now take a sequence id and apply their answer through `apply()`, which does two things:
- It drops an answer that is older than the newest one already applied.
- It keeps any calendar the page shows at a higher `version` than the answer has. Versions only grow, so a PATCH result merged meanwhile stays.

A failed change still shows the server state, because the server's version is equal or higher. A load error from a superseded load is not shown.

Tests: `keeps a change saved while a reload was on its way, even when the reload answers later` (also checks that the next PATCH sends `If-Match: "1"`), and `drops a reload that answers after a newer one`. I ran a mutation check: with the sequence check and the version merge disabled, both tests fail, and with them restored, both pass.

Validation:
- `cd web && npx ng test --watch=false --include='src/app/features/settings/calendars/calendars-settings.spec.ts'` with the guards disabled - 2 failed (expected); restored - Pass
- `cd web && npm run check` - Pass

### CR-3 - The OAuth hint says "sends you back here", but the callback lands on Integrations

Files changed:
- `web/src/app/features/settings/calendars/calendars-settings.html`

Problem:
The Google and Microsoft hints on the Calendars page were misleading.

Fix:
The text now reads "... then returns you to Settings → Integrations." The i18n ids are unchanged. `messages.xlf` was not touched, as instructed: it is left to the dedicated extract.

Validation:
- `cd web && npm run check` - Pass

### CR-4 - An add that succeeded is reported as failed when the follow-up reload fails

Files changed:
- `web/src/app/features/settings/calendars/calendars-settings.ts`
- `web/src/app/features/settings/calendars/calendars-settings.spec.ts`

Problem:
A failed GET after a successful create showed "Something went wrong." as the add error, which invites a duplicate add.

Fix:
A new `reloadAfterAdd()` catches the reload failure. The add then reports a status, not an error: "<name> added. The list could not be updated; reload the page to see it." This uses the new i18n id `calendars.add.notShown`. A failed first sync of a new source is still reported as before.

Test: `reports an add that worked as added when the list cannot be read again`.

Validation:
- `cd web && npm run check` - Pass

### CR-5 - `NewCalendarSource` prints the plaintext password in `toString`

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSourceService.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/calendar/sync/CalendarSourceCreateTests.kt`

Problem:
The data class's generated `toString` included `password` and `url`, and an ICS URL may carry a token.

Fix:
`toString` is overridden and masks `url` and `password`, as `CreateCalendarSourceRequest` does.

Test: `the domain request does not print its password or URL either`.

Validation:
- `cd server && ./gradlew ktlintFormat test --tests 'ch.sonensei.cockpit.calendar.sync.CalendarSourceCreateTests'` - Pass (17 tests, 0 failures)

### CR-6 - Screen readers do not hear "Syncing…" on the sync button

Files changed:
- `web/src/app/features/settings/integrations/integrations-settings.ts`
- `web/src/app/features/settings/integrations/integrations-settings.html`
- `web/src/app/features/settings/integrations/integrations-settings.spec.ts`

Problem:
The fixed aria-label "Sync <name> now" hid the visible "Syncing…" state.

Fix:
While the source syncs, `syncLabel()` returns "Syncing <name>…" (new i18n id `integrations.syncingAria`), and the button gets `aria-busy`.

Test: `syncs a source now and shows its new status` now checks the label and `aria-busy` during and after the sync.

Validation:
- `cd web && npm run check` - Pass

## Not fixed

None.

## Validation summary

Focused tests:
- `cd web && npx ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'` - Pass (13 files, 279 tests)
- `cd server && ./gradlew ktlintFormat test --tests 'ch.sonensei.cockpit.calendar.sync.CalendarSourceCreateTests'` - Pass (17 tests)

Full test suite:
- `cd web && npm run check` (api:check, lint, e2e typecheck, vitest, build) - Pass (123 files, 2414 tests; "All files pass linting"; the initial-bundle budget warning was already there and is unchanged)
- `cd server && ./gradlew ktlintFormat check` - Pass (exit 0; 257 test classes, none with failures or errors; ktlint clean)

Lint/type-check/build:
- Covered by the two commands above.

`.taskcheck`:
- Not run by the fixer (`sh scripts/check.sh` runs the same per-part checks; the parent runs it before `done`).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed.
