---
title: Settings: calendars and integrations
status: todo
owner:
deps: [p0-web-settings,cal-caldav,cal-ics,cal-google,cal-microsoft]
touches: [web/src/app/features/settings/calendars,web/src/app/features/settings/integrations]
prio: 2
---
## Goal
Connect calendar sources (SPEC.md §6.1, §11).

## Acceptance criteria
- [ ] Add local calendar, CalDAV (URL, user, app password), ICS URL, connect Google/Microsoft (OAuth redirect flow); colors, visibility, default calendar
- [ ] Integrations page: per source last sync, error, 'sync now'
- [ ] Vitest component/service tests cover source forms and status rendering; `cd web && npm run check` passes

## Notes
