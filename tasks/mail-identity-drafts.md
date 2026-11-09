---
title: Choose sending identity and resume drafts (web + Android)
status: in_progress
owner: dell26/agent-1
deps: [and-mail-compose-plus,mail-load-older-ui]
touches: [web/src/app/features/mail/compose,web/src/app/features/mail/list,android/app/src/main/java/ch/sonensei/cockpit/mail/compose,android/app/src/test/java/ch/sonensei/cockpit/mail/compose,android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail/list,android/app/src/main/res/values/strings_mail_compose.xml,android/app/src/main/res/values-de/strings_mail_compose.xml,web/src/locale,server/src/main/kotlin/ch/sonensei/cockpit/mail,server/src/test/kotlin/ch/sonensei/cockpit/mail]
prio: 3
lease_until: 2026-11-09T01:31:06Z
releases: 1
extended: 
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
- ASSUMPTION (2026-11-09 02:09+0100 by dell26/agent-1): From picker lists the account address only when the account has no default identity: the server sends identityId=null from the default identity, so the address cannot be chosen explicitly then (no server change made; the API already accepts identityId)
- ASSUMPTION (2026-11-09 02:10+0100 by dell26/agent-1): Opening an untouched draft from the Drafts folder and leaving keeps it (Android does not discard it like an untouched reply); identities that fail to load leave only the account address in the web picker
- release by dell26/agent-1: DONE (committed): web From picker lists identities per account (MailComposeService.identities, senders/senderKeyValue/chooseSender in mail-compose-page; another account's identity moves the draft), web list opens Drafts-folder mail in /mail/compose?draft=; web mail compose+list specs pass (573) but no NEW tests yet. Android: ComposeMode.Draft (list opens Drafts-folder mail in compose via MailListState.isDraft, repository loads api.get, untouched opened draft not discarded on leave, title string en/de); ktlint+compileDebugKotlin pass, no tests yet. LEFT: Android identity picker (identities endpoint GET api/v1/mail/accounts/{id}/identities in MailComposeApi, identityId in CreateDraftDto/DraftContentDto/DraftDto/Draft, session state + From picker UI in MailComposeScreen, strings if any); unit tests on both clients; server needs no change (identityId already accepted); then test-writer, code-reviewer, verify, quality.json, done.
