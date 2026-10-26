---
title: Phase 6 demo script
status: in_progress
owner: dell26/agent-1
deps: [web-contacts,contacts-google,contacts-birthdays,backup-contrib-contacts,web-mail-contacts-autocomplete,bookmarks-snapshot,backup-contrib-bookmarks,and-bookmarks-share,web-journal,backup-contrib-journal,and-journal-habits,web-dashboard,and-files,and-contacts,and-review,web-passkeys,and-passkeys,p5-smoke]
touches: [web/e2e/phase6]
prio: 2
lease_until: 2026-10-26T05:58:45Z
---
## Goal
Automate SPEC.md §13.2 Phase 6.

## Acceptance criteria
- [ ] `web/e2e/phase6` covers steps 1–5 (step 2 via API instead of Android share); earlier phase specs pass

## Notes
- ASSUMPTION (2026-10-26 06:32+0100 by dell26/agent-1): Step 2 (Android share -> Read later) bookmarks the e2e server's own page http://localhost:8080/ (title 'My Cockpit', favicon.svg): the e2e stack has no internet, so application-e2e.properties now sets cockpit.bookmarks.fetch.allow-private-networks=true (outside the task's touches, e2e profile only).
- ASSUMPTION (2026-10-26 06:32+0100 by dell26/agent-1): Step 5 restores the backup into the same e2e server (server-fresh is single-use and taken by phase 2): the phase 6 data is changed after the backup (contact + bookmark trashed, a habit check removed) and the restore must bring the snapshot back exactly.
- ASSUMPTION (2026-10-26 06:32+0100 by dell26/agent-1): Weekly reviews are not in backups yet (no contributor; task backup-contrib-review is todo and not a dep of p6-smoke). Step 5 checks contacts, bookmarks, journal and habits now, and checks 'last weekly review' after the restore automatically once data/weekly_reviews.json(l) appears in the backup.
- ASSUMPTION (2026-10-26 06:32+0100 by dell26/agent-1): Step 3's 3 habit checks are on Mon-Wed of next week (server + page clock moved forward like phase 1), because the web only checks off today; steps 4-5 run on that moved clock.
