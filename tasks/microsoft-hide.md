---
title: Hide Microsoft integration behind a setting (default off)
status: in_progress
owner: dell26/agent-1
deps: [mail-identities]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/oauth,server/src/main/kotlin/ch/sonensei/cockpit/calendar/sync,server/src/main/kotlin/ch/sonensei/cockpit/mail/imap,server/src/main/resources/application.properties,api/openapi.yaml,web/src/app/api/generated,web/src/app/features/settings/integrations,web/src/app/features/settings/calendars,web/src/app/features/settings/mail,android/app/src/main/java/ch/sonensei/cockpit/settings,docs/oauth-setup.md,deploy/.env.example]
prio: 2
lease_until: 2026-10-26T09:00:20Z
---
## Goal
The owner deferred Microsoft (docs/adr/0003-microsoft-deferred.md): the built Microsoft code stays, but nothing
offers it. One server setting (e.g. `cockpit.microsoft.enabled`, env `MICROSOFT_ENABLED`, default false) switches it
on again later.

## Acceptance criteria
- [ ] With the setting off (default), the server neither offers nor accepts Microsoft: starting a Microsoft OAuth
  connection, creating a `MICROSOFT` calendar source or mail account -> 400 Problem Details ("Microsoft is not
  enabled"); any "available providers" information in the API leaves Microsoft out; Microsoft sync jobs don't run.
  With the setting on, behavior is unchanged. Server tests cover both; `api/openapi.yaml` regenerated if it changed
- [ ] Web settings (integrations, calendars, mail accounts) and Android show no Microsoft option when the server
  says Microsoft is off; Vitest (and Android unit tests where Android offers it) cover it; `cd web && npm run check` passes
- [ ] No Microsoft code, enum value or migration is deleted; existing Microsoft tests still pass (setting on in
  those tests); `docs/oauth-setup.md` and `deploy/.env.example` say Microsoft is deferred and how to re-enable it;
  `sh scripts/check.sh` passes

## Notes
- ASSUMPTION (2026-10-26 09:44+0100 by dell26/agent-1): Microsoft off also stops mail push/poll of existing MICROSOFT accounts (small edit in mail/sync/MailPushService, outside touches) and refuses manual 'Sync now' of MICROSOFT calendar sources with 400; existing Microsoft OAuth connections, sources and accounts are kept, not deleted.
- ASSUMPTION (2026-10-26 09:44+0100 by dell26/agent-1): Web Integrations page hides existing Microsoft sources and connections while the server does not list Microsoft in /oauth/providers (also when that request fails), instead of showing them as unsyncable.
