# Code Review

Date: 2026-10-21

Task: web-cal-sources - Settings: calendars and integrations

Scope: `git diff 59631418..HEAD`, excluding `tasks/`, `reviews/` and `web/src/locale/messages.xlf`. Committed changes only. This is a re-review after the fixer addressed CR-1..CR-6 of the previous review. The fixer's commit 65d5a541 is now 91ed5d81 after a rebase.

Requirements:
- tasks/web-cal-sources.md acceptance criteria and recorded assumptions
- SPEC.md §4.2 (Problem Details, ETag/If-Match), §6.1, §6.4, §11
- docs/ARCHITECTURE.md conventions, AGENTS.md project notes
- Parent instructions: verify the CR-1..CR-6 fixes and review the whole implementation again

Git range:
- Branch: main
- HEAD: 91ed5d818fe032a9613ae5703588fcb2824fbfd7
- Base/start: 59631418af373e0935ae9078ecc0e48a6431fa8b
- Commits reviewed: 6 (64b11ac0, f28007a2, 507a39c7, 88633600, bbaf1a6d, 91ed5d81)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Verification of previous findings

- **CR-1 (Reconnect adds a calendar source): fixed.** `CalendarSourcesService.reconnect()` saves the connection id in sessionStorage. `takeReconnect()` reads it once and clears it. `connect()` clears a stale id. `finishOAuth` skips the source POST when the callback's `connectionId` matches. This works because the server keeps the same connection id on reconnect: `OAuthConnectionService.complete` looks the connection up by provider and subject. Tests cover a matching reconnect, a reconnect for another account, and the service's one-time read. The sessionStorage fallback is recorded as an assumption.
- **CR-2 (an older reload overwrites a newer change): fixed for reloads.** `load()` and `reload()` share a sequence. `apply()` drops an answer older than the one already applied and keeps any calendar the page shows at a higher `version`. Two tests cover this with interleaved responses: a stale reload after a PATCH, and an older reload arriving after a newer one. The PATCH merge path still has no version guard; see CR-1 below.
- **CR-3 (hint text): fixed.** Both hints now say "returns you to Settings → Integrations", which matches `OAuthController.INTEGRATIONS_PAGE`.
- **CR-4 (a successful add reported as failed): fixed.** `reloadAfterAdd()` turns a failed reload into the "added; reload the page" status, and a test covers it. A failed first sync is still reported as an error that names the added source.
- **CR-5 (`NewCalendarSource.toString`): fixed.** `toString` now masks `url` and `password`, and a server test covers it.
- **CR-6 (sync button a11y): fixed.** The aria-label switches to "Syncing <name>…" and `aria-busy` is set while the sync runs. The spec checks both.

I re-checked these without finding problems:
- **Server API:** the server endpoint validates each type (CalDAV url/username/password, ICS http(s)/webcal, OAuth connection of the same provider). It refuses URLs with user info and a LOCAL type. It returns 201, or 200 for an OAuth connection that is already linked. Responses never include config or credentials, and the password is stored as `EncryptedSecret`. Errors are Problem Details through `InvalidRequestException`.
- **Calendar changes:** calendar PATCH sends `If-Match` at the version shown and handles 409 and 412.
- **Integrations page:** it shows last sync, last attempt, next sync, the error, and "Sync now" for each external source. A failed sync request is shown separately.
- **OAuth callback:** the query parameters match `OAuthController`. The URL is cleared in `finally`, and the outcome text is rendered by interpolation, not as HTML.
- **Routes:** the routes replace the two placeholders, and the routes spec was updated.

Acceptance evidence: `tasks.py verify web-cal-sources` shows all three criteria as not yet verified, so no criterion is claimed without evidence. The parent must still record them. Criterion 3 needs `cd web && npm run check`. I could not run the focused Vitest suite because test execution was denied in this review session, so the test claims above come from reading the specs.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | A late PATCH answer can still replace a newer state from a reload**
    - File: `web/src/app/features/settings/calendars/calendars-settings.ts:284`
    - Category: Correctness
    - Problem: `change()` merges a non-default PATCH result with `c.id === saved.id ? saved : c` and does not compare versions. The reload path now keeps the higher version (`apply()`, line 327), but this path does not. Example: B is the default, and the user toggles B's visibility and makes A the default while both requests are in flight. B's PATCH commits first (version n+1, still default). A's PATCH then clears B's flag (`CalendarService.makeDefault` saves B again, version n+2), and A's reload shows B at n+2. If B's PATCH answer reaches the browser after that reload, B is replaced by its n+1 state. The page then shows two defaults, and B's next change gets a 412.
    - Why it matters: This is a narrow case with a recoverable 412. It is the remaining part of the out-of-order defect that CR-2 of the previous review fixed for reloads.
    - Suggested fix: Merge `saved` only when `saved.version >= c.version`, the same rule `apply()` uses. Add a test where the PATCH answer arrives after a reload that carries a higher version.

## Review conclusion

No material issues found. All six earlier findings (CR-1 to CR-6) are fixed correctly. The new CR-1 is a low-priority hardening that can be fixed or accepted when the parent records dispositions.
