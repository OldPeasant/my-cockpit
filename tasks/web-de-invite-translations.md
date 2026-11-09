---
title: German translations for the iTIP invitation dialog (calendar.invite.*)
status: in_progress
owner: dell26/agent-1
deps: []
touches: [web/src/locale]
prio: 2
lease_until: 2026-11-09T01:00:25Z
---
## Goal
`cd web && npm run build:de` fails with 16 "No translation found" errors, all `calendar.invite.*` (the invitation
dialog from itip-api: `web/src/app/features/calendar/edit/invitation-dialog.ts`, `event-dialog.*`). Add the units to
`web/src/locale/messages.xlf` and German targets to `messages.de.xlf`.

## Acceptance criteria
- [x] `cd web && npm run build:de` reports no missing translation
  - verified (2026-11-09 01:41+0100 by dell26/agent-1): cd web && npm run build:de (at 2e207f3eb) -> bundle generation complete, 0 'No translation found' errors; 16 calendar.invite.* units present in messages.xlf and messages.de.xlf (added by ba52ebac3)
- [ ] `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Found 2026-11-08 by mail-images-web-settings. Add units by hand (a full `npm run i18n:extract` reorders the whole file).
- ASSUMPTION (2026-11-09 01:41+0100 by dell26/agent-1): No code change: the 16 calendar.invite.* units and German targets were already added to main by mail-load-older-ui (ba52ebac3); this task only verifies build:de and records review evidence.
