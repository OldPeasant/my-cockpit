---
title: Mail actions
status: in_progress
owner: dell26/agent-1
deps: [mail-read-api]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/api,server/src/test/kotlin/ch/sonensei/cockpit/mail/api]
prio: 1
lease_until: 2026-10-21T02:41:01Z
releases: 1
extended: 
---
## Goal
Triage actions (SPEC.md §7.3).

## Acceptance criteria
- [ ] `POST /api/v1/mail/messages/actions {ids, action, targetFolderId?}`: READ, UNREAD, FLAG, UNFLAG, MOVE, ARCHIVE, DELETE (to Trash; permanent in Trash), SPAM; applied on IMAP and cache consistently; partial failures reported per id
- [x] `external` GreenMail tests reflect every action
  - verified (2026-10-21 04:05+0200 by dell26/agent-1): cd server && ./gradlew integrationTest --tests '*MailActionGreenMailTests' (GreenMail 2.1.5, @ExternalTest) -> 16 tests, 0 failures: READ, UNREAD, FLAG, UNFLAG, MOVE, ARCHIVE, DELETE (to Trash + permanent in Trash), SPAM each checked on IMAP server and cache
- [x] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag
  - verified (2026-10-21 04:05+0200 by dell26/agent-1): cd server && ./gradlew test --tests '*OpenApiContractTest' (without UPDATE_OPENAPI, after merging latest main) -> 1 test passed; api/openapi.yaml contains POST /api/v1/mail/messages/actions, web client regenerated

## Notes
- ASSUMPTION (2026-10-21 03:02+0200 by dell26/agent-5): Actions response is 200 with one result per distinct id in request order ({id, ok, errorCode, detail}), also when all fail; no 207 Multi-Status
- ASSUMPTION (2026-10-21 03:02+0200 by dell26/agent-5): ARCHIVE/DELETE/SPAM fail per id with NO_ROLE_FOLDER when the account has no (selectable) Archive/Trash/Spam folder; acting on a message already in the target folder is a successful no-op; SPAM only moves to the Spam folder (no $Junk keyword)
- ASSUMPTION (2026-10-21 03:02+0200 by dell26/agent-5): Moves use UID MOVE, else UID COPY + \Deleted + expunge; without UIDPLUS the expunge is a plain EXPUNGE (also removes other \Deleted messages of that folder, like most clients). Without COPYUID the cached row is detached (linked) or deleted and the next target-folder sync brings it back
- ASSUMPTION (2026-10-21 03:02+0200 by dell26/agent-5): Bulk actions take at most 1000 ids and do not use ETag/If-Match (bulk, idempotent flag/move semantics)
- ASSUMPTION (2026-10-21 03:02+0200 by dell26/agent-5): Regenerated the web client (web/src/app/api/generated) together with api/openapi.yaml so the generated files stay in sync, although touches lists only server folders
- release by dell26/agent-5: Implementation complete and committed (f627493b..a6cc8fdc): POST /api/v1/mail/messages/actions with all 8 actions, per-id results, GreenMail tests per action (MailActionGreenMailTests), controller/web/service tests, api/openapi.yaml + web client regenerated; review.md findings CR-1..CR-4 fixed (fixes.md). sh scripts/check.sh passes. LEFT: (1) tests.md is missing - the test-writer's tests were committed (f9920402) but it wrote no report: run test-writer on the final code (base 8a341743 or git merge-base HEAD origin/main) so it writes reviews/mail-actions-api/tests.md with coverage; (2) re-run code-reviewer on the final code (fixes changed code after review.md); (3) tasks.py verify 1-3 (run GreenMail tests and OpenApiContractTest without UPDATE_OPENAPI); (4) write reviews/mail-actions-api/quality.json, commit, tasks.py done.
