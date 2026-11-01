---
title: Mail folders: create, rename and delete (API)
status: in_progress
owner: dell26/agent-2
deps: [mail-imap]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/imap,server/src/test/kotlin/ch/sonensei/cockpit/mail/imap,api/openapi.yaml,web/src/app/api/generated]
prio: 2
lease_until: 2026-11-01T06:29:39Z
extended: dell26/agent-2
---
## Goal
The owner can create, rename and delete folders of a mail account from My Cockpit, instead of using another mail
client and waiting for the next folder poll. Requested by the owner (2026-10-30). SPEC.md does not cover folder
management yet; record the decisions below with `tasks.py assume`.

## Acceptance criteria
- [x] `POST /api/v1/mail/accounts/{id}/folders` (`{"name", "parentId"?}`) creates the folder on the IMAP server
      (`CREATE`, then `SUBSCRIBE`), under the parent folder or at the top level, using the server's hierarchy
      delimiter, and caches it. Returns 201 with the folder and its ETag
  - verified (2026-11-01 07:11+0100 by dell26/agent-2): cd server && ./gradlew integrationTest --tests '*Folder*GreenMail*' --rerun (GreenMail) -> BUILD SUCCESSFUL, MailFolderManageGreenMailTests 13/13 passed; ./gradlew check --rerun-tasks -> BUILD SUCCESSFUL (MailFolderManageTests 23, MailFolderManageWebTests 11 passed); covers create top level + nested, CREATE+SUBSCRIBE, 201+ETag
- [x] `PATCH /api/v1/mail/accounts/{id}/folders/{folderId}` additionally takes `{"name"}` (the existing role update
      stays as it is, with If-Match): `RENAME` on the server, the cached folder and all its subfolders get their new
      paths, and cached messages keep their ids, so links stay valid. When the server gives the renamed folder a new
      UIDVALIDITY, the next sync resyncs it as it does today
  - verified (2026-11-01 07:11+0100 by dell26/agent-2): cd server && ./gradlew integrationTest --tests '*Folder*GreenMail*' --rerun -> 13/13 passed; ./gradlew check --rerun-tasks -> BUILD SUCCESSFUL; covers rename with subfolders and messages (ids/links kept), name-only body keeps role, INBOX rename refused
- [x] `DELETE /api/v1/mail/accounts/{id}/folders/{folderId}` (If-Match) deletes the folder and its subfolders on the
      server and from the cache. Cached messages of deleted folders are kept as detached link targets, as the folder
      sync already does for vanished folders. INBOX and folders with a role (Archive, Trash, Drafts, Sent, Spam) cannot
      be deleted: 409 Problem. INBOX cannot be renamed either
  - verified (2026-11-01 07:11+0100 by dell26/agent-2): cd server && ./gradlew integrationTest --tests '*Folder*GreenMail*' --rerun -> 13/13 passed; ./gradlew check --rerun-tasks -> BUILD SUCCESSFUL; covers delete with subfolders (messages detached, linked kept), INBOX/role/role-subtree delete -> 409
- [x] Errors as Problem Details: empty name or a name containing the delimiter -> 400; name already taken under that
      parent -> 409; unknown account or folder -> 404; stale or missing If-Match -> 412/428; unreachable server or a
      command the server rejects -> 502 with `errorCode` (shared mail connection handler). Each change publishes the
      account's folder change event, so open clients refresh the folder tree
  - verified (2026-11-01 07:11+0100 by dell26/agent-2): cd server && ./gradlew integrationTest --tests '*Folder*GreenMail*' --rerun -> 13/13 passed; ./gradlew check --rerun-tasks -> BUILD SUCCESSFUL (MailFolderManageWebTests 11 passed); covers 400 blank/delimiter/null name, 409 taken, 404, 412/428, 502 SERVER_REJECTED, MAIL_FOLDER events
- [x] `api/openapi.yaml` and the web client regenerated (`api/README.md`); GreenMail integration tests for create
      (top level and nested), rename with subfolders and messages (ids kept), delete (messages detached, linked ones
      kept), and the refused cases; `cd server && ./gradlew check` and
      `./gradlew integrationTest --tests '*Folder*GreenMail*'` pass
  - verified (2026-11-01 07:11+0100 by dell26/agent-2): api/openapi.yaml + web/src/app/api/generated regenerated (commit ecf9c14be); cd server && ./gradlew check --rerun-tasks -> BUILD SUCCESSFUL; ./gradlew integrationTest --tests '*Folder*GreenMail*' --rerun -> 13/13 passed

## Notes
Server only; the web UI is web-mail-folder-manage, an Android UI is not planned yet. Deleting a folder that still
holds messages is allowed (the web client asks for confirmation); messages are not moved to Trash first.
- ASSUMPTION (2026-11-01 06:06+0100 by dell26/agent-2): Folder names are trimmed; a name also may not be 'INBOX' (any case) at the top level (409). A server refusal (NO/BAD, or folder not found) is a new MailConnectionError SERVER_REJECTED -> 502 via the shared MailConnectionAdvice.
- ASSUMPTION (2026-11-01 06:07+0100 by dell26/agent-2): PATCH folder: a body with only 'name' renames without touching the role; the role is changed when 'role' or 'automatic' is sent, or when 'name' is absent (unchanged legacy behaviour, so {} still clears the role). Name and role together: rename first, then role.
- ASSUMPTION (2026-11-01 06:07+0100 by dell26/agent-2): DELETE also refuses (409) a folder whose subfolders include INBOX or a role folder, since the delete is recursive. Deleting a folder that is already gone on the server just uncaches it (204). Renaming a folder gone on the server -> 502 SERVER_REJECTED.
- ASSUMPTION (2026-11-01 06:07+0100 by dell26/agent-2): Create under a parent on a server without hierarchy delimiter -> 400 on name. Rename only checks the cache + server for a taken target; RENAME also re-SUBSCRIBEs the new name (best effort), DELETE UNSUBSCRIBEs (best effort).
- ASSUMPTION (2026-11-01 06:28+0100 by dell26/agent-2): Folder sync, role override, create, rename and delete are serialized per account by an in-process lock held across the IMAP commands and the cache update (fixes a rename/sync race that would recreate folders with new ids). Assumes one server instance, as deployed; the existing DB row lock stays for the cache step.
- ASSUMPTION (2026-11-01 06:28+0100 by dell26/agent-2): PATCH folder with an explicit {"name": null} is refused with 400 on 'name' instead of being read as an absent name (which would clear the role).
- extended by dell26/agent-2: Implementation complete and committed (create/rename/delete API, per-account lock, null-name 400, GreenMail tests, regenerated openapi + web client); scripts/check.sh passes. tests.md is current (final code d0d9f495a-equivalent). Left: (1) re-run code-reviewer on final code (review.md predates the CR-1/CR-2 fixes in 7bd11afe6); fixes were made by the parent rather than the fixer, so record that in quality.json or run fixer for fixes.md; (2) run cd server && ./gradlew check and ./gradlew integrationTest --tests '*Folder*GreenMail*', record verify 1-5; (3) write+commit reviews/mail-folder-manage-api/quality.json; (4) tasks.py done.
