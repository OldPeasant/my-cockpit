---
title: Load mail older than the sync window (web + Android)
status: in_progress
owner: felix/agent-1
deps: [web-mail-read-actions,and-mail-actions,and-mail-note-links]
touches: [web/src/app/features/mail/list,android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail/list,android/app/src/main/res/values/strings_mail.xml,android/app/src/main/res/values-de/strings_mail.xml,web/src/locale]
prio: 3
lease_until: 2026-11-08T20:45:47Z
---
## Goal
SPEC.md §7.2: only a sync window is cached; `POST /api/v1/mail/folders/{id}/load-older` exists but no client calls it.

## Acceptance criteria
- [ ] At the end of a folder list (web and Android), "Load older from server" calls the endpoint and shows the progress, then the newly cached messages
- [ ] Errors (connection, nothing older) are shown non-blocking
- [ ] New strings in English and German on both clients (`web/src/locale`, `res/values{,-de}/`)
- [ ] Unit tests cover the new behavior on each changed part; `sh scripts/check.sh` passes

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
