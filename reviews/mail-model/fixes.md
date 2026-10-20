# Fix Report

Date: 2026-10-20

Task: mail-model — Mail data model

Scope: current task (findings TW-1 and TW-2 from `reviews/mail-model/tests.md`). The fix is in commit `7621d6e`, made by an earlier fixer session that stopped before writing this report. This session reviewed that commit against both findings and found nothing more to change.

## Fixed findings

### TW-1 — Deleting a folder that still holds located messages fails with a check violation

Files changed (commit `7621d6e`):
- `server/src/main/resources/db/migration/mail/V202610201402__mail_model.sql`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailMessage.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailFolder.kt` (KDoc only)
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailMessageLocator.kt`
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailRepositories.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/model/MailModelConstraintTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/model/MailItemResolverTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/model/MailModelPersistenceTests.kt`

Problem:
`mail_message.folder_id ... on delete set null` conflicted with the check `uid is null or (uid > 0 and folder_id is not null)`. Deleting a folder that still had located messages failed, and the whole deletion was rolled back. That contradicts the migration's stated intent: messages survive a folder deletion as link targets.

Fix:
- The check is now `uid is null or uid > 0`, so the SET NULL cascade can run. The migration is new in this task and has never been on main, so editing it is allowed. The migration comment now says that a row is located only when both `folder_id` and `uid` are set, and that a `uid` left over after a folder deletion is stale.
- `MailMessage.isLocated` is now `folderId != null && uid != null`. A stale UID therefore means "not located", and `MailMessageLocator` re-resolves the row by account and Message-ID.
- The located-copy and thread queries now also require `folder_id is not null`. They are renamed to `findByAccountIdAndMessageIdHeaderAndFolderIdIsNotNullAndUidIsNotNullOrderByIdAsc` and `findByAccountIdAndThreadKeyAndFolderIdIsNotNullAndUidIsNotNullOrderByReceivedAtAscIdAsc`. Only this package calls them; a grep of `server/src/main` found no other callers. A stale row is never returned as a copy or as a thread member.
- The application still rejects a UID without a folder: `MailMessage.locate` / `requireLocation` are unchanged.
- The existing partial indexes (`where uid is not null`) still serve the new queries, because their predicates imply the index predicate. Stale UIDs cannot collide in `mail_message_folder_uid`, because Postgres treats NULL `folder_id` values as distinct.
- Tests: the TW-1 test now deletes a folder with a located message and checks that the row is kept (`folderId` null, not located) and that it is excluded from the copy and thread queries. A new test covers stale UIDs from two deleted folders: they do not collide, and the row can be located again. A new resolver test checks that a located row whose folder was deleted re-resolves to a copy in another folder. The persistence round-trip test no longer clears `uid` with raw SQL before deleting the folder. The DB constraint test still checks that a non-positive UID is rejected, and now also with `folder_id` null.

Review in this session:
I checked the change against the finding's expected behavior and against the link-survival assumption in the task notes. Both are satisfied. No further change was needed.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.mail.model.*'` — Pass. Includes `MailModelConstraintTests > deleting a folder keeps its messages as link targets()`.

### TW-2 — `MailAddress.parseList` KDoc does not match its behavior; malformed entries come through as pseudo-addresses

Files changed (commit `7621d6e`):
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/model/MailAddress.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/model/MailAddressTests.kt`

Problem:
The KDoc said that an unparsable list gives no addresses. In fact, lenient `InternetAddress.parseHeader(header, false)` never throws, so the `AddressException` branch could not be reached. Malformed entries such as `<`, `@@@` and `Ann <ann@example.com` came through as pseudo-addresses.

Fix:
- `parseList` now drops entries whose address does not have the `local@domain` shape: there must be an `@` with text on both sides (`hasMailboxShape`). The `AddressException` branch, which could not be reached, is removed.
- The KDoc now describes the actual behavior. Parsing never fails, valid neighbors are kept, and groups are flattened. Entries without the `local@domain` shape are dropped. Anything else is kept as the parser read it, with no further RFC 5322 validation (the documented example is `Ann <ann@example.com` with no closing `>`).
- Tests: `<<>>`, `<`, `>`, `@@@`, `@example.com`, `ann@`, `(unclosed comment`, `"bad` and `a b c` each give no addresses. A valid neighbor next to a malformed entry is kept. The remaining lenient case (`Ann <ann@example.com`) is pinned. The `>` and `(unclosed comment` cases also show that lenient parsing does not throw on these inputs.

Review in this session:
The KDoc and the behavior now agree, and the behavior is pinned by tests. No requirement defines how malformed input is handled, and keeping parsing lenient matches the original intent ("like most mail clients"). No further change was needed.

Validation:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.mail.model.*'` — Pass.

## Not fixed

None.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.mail.model.*'` (2026-10-20, about 16:32 +02:00) — BUILD SUCCESSFUL. JUnit result files for `ch.sonensei.cockpit.mail.model.*`: 68 tests, 0 failures, 0 errors, 0 skipped. ktlint passed on the main and test source sets.

Full test suite:
- Not run by this fixer. The parent worker runs `.taskcheck` (`sh scripts/check.sh`) as part of the quality gate.

Lint/type-check/build:
- `./gradlew ktlintCheck` (server) — Pass (in the run above). Compilation for the run above succeeded.

`.taskcheck`:
- Not run (the parent worker's step, as instructed).

Remaining unresolved findings:
0

## Final status

All validated findings fixed and validation passed. The fixes are in commit `7621d6e`; this session made no further code changes.
