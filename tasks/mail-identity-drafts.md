---
title: Choose sending identity and resume drafts (web + Android)
status: in_progress
owner: dell26/agent-1
deps: [and-mail-compose-plus,mail-load-older-ui]
touches: [web/src/app/features/mail/compose,web/src/app/features/mail/list,android/app/src/main/java/ch/sonensei/cockpit/mail/compose,android/app/src/test/java/ch/sonensei/cockpit/mail/compose,android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail/list,android/app/src/main/res/values/strings_mail_compose.xml,android/app/src/main/res/values-de/strings_mail_compose.xml,web/src/locale,server/src/main/kotlin/ch/sonensei/cockpit/mail,server/src/test/kotlin/ch/sonensei/cockpit/mail]
prio: 3
lease_until: 2026-11-09T01:25:50Z
---
## Goal
SPEC.md §7.3 says "choose sending identity"; both clients only pick the account. Neither can open a draft from
the Drafts folder to continue it.

## Acceptance criteria
- [ ] Compose (web + Android): the From picker lists identities (per account) and sends with the chosen one; if the send/draft API does not accept an identity yet, add it on the server first and regenerate `api/openapi.yaml` and the web client
- [ ] Opening a message in the Drafts folder (web and Android) opens it in compose; saving updates that draft
- [ ] New strings in English and German on both clients (`web/src/locale`, `res/values{,-de}/`)
- [ ] Unit tests cover the new behavior on each changed part; `sh scripts/check.sh` passes

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
