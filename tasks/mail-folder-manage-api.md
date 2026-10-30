---
title: Mail folders: create, rename and delete (API)
status: todo
owner:
deps: [mail-imap]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/imap,server/src/test/kotlin/ch/sonensei/cockpit/mail/imap,api/openapi.yaml,web/src/app/api/generated]
prio: 2
---
## Goal
The owner can create, rename and delete folders of a mail account from My Cockpit, instead of using another mail
client and waiting for the next folder poll. Requested by the owner (2026-10-30). SPEC.md does not cover folder
management yet; record the decisions below with `tasks.py assume`.

## Acceptance criteria
- [ ] `POST /api/v1/mail/accounts/{id}/folders` (`{"name", "parentId"?}`) creates the folder on the IMAP server
      (`CREATE`, then `SUBSCRIBE`), under the parent folder or at the top level, using the server's hierarchy
      delimiter, and caches it. Returns 201 with the folder and its ETag
- [ ] `PATCH /api/v1/mail/accounts/{id}/folders/{folderId}` additionally takes `{"name"}` (the existing role update
      stays as it is, with If-Match): `RENAME` on the server, the cached folder and all its subfolders get their new
      paths, and cached messages keep their ids, so links stay valid. When the server gives the renamed folder a new
      UIDVALIDITY, the next sync resyncs it as it does today
- [ ] `DELETE /api/v1/mail/accounts/{id}/folders/{folderId}` (If-Match) deletes the folder and its subfolders on the
      server and from the cache. Cached messages of deleted folders are kept as detached link targets, as the folder
      sync already does for vanished folders. INBOX and folders with a role (Archive, Trash, Drafts, Sent, Spam) cannot
      be deleted: 409 Problem. INBOX cannot be renamed either
- [ ] Errors as Problem Details: empty name or a name containing the delimiter -> 400; name already taken under that
      parent -> 409; unknown account or folder -> 404; stale or missing If-Match -> 412/428; unreachable server or a
      command the server rejects -> 502 with `errorCode` (shared mail connection handler). Each change publishes the
      account's folder change event, so open clients refresh the folder tree
- [ ] `api/openapi.yaml` and the web client regenerated (`api/README.md`); GreenMail integration tests for create
      (top level and nested), rename with subfolders and messages (ids kept), delete (messages detached, linked ones
      kept), and the refused cases; `cd server && ./gradlew check` and
      `./gradlew integrationTest --tests '*Folder*GreenMail*'` pass

## Notes
Server only; the web UI is web-mail-folder-manage, an Android UI is not planned yet. Deleting a folder that still
holds messages is allowed (the web client asks for confirmation); messages are not moved to Trash first.
