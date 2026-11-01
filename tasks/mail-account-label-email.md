---
title: Mail screens label accounts by email address, not the sender name
status: in_progress
owner: dell26/agent-1
deps: []
touches: [web/src/app/features/mail/list,android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail/list]
prio: 2
lease_until: 2026-11-01T05:34:01Z
---
## Goal
Mail screens show each account by its email address. Today they show the account's display name, which is the
sender name ("Your name" in Settings), so several accounts of the same person look identical. Requested by the owner
(2026-10-30): email address only, on web and Android.

## Acceptance criteria
- [x] Web, `web/src/app/features/mail/list`: the account heading in the folder tree (`mail-folder-tree.ts`, text and
      `aria-label`), the account line under the folder title (`accountName` in `mail-list-page.ts`) and the account
      chip on messages in "All inboxes" (`accountOf`) show the account's email address instead of its display name
  - verified (2026-11-01 06:09+0100 by dell26/agent-1): web: mail-folder-tree.ts heading text+aria-label, mail-list-page.ts accountName/accountOf now use emailAddress; npx ng test --watch=false --include='src/app/features/mail/list/**/*.spec.ts' -> 193 passed (folder-tree spec asserts aria-label/h2 = work@/home@example.test; list-page specs assert account line + All-inboxes chips = acc-1/acc-2@example.test with both accounts named 'Ann Example')
- [x] Android, `mail/list`: the account title in the folder picker and the account chip on messages show the email
      address (`MailModels.kt` maps the account's label from `emailAddress`, no longer from `displayName`)
  - verified (2026-11-01 06:09+0100 by dell26/agent-1): android: MailAccount.label (MailModels.kt, = emailAddress, fallback name) used for picker account title, message chip and sort; android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug -> BUILD SUCCESSFUL (MailListScreenTest picker titles = me@example.org/me@example.net, MailListScreenStatesTest chips, MailModelsTest label, MailRepositoryTest sort by label; all with same display name 'Ann Example')
- [x] The display name is unchanged everywhere else: it stays the sender name in compose and identities, and the
      Settings account list keeps showing name and address
  - verified (2026-11-01 06:09+0100 by dell26/agent-1): git diff origin/main...HEAD --stat touches only web/.../mail/list and android/.../mail/list (+tests); MailAccount.name still = displayName (MailModelsTest asserts name 'Ann Example'); compose/identities/settings untouched; android+web full suites pass (compose/settings tests included) -> unchanged behaviour
- [x] Unit tests on both platforms assert the email address (two accounts with the same display name are told apart);
      `cd web && npm run check` and `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` pass
  - verified (2026-11-01 06:09+0100 by dell26/agent-1): npm --prefix web run check on 2970e9f79 -> lint ok, 3858 tests passed, build ok; android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug on 2970e9f79 -> BUILD SUCCESSFUL; both platforms' tests use two accounts named 'Ann Example' with different addresses

## Notes
Display-only change: no server or API change. If a test needs it, the fixtures' accounts get the same display name
and different addresses.
- ASSUMPTION (2026-11-01 05:55+0100 by dell26/agent-1): Android: MailAccount keeps name = display name (compose builds the sender 'name <address>' from it, outside this task's touches) and gains a computed label = emailAddress (falls back to name if blank) that the mail list screens show and sort by; satisfies 'label from emailAddress' without touching compose.
