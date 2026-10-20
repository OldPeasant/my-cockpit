---
title: IMAP/SMTP connectivity and folder sync
status: in_progress
owner: dell26/agent-1
deps: [mail-model,oauth-connections]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/imap,server/src/test/kotlin/ch/sonensei/cockpit/mail/imap]
prio: 1
lease_until: 2026-10-20T17:01:16Z
extended: 
releases: 1
---
## Goal
Connect accounts (SPEC.md §7.1).

## Acceptance criteria
- [x] Account CRUD API (`/api/v1/mail/accounts`, secrets write-only) with 'test connection'; Angus Mail sessions with TLS/STARTTLS, XOAUTH2 for Google/MS
  - verified (2026-10-20 18:34+0200 by dell26/agent-2): ./gradlew test --tests 'ch.sonensei.cockpit.mail.imap.*' (MailAccountWebTests 18, MailAccountServiceTests 15, MailSessionsTests 25, MailSecretRedactionTests 4 incl. write-only password, TLS/STARTTLS/XOAUTH2 session props) + ./gradlew integrationTest --tests '*Imap*' (GreenMail TLS, STARTTLS, XOAUTH2 login, test-connection, 127.0.0.1 host mismatch -> TLS_FAILED) -> all pass, 0 failures
- [x] Connection pool per account with limits; folder list with SPECIAL-USE role detection and manual override
  - verified (2026-10-20 18:34+0200 by dell26/agent-2): ./gradlew test --tests 'ch.sonensei.cockpit.mail.imap.*' -> ImapConnectionPoolTests 24 (limit, borrow timeout, idle eviction, settings fingerprint), MailFolderSyncTests 25 (SPECIAL-USE roles, manual override, parallel syncs) pass; integrationTest '*Imap*' GreenMail folder sync + pool limit 15/15 pass
- [x] `external` tests against GreenMail (`./gradlew integrationTest --tests '*Imap*'`); unit tests for role detection
  - verified (2026-10-20 18:34+0200 by dell26/agent-2): cd server && ./gradlew integrationTest --tests '*Imap*' --rerun -> ImapGreenMailTests 15 tests, 0 failures/skips; ./gradlew test --tests '*FolderRoleDetectorTests' -> 15 pass
- [x] API documented: `api/openapi.yaml` regenerated (`UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'`) and `OpenApiContractTest` passes without the flag
  - verified (2026-10-20 18:34+0200 by dell26/agent-2): api/openapi.yaml regenerated with UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest' after merging main (commit 2f82ff2f, web client via npm run api:gen); ./gradlew test --tests '*OpenApiContractTest' --rerun without flag -> 1 test, 0 failures

## Notes
- ASSUMPTION (2026-10-20 17:17+0200 by dell26/agent-2): Account API lives in mail/imap (task touches) as /api/v1/mail/accounts plus POST /{id}/test, GET /{id}/folders, POST /{id}/folders/sync, PATCH /{id}/folders/{folderId}; 'test connection' tests the stored account only (no test of unsaved settings), failures are 200 with per-protocol errorCode
- ASSUMPTION (2026-10-20 17:17+0200 by dell26/agent-2): Sending identities (mail_identity) are not managed by this API; left to compose/settings tasks. Account creation does not contact the server; folder sync is an explicit call (header-sync/idle tasks will call MailFolderService.sync)
- ASSUMPTION (2026-10-20 17:17+0200 by dell26/agent-2): Provider defaults: Gmail imap.gmail.com:993 TLS / smtp.gmail.com:465 TLS; Microsoft outlook.office365.com:993 TLS / smtp.office365.com:587 STARTTLS; OAuth username defaults to the connection email
- ASSUMPTION (2026-10-20 17:18+0200 by dell26/agent-2): Role detection: SPECIAL-USE attributes from plain LIST (Angus has no LIST RETURN (SPECIAL-USE)), All counts as Archive when there is no Archive attribute, then English/German well-known names; PATCH folder {automatic:true} only clears the override, the role is recomputed on the next sync
- ASSUMPTION (2026-10-20 17:18+0200 by dell26/agent-2): Pool defaults (cockpit.mail.pool.*): 3 connections per account, 30s borrow timeout, 5m idle timeout; cockpit.mail.ssl-trust (empty by default) exists only for test servers with self-signed certs; host names are always checked
- extended by dell26/agent-2: Implemented: account CRUD API + test connection, Angus TLS/STARTTLS/XOAUTH2, per-account pool, folder sync + SPECIAL-USE role detection/override, GreenMail tests, openapi regenerated; tests.md + review.md written. Commit 9aa9c180 contains fixes for CR-1/CR-3/CR-4 (made without the fixer agent, so there is no fixes.md yet); check passes. Left: CR-2 (GreenMail test with a cert whose host name does not match), CR-5 (stale account snapshot kept in the new pool), run the fixer to fix/record CR-2/CR-5 and write fixes.md that covers all fixes, re-run test-writer + code-reviewer on the final code, run integrationTest --tests '*Imap*' + OpenApiContractTest, verify 4 criteria, quality.json, done.
- release by dell26/agent-2: Implementation complete: all 4 criteria verified, tests.md/review.md/fixes.md/quality.json committed (reviewed_revision 58b0dde7; since then only main merges touching calendar/recurrence, no mail-imap code). Only left: run 'python tasks.py done mail-imap'. Last .taskcheck run failed only on flaky ClockConfigurationTests.'the application clock is the system clock in UTC' (core/persistence, not this task: micro-truncated clock vs nanosecond Instant.now() comparison); re-run, and if it keeps failing, fix/report that under p0-db-conventions follow-up, not here.
