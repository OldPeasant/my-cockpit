---
title: Hide Microsoft integration behind a setting (default off)
status: in_progress
owner: dell26/agent-1
deps: [mail-identities]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/oauth,server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync,server/src/main/kotlin/ch/sonensei/cockpit/mail/imap,server/src/main/resources/application.properties,api/openapi.yaml,web/src/app/api/generated,web/src/app/features/settings/integrations,web/src/app/features/settings/calendars,web/src/app/features/settings/mail,android/app/src/main/java/ch/sonensei/cockpit/settings,docs/oauth-setup.md,deploy/.env.example]
prio: 2
lease_until: 2026-10-26T09:35:42Z
---
## Goal
The owner deferred Microsoft (docs/adr/0003-microsoft-deferred.md): the built Microsoft code stays, but nothing
offers it. One server setting (e.g. `cockpit.microsoft.enabled`, env `MICROSOFT_ENABLED`, default false) switches it
on again later.

## Acceptance criteria
- [x] With the setting off (default), the server neither offers nor accepts Microsoft: starting a Microsoft OAuth
  connection, creating a `MICROSOFT` calendar source or mail account -> 400 Problem Details ("Microsoft is not
  enabled"); any "available providers" information in the API leaves Microsoft out; Microsoft sync jobs don't run.
  With the setting on, behavior is unchanged. Server tests cover both; `api/openapi.yaml` regenerated if it changed
  - verified (2026-10-26 10:10+0100 by dell26/agent-1): server/gradlew -p server check jacocoTestReport -> 4755 tests, 0 failures; MicrosoftDisabledWebTests (off: /oauth/providers omits Microsoft, OAuth start, MICROSOFT calendar source, mail account and Sync now -> 400 'Microsoft is not enabled', scheduler skips Microsoft sources, Google still accepted), MicrosoftAvailabilityTests (default false), MailPushServiceTests (push/poll skip MICROSOFT when off, run when on), OAuthConnectionsWebTests + MicrosoftCalendarSyncTests (setting on, unchanged) pass; JaCoCo MicrosoftAvailability 6/6 lines, OAuthController 74/74; api/openapi.yaml regenerated (new 400 on POST /calendar/sources/{id}/sync)
- [x] Web settings (integrations, calendars, mail accounts) and Android show no Microsoft option when the server
  says Microsoft is off; Vitest (and Android unit tests where Android offers it) cover it; `cd web && npm run check` passes
  - verified (2026-10-26 10:10+0100 by dell26/agent-1): npm --prefix web run check -> passed, 175 test files; Vitest specs for calendars, mail and integrations settings cover Microsoft listed / not listed / providers request failing (no Microsoft option or existing Microsoft items when off); Android: grep of android/app/src/main shows no Microsoft or OAuth provider option, so nothing to hide and no Android test (recorded assumption)
- [x] No Microsoft code, enum value or migration is deleted; existing Microsoft tests still pass (setting on in
  those tests); `docs/oauth-setup.md` and `deploy/.env.example` say Microsoft is deferred and how to re-enable it;
  `sh scripts/check.sh` passes
  - verified (2026-10-26 10:10+0100 by dell26/agent-1): git diff --diff-filter=D 537cbc7ea HEAD -> no files deleted, no migration changed; removed server lines are only descriptions/replaced gated loops; existing Microsoft tests (MicrosoftCalendarAdapterTests, MicrosoftCalendarSyncTests, MicrosoftInvitation*, Graph*) pass with cockpit.microsoft.enabled=true in test config; docs/oauth-setup.md and deploy/.env.example document deferral and MICROSOFT_ENABLED=true; sh scripts/check.sh -> all checks passed

## Notes
- ASSUMPTION (2026-10-26 09:44+0100 by dell26/agent-1): Microsoft off also stops mail push/poll of existing MICROSOFT accounts (small edit in mail/sync/MailPushService, outside touches) and refuses manual 'Sync now' of MICROSOFT calendar sources with 400; existing Microsoft OAuth connections, sources and accounts are kept, not deleted.
- ASSUMPTION (2026-10-26 09:44+0100 by dell26/agent-1): Web Integrations page hides existing Microsoft sources and connections while the server does not list Microsoft in /oauth/providers (also when that request fails), instead of showing them as unsyncable.
- ASSUMPTION (2026-10-26 09:44+0100 by dell26/agent-1): Android has no Microsoft (or any OAuth provider) option yet, so nothing to hide and no Android test added; deploy/docker-compose.yml passes MICROSOFT_ENABLED (default false) to the server (outside touches, one line). Tests set cockpit.microsoft.enabled=true in test config so existing Microsoft tests keep running; MicrosoftDisabledWebTests set it false.
- ASSUMPTION (2026-10-26 10:00+0100 by dell26/agent-1): While Microsoft is off, manual 'Test' and 'sync folders' of an already existing MICROSOFT mail account stay allowed (only creation and background push/poll are blocked), unlike calendar 'Sync now' which returns 400; no such accounts exist since Microsoft was never set up. Contacts page text still mentions Microsoft (outside touches, follow-up if wanted).
