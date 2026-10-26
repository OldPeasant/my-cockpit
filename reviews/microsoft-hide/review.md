# Code Review

Date: 2026-10-26

Task: microsoft-hide - Hide Microsoft integration behind a setting (default off)

Scope: `git diff 2954a5299a3fcd1ce1fea73242f0f645254a3401..245ae68ecf925fab6b89a08b2f6b5ba8ec86ca71` (committed changes only; working tree clean)

Requirements:
- `tasks/microsoft-hide.md` acceptance criteria and recorded assumptions
- `docs/adr/0003-microsoft-deferred.md`
- `AGENTS.md` "Project notes", `docs/ARCHITECTURE.md`, `api/README.md`, `docs/QUALITY.md`
- Parent instructions (Gradle is blocked for sub-agents; server test results supplied by the parent)

Git range:
- Branch: main
- HEAD: 245ae68ecf925fab6b89a08b2f6b5ba8ec86ca71
- Base/start: 2954a5299a3fcd1ce1fea73242f0f645254a3401
- Commits reviewed: 4 (a8afc79e1, 44723ad45, 6abc50ec8, 245ae68ec)

Summary: 0 P0, 0 P1, 0 P2, 2 P3

## What was checked

- Server: `MicrosoftAvailability` (`core.oauth.domain`, `@Value("${cockpit.microsoft.enabled:false}")`) and every place it is used:
  `OAuthController.providers` (filters Microsoft out) and `start` (400 before the session check, no pending
  authorization stored), `CalendarSourceService.create` and `MailAccountService.create` (400 "Microsoft is not enabled"
  with `errors[0].field = type`, before other validation), `CalendarSyncService.syncDue` (skips MICROSOFT sources) and
  `sync` (400 on "Sync now"), `MailPushService` (watchers, poll and restart skip MICROSOFT accounts). The 400s go
  through `InvalidRequestException` -> Problem Details with `errors`, matching `api/README.md`.
- Other Microsoft paths (`OAuthConnectionService`, contacts, invitations, `MailSender`, schedulers): no further
  background Microsoft job exists (Microsoft contacts sync was never built; the only schedulers touching Microsoft
  are the calendar tick and mail push/poll, both gated). OAuth callback stays reachable but cannot complete
  without a started flow.
- Configuration: `application.properties` default false via `MICROSOFT_ENABLED`; test config turns it on so the
  existing Microsoft tests keep running; `deploy/docker-compose.yml` and `deploy/.env.example` pass and document it;
  `docs/oauth-setup.md` explains deferral and re-enabling. `MicrosoftAvailabilityTests` checks the shipped default
  resolution.
- `api/openapi.yaml`: only description changes plus a new 400 response on `POST /calendar/sources/{id}/sync`,
  matching the controller annotations; the generated web client contains no descriptions, so no regeneration
  diff is expected there.
- No Microsoft code, enum value or migration deleted (diff removes only description/template lines).
- Web: calendars and mail settings render the Microsoft toggle only when `/oauth/providers` lists it (and hide it
  when that request fails); integrations hides existing Microsoft sources and connections (recorded assumption).
  `identities.html` change is a formatting-only rewrap.
- Android: no Microsoft/OAuth option exists in `android/app/src/main` (only a contact-source comment), consistent
  with the recorded assumption.
- Tests: `MicrosoftDisabledWebTests` (off state: providers, start, calendar source, mail account, scheduler and
  on-demand sync, Google still accepted), `MicrosoftAvailabilityTests`, `MailPushServiceTests` (both states),
  `MicrosoftCalendarSyncTests` (scheduler with the setting on), existing `OAuthConnectionsWebTests` (both providers
  listed with the setting on); Vitest cases for all three settings pages, on and off and on provider failure.
- Re-ran here: `npx --prefix web ng test --watch=false --include='src/app/features/settings/**/*.spec.ts'`
  -> 19 files, 468 tests passed. Server tests were not run by the reviewer (Gradle blocked); the parent reports them
  passing.
- Security: no secrets, no new logging, no auth changes; the gate is enforced server-side, the web hiding is only
  presentation.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Manual sync is refused for Microsoft calendar sources but not for Microsoft mail accounts**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/imap/MailAccountController.kt:215`
    - Category: Correctness
    - Problem: With the setting off, `POST /calendar/sources/{id}/sync` on an existing MICROSOFT source returns 400
      (`CalendarSyncService.kt:104`), but `POST /mail/accounts/{id}/folders/sync` and `POST /mail/accounts/{id}/test`
      on an existing MICROSOFT mail account still contact Microsoft (token refresh and IMAP), and the mail settings
      page still shows those accounts with their Test button. The recorded assumption only covers calendar "Sync now".
    - Why it matters: the two modules treat "Microsoft off" differently for the same kind of user-triggered action,
      so the API contract for existing Microsoft data is inconsistent. Only existing Microsoft accounts are affected,
      and the ADR says the owner has none in use, so the impact is small.
    - Suggested fix: either gate the manual mail actions for MICROSOFT accounts the same way (`microsoft.require` in
      the service) or record in the assumption that only background jobs and the calendar "Sync now" are gated.

- [ ] **CR-2 | Contacts page still tells the user to connect a Microsoft account**
    - File: `web/src/app/features/contacts/contacts-page.html:55`
    - Category: Quality
    - Problem: The empty-sync hint reads "Connect a Google or Microsoft account to sync them." It is shown regardless
      of the setting, although Microsoft connections can no longer be started and Microsoft contacts sync was never
      built (ADR 0003 drops contacts-microsoft).
    - Why it matters: ADR 0003 says the web client does not show Microsoft options. This text is outside the three
      settings screens the criterion names, so it does not block the criterion, but it points the user to something
      they cannot do.
    - Suggested fix: change the copy to mention Google only (or make it depend on the providers), in this task or a
      small follow-up. The file is outside this task's `touches`.

## Review conclusion

No material issues found. The acceptance criteria are implemented and tested. Two low-priority consistency items
(CR-1, CR-2) are optional. Evidence for each criterion still needs to be recorded with `tasks.py verify`
(the board currently shows all three criteria unverified).
