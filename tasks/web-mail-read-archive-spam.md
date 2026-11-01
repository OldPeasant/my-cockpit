---
title: Mail read view: Archive and Spam buttons
status: in_progress
owner: dell26/agent-2
deps: [web-mail-read-delete]
touches: [web/src/app/features/mail/read]
prio: 2
lease_until: 2026-11-01T08:35:05Z
---
## Goal
The mail read view (`/mail/messages/:id`) gets Archive and Spam buttons next to the Delete button from
web-mail-read-delete, so a message can be triaged right after reading it. Requested by the owner (2026-10-30).
The Android read view already has Archive and Delete.

## Acceptance criteria
- [x] Once the message has loaded, the read view's action bar shows Archive and Spam buttons. They call the existing
      mail actions API (`ARCHIVE`, `SPAM`) the same way the Delete button does, and all three are disabled while one
      of them runs
  - verified (2026-11-01 09:15+0100 by dell26/agent-2): npx ng test --watch=false --include src/app/features/mail/read (web) -> 132 passed; spec checks Archive/Spam buttons appear only after load, call MailService.apply ARCHIVE/SPAM via shared triage() with Delete, all three disabled while one runs
- [x] On success the app returns to the mail list the message was opened from (as after Delete). On failure the view
      stays open and shows the server's error, e.g. when the account has no Archive or Spam folder (NO_ROLE_FOLDER)
  - verified (2026-11-01 09:15+0100 by dell26/agent-2): same focused run -> 132 passed; RouterTestingHarness tests: Archive/Spam/Delete return to /mail/folders/f-work (origin) with replaceUrl, /mail when opened elsewhere; NO_ROLE_FOLDER and ApiError keep view open and show server detail
- [x] Keyboard shortcuts as in the list: `e` archives, `!` marks as spam (not while typing in an input or editor)
  - verified (2026-11-01 09:15+0100 by dell26/agent-2): same focused run -> 132 passed; document keydown tests: e archives, ! spams, ignored in input/editor, chords, repeats, open dialog, while loading
- [ ] Unit tests for both buttons, both shortcuts, success navigation and the error case; `cd web && npm run check` passes

## Notes
Reuse the Delete button's action code from web-mail-read-delete instead of duplicating it. Move is not in scope.
- ASSUMPTION (2026-11-01 08:43+0100 by dell26/agent-2): Added the 6 new mailRead.* trans-units by hand to web/src/locale/messages.xlf and messages.de.xlf (outside touches, needed for i18n): a full 'npm run i18n:extract' would also pull in another task's unextracted calendar.invite.* units. German: 'Archivieren', 'Spam', as in the mail list.
