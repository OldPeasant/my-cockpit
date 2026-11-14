---
title: Android: mail → Next action & archive (read view, New view, mail list)
status: in_progress
owner: dell26/agent-1
deps: [mail-task-archive-api]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/read,android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/main/java/ch/sonensei/cockpit/newitems,android/app/src/test/java/ch/sonensei/cockpit/mail,android/app/src/test/java/ch/sonensei/cockpit/newitems,android/app/src/main/res/values/strings_mail.xml,android/app/src/main/res/values-de/strings_mail.xml,android/app/src/main/res/values/strings_new.xml,android/app/src/main/res/values-de/strings_new.xml]
prio: 2
lease_until: 2026-11-14T08:34:06Z
---
## Goal
Android part of "→ Next action & archive" (server call from `mail-task-archive-api`), same behavior as
`web-mail-task-archive`.

## Acceptance criteria
- [ ] Mail read view: "Next action & archive" action next to "To task" (which stays unchanged); on success the new task's detail opens, and Back returns to where the mail was opened from
- [ ] New view: mail rows offer the same action, with the same result as in the read view
- [ ] Mail list multi-select: the action creates one Next action per selected mail and archives them; the list stays, the mails leave it, and a snackbar "N next actions created" offers to open Next Actions
- [ ] Archive failure: the task is kept (single mail: its detail still opens) and a message says which mail was not archived; a failed task creation leaves the mail in place with an error
- [ ] The task detail's linked items show the mail, and opening it shows the archived mail
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`); touch targets ≥ 48dp, actions fit at 320dp width (overflow menu if needed)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-14 with the owner: from a mail, make a Next action and archive the mail in one step (mails already clarified should not pass through the cockpit Inbox again); create a project directly from the task detail.
- ASSUMPTION (2026-11-14 09:17+0100 by dell26/agent-1): Read view toolbar: 'To Waiting' moved into the overflow menu so 'Next action & archive' (TaskAlt icon) sits next to 'To task' and the toolbar still fits 320dp; when archiving fails the task detail still opens and Back returns to the (unarchived) mail, which shows the notice naming it
