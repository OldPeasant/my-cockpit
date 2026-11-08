---
title: Android mail: unread, flag, move, spam, multi-select
status: in_progress
owner: dell26/agent-2
deps: [and-mail-search-nav]
touches: [android/app/src/main/java/ch/sonensei/cockpit/mail/list,android/app/src/test/java/ch/sonensei/cockpit/mail/list,android/app/src/main/java/ch/sonensei/cockpit/mail/read,android/app/src/test/java/ch/sonensei/cockpit/mail/read,android/app/src/main/res/values/strings_mail.xml,android/app/src/main/res/values-de/strings_mail.xml]
prio: 2
lease_until: 2026-11-08T11:27:51Z
extended: dell26/agent-2
---
## Goal
Android mail cannot mark read/unread, flag, or move, has spam only in the read view and no multi-select (web:
`mail-list-page.html:36-121`). Decided: mobile versions (long-press multi-select).

## Acceptance criteria
- [ ] Read view menu: mark unread (returns to the list), flag/unflag, move to folder (folder picker of the same account), spam
- [ ] Long-press on a row starts multi-select; bulk archive, delete, spam, read/unread, flag, move
- [ ] Swipe actions stay; optimistic updates with undo where the action can be undone
- [ ] New strings are in English and German (`res/values/` + `res/values-de/`)
- [ ] Unit/Compose tests cover the new behavior; `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` and `sh scripts/check.sh` pass

## Notes
Planned 2026-11-08 from the owner's UX request (web/Android parity, mail images, back navigation, mail search); the parity inventory and decisions are in `docs/PARITY.md`.
- ASSUMPTION (2026-11-08 11:18+0100 by dell26/agent-2): Undo (snackbar) is offered for list actions (swipes and bulk): marks are undone by the inverse action, moves/archive/delete/spam by MOVE back to each mail's original folder; not offered for a delete in Trash (permanent) or when a mail's folder is unknown. The read view has no undo snackbar: flag toggles are reversible from its menu, unread returns to the list.
- ASSUMPTION (2026-11-08 11:18+0100 by dell26/agent-2): Bulk 'Move to folder' is only enabled when all selected mails belong to one account (the server needs a target folder of the messages' account); in the unified Inbox with mixed accounts it is disabled.
- ASSUMPTION (2026-11-08 11:18+0100 by dell26/agent-2): Multi-select works in the mailbox list only, not in search results; spam moved from the read view toolbar into its overflow menu (with unread, flag, move) to keep the toolbar within phone width.
- extended by dell26/agent-2: All features implemented (read view menu, long-press multi-select + bulk actions, swipe + undo, en/de strings); CR-1/CR-2 fixed (fixes.md); second code review: no material issues (one optional P3 on bulk failure wording). sh scripts/check.sh passes at 87ac84a08. Left: final test-writer run on the current code to write reviews/and-mail-actions/tests.md with coverage (its extra tests are committed in 87ac84a08); re-run code-reviewer if the review is stale against those tests; tasks.py verify for criteria 1-5; write quality.json; done.
