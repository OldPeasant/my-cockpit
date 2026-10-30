---
title: Mail screens label accounts by email address, not the sender name
status: todo
owner:
deps: []
touches: [web/src/app/features/mail/list,android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail/list]
prio: 2
---
## Goal
Mail screens show each account by its email address. Today they show the account's display name, which is the
sender name ("Your name" in Settings), so several accounts of the same person look identical. Requested by the owner
(2026-10-30): email address only, on web and Android.

## Acceptance criteria
- [ ] Web, `web/src/app/features/mail/list`: the account heading in the folder tree (`mail-folder-tree.ts`, text and
      `aria-label`), the account line under the folder title (`accountName` in `mail-list-page.ts`) and the account
      chip on messages in "All inboxes" (`accountOf`) show the account's email address instead of its display name
- [ ] Android, `mail/list`: the account title in the folder picker and the account chip on messages show the email
      address (`MailModels.kt` maps the account's label from `emailAddress`, no longer from `displayName`)
- [ ] The display name is unchanged everywhere else: it stays the sender name in compose and identities, and the
      Settings account list keeps showing name and address
- [ ] Unit tests on both platforms assert the email address (two accounts with the same display name are told apart);
      `cd web && npm run check` and `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` pass

## Notes
Display-only change: no server or API change. If a test needs it, the fixtures' accounts get the same display name
and different addresses.
