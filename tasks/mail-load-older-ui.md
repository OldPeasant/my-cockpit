---
title: Load mail older than the sync window (web + Android)
status: in_progress
owner: felix/agent-1
deps: [web-mail-read-actions,and-mail-actions,and-mail-note-links]
touches: [web/src/app/features/mail/list,android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail/list,android/app/src/main/res/values/strings_mail.xml,android/app/src/main/res/values-de/strings_mail.xml,web/src/locale]
prio: 3
lease_until: 2026-11-08T21:37:47Z
---
## Goal
SPEC.md §7.2: only a sync window is cached; `POST /api/v1/mail/folders/{id}/load-older` exists but no client calls it.

## Acceptance criteria
- [x] At the end of a folder list (web and Android), "Load older from server" calls the endpoint and shows the progress, then the newly cached messages
  - verified (2026-11-08 22:15+0100 by felix/agent-1): web: npm --prefix web run test:ci (mail-list-store.spec + mail-list-page.spec: loadOlder posts load-older, shows loadingOlder progress, then re-read list incl. older items) -> pass; android: ./gradlew testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.list.MailLoadOlder*' (progress, then newly cached messages) -> pass; full suites in sh scripts/check.sh -> all checks passed
- [x] Errors (connection, nothing older) are shown non-blocking
  - verified (2026-11-08 22:15+0100 by felix/agent-1): web store/page tests for failure (olderError inline, list kept, retry possible), nothing-older (neutral olderNote), reload while loading -> pass (npm --prefix web run coverage: 4553 tests passed); android MailLoadOlder* tests for OFFLINE/FAILED/NOTHING_OLDER inline messages -> pass
- [x] New strings in English and German on both clients (`web/src/locale`, `res/values{,-de}/`)
  - verified (2026-11-08 22:16+0100 by felix/agent-1): web/src/locale messages.xlf + messages.de.xlf: mail.loadOlderFromServer, mail.loadingOlder, mail.olderFailed, mail.list.nothingOlder with German targets; messages-de.spec.ts (every unit translated) passes in npm run check; android res/values{,-de}/strings_mail.xml: 5 new strings in both, lintDebug passes
- [x] Unit tests cover the new behavior on each changed part; `sh scripts/check.sh` passes
  - verified (2026-11-08 22:16+0100 by felix/agent-1): new unit tests in web mail-list-store.spec.ts/mail-list-page.spec.ts and android mail.list.MailLoadOlder*; sh scripts/check.sh on final code (incl. ad5189a17 tests) -> == all checks passed

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 21:30+0100 by felix/agent-1): The 'Load older from server' button is offered at the end of every folder list (not the unified Inbox, not search) regardless of the list response's hasOlder hint (it only says 'may'); a click that finds nothing shows 'no older messages' inline next to the button. Newly cached mails are shown by re-reading the folder's first (shown + loaded) messages.
- ASSUMPTION (2026-11-08 21:52+0100 by felix/agent-1): TW-2 (missing German for calendar.invite.* units and stale capture.save source in messages.de.xlf) is fixed in this task: regenerating messages.xlf surfaced it and web/src/locale is in touches; otherwise .taskcheck fails.
