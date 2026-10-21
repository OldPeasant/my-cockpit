---
title: Settings: calendars and integrations
status: in_progress
owner: dell26/agent-2
deps: [p0-web-settings,cal-caldav,cal-ics,cal-google,cal-microsoft]
touches: [web/src/app/features/settings/calendars,web/src/app/features/settings/integrations]
prio: 2
lease_until: 2026-10-21T17:52:12Z
---
## Goal
Connect calendar sources (SPEC.md §6.1, §11).

## Acceptance criteria
- [ ] Add local calendar, CalDAV (URL, user, app password), ICS URL, connect Google/Microsoft (OAuth redirect flow); colors, visibility, default calendar
- [ ] Integrations page: per source last sync, error, 'sync now'
- [ ] Vitest component/service tests cover source forms and status rendering; `cd web && npm run check` passes

## Notes
- ASSUMPTION (2026-10-21 19:28+0200 by dell26/agent-2): Server had no API to add external calendar sources (SPEC 6.4 lists POST /calendar/sources); added POST /api/v1/calendar/sources (server calendar/sync, outside touches) for CALDAV {url,username,password}, ICS_FEED {url}, GOOGLE/MICROSOFT {oauthConnectionId}; same OAuth connection twice returns the existing source (200). No DELETE/PATCH of sources (not in criteria).
- ASSUMPTION (2026-10-21 19:35+0200 by dell26/agent-2): OAuth callback lands on /settings/integrations: purpose=CALENDAR creates the GOOGLE/MICROSOFT calendar source for connectionId and syncs it; other purposes (MAIL/CONTACTS) only show 'account connected' (mail/contacts pages handle their own linking). Adding CalDAV/ICS syncs the new source at once so its calendars appear; a failed first sync keeps the source and shows the error.
- ASSUMPTION (2026-10-21 19:35+0200 by dell26/agent-2): Shared CalendarSourcesService lives in settings/calendars and is used by settings/integrations; settings.routes.ts (+spec) replaced the two placeholders. Integrations also lists OAuth connections with a Reconnect for RECONNECT_REQUIRED (no disconnect/remove of sources: no API in criteria).
