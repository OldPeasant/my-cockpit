---
title: Phase 6 demo script
status: in_progress
owner: dell26/agent-1
deps: [web-contacts,contacts-google,contacts-birthdays,backup-contrib-contacts,web-mail-contacts-autocomplete,bookmarks-snapshot,backup-contrib-bookmarks,and-bookmarks-share,web-journal,backup-contrib-journal,and-journal-habits,web-dashboard,and-files,and-contacts,and-review,web-passkeys,and-passkeys,p5-smoke]
touches: [web/e2e/phase6]
prio: 2
lease_until: 2026-10-26T05:48:41Z
---
## Goal
Automate SPEC.md §13.2 Phase 6.

## Acceptance criteria
- [ ] `web/e2e/phase6` covers steps 1–5 (step 2 via API instead of Android share); earlier phase specs pass

## Notes
- ASSUMPTION (2026-10-26 06:32+0100 by dell26/agent-1): Step 2 (Android share -> Read later) bookmarks the e2e server's own page http://localhost:8080/ (title 'My Cockpit', favicon.svg): the e2e stack has no internet, so application-e2e.properties now sets cockpit.bookmarks.fetch.allow-private-networks=true (outside the task's touches, e2e profile only).
