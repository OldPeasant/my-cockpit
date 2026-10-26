---
title: Mail sending identities API and settings
status: in_progress
owner: dell26/agent-1
deps: [web-mail-accounts]
touches: [server/src/main/kotlin/ch/sonensei/cockpit/mail/imap,web/src/app/features/settings/mail]
prio: 2
lease_until: 2026-10-26T06:29:03Z
extended: 
releases: 1
---
## Goal
Additional sending identities per mail account (SPEC.md §7.1 "email address(es)/identities"). The
`mail_identity` table exists (mail-model), but the account API (mail-imap) does not manage it, so
Settings → Mail (web-mail-accounts) only edits the account's primary identity (display name + address).

## Acceptance criteria
- [x] API: list/add/change/remove identities of an account (name, email, reply-to, signature override), ETag/If-Match, Problem Details; `api/openapi.yaml` and web client regenerated; server tests pass
  - verified (2026-10-26 06:58+0100 by dell26/agent-1): cd server && ./gradlew check -> BUILD SUCCESSFUL (4660 tests, ktlint clean); ./gradlew ktlintCheck test --tests MailIdentityWebTests jacocoTestReport at ef0da8140 -> 24/24 pass (list/add/PATCH/DELETE, ETag 412/428, Problem Details 400/404/409, 401); OpenApiContractTest passes so api/openapi.yaml matches server; web client regenerated (npm run api:gen) in 19048d81/dea927fe6
- [x] Settings → Mail lists an account's identities and adds/edits/removes them; Vitest tests cover it; `cd web && npm run check` passes
  - verified (2026-10-26 06:58+0100 by dell26/agent-1): cd web && npm run check at ef0da8140 -> lint ok, build ok, Vitest 175 files / 3665 tests passed incl. identities.spec.ts (25 tests: list, add, edit, remove, default flag, 409/412/404 refresh, disabled while saving)

## Notes
- ASSUMPTION (2026-10-26 04:50+0100 by felix/agent-1): Identities are additional From addresses besides the account's own emailAddress (which stays the primary one, edited via the account); an identity may repeat the account address. Duplicate address within an account -> 409. Exposed isDefault (moves flag) since compose already starts with the default identity; new identities are appended (position = max+1), no reorder endpoint. Change events type MAIL_IDENTITY.
- ASSUMPTION (2026-10-26 04:56+0100 by felix/agent-1): DraftService (not in this task's touches) already starts compose with the default identity, else the FIRST identity, so adding a non-default identity changes the default From away from the account address. Left as is; UI offers 'Start new mail with this identity'. A follow-up may want compose to fall back to the account address when no identity is default.
- extended by felix/agent-1: Done: server identity API + openapi + regenerated client (dddf7d19), web Settings->Mail identities UI (b9193705), extra tests + code review report (9e503433). Left: (1) check.sh timed out >10 min in 'server: ktlint + tests' after adding MailIdentityWebTests cases - find whether a new test hangs; (2) run fixer for review CR-1 (PATCH accepts blank email -> 400), CR-2 (reword hint: identities change default From; follow-up task mail-compose-identity-fallback exists), CR-3 (refresh after 412/404), CR-4 (disable Edit/Add while saving); (3) test-writer -> tests.md with coverage, code-reviewer again (review.md reviewed pre-rebase hashes 20fb5d9a/2371f113), quality.json; (4) tasks.py verify #1 #2, done.
- release by felix/agent-1: Done & committed (rebased on main 97c0d47e, openapi+web client regenerated after conflict): server identity API + tests (19048d81), web Settings->Mail identities UI (0aa9fa9b), extra tests + review.md (806d72fb), fixer for CR-1..CR-4 with reviews/mail-identities/fixes.md (a600d5ba). Left: (1) sh scripts/check.sh hangs in 'server: ktlint + tests': at 06:27 no new test-result XML was written for 10+ min (all XMLs date from a complete run at 06:19, which suggests a stale Gradle daemon/lock left by an earlier run, not a hanging test). Kill old Gradle daemons (cd server && ./gradlew --stop), rerun the check and confirm; if it still hangs, jstack the test JVM. (2) test-writer -> reviews/mail-identities/tests.md with coverage; code-reviewer again on the final code (review.md predates the fixes and the rebase); (3) quality.json; (4) tasks.py verify mail-identities 1 and 2; done.
