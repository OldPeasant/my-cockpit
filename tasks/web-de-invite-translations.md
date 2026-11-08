---
title: German translations for the iTIP invitation dialog (calendar.invite.*)
status: todo
owner:
deps: []
touches: [web/src/locale]
prio: 2
---
## Goal
`cd web && npm run build:de` fails with 16 "No translation found" errors, all `calendar.invite.*` (the invitation
dialog from itip-api: `web/src/app/features/calendar/edit/invitation-dialog.ts`, `event-dialog.*`). Add the units to
`web/src/locale/messages.xlf` and German targets to `messages.de.xlf`.

## Acceptance criteria
- [ ] `cd web && npm run build:de` reports no missing translation
- [ ] `cd web && npm run check` and `sh scripts/check.sh` pass

## Notes
Found 2026-11-08 by mail-images-web-settings. Add units by hand (a full `npm run i18n:extract` reorders the whole file).
