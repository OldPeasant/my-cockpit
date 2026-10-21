# Code Review

Date: 2026-10-21

Task: mail-actions-api - Mail actions (final re-review after the test-writer commits ea2a06e0 and daca89e3)

Scope: `git diff bff56756..HEAD` (task base bff56756ab7c7f00a421f70daf0de7f85dfcb155 to HEAD daca89e3). Production files reviewed in full: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionController.kt`, `MailActionService.kt`, the `api/openapi.yaml` hunks and the generated web client files. Tests: the new tests in `MailActionServiceTests.kt` (commits ea2a06e0, daca89e3) were checked one by one against the production code. The rest of the file, `MailActionGreenMailTests.kt`, `MailActionWebTests.kt` and `MailActionControllerTests.kt` were only spot-checked; the earlier reviews covered them. `reviews/mail-actions-api/tests.md` was checked against the repository. Only committed changes are in scope. The working tree is clean.

Requirements:
- `tasks/mail-actions-api.md`: acceptance criteria 1-3 and the five recorded assumptions
- SPEC.md §7.3 (actions), §4.2 (API conventions)
- AGENTS.md project notes (logging, generated files, module boundaries), docs/QUALITY.md artifact contract
- Earlier `reviews/mail-actions-api/review.md` (re-review: 0 findings) and `reviews/mail-actions-api/fixes.md` (CR-1..CR-4)

Git range:
- Branch: main
- HEAD: daca89e3bd2e10f02f8c901aa3033693a059c167
- Base/start: bff56756ab7c7f00a421f70daf0de7f85dfcb155
- Commits reviewed: 7 (7a53377f, f68bef8e, 31f7a5d1, 5b2c1780, ce836284, ea2a06e0, daca89e3)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | The OpenAPI contract allows an empty `ids` list (`minItems: 0`) that the server rejects with 400**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailActionController.kt:69-70` (generated as `api/openapi.yaml:6550`)
    - Category: Integration
    - Problem: `ids` has `@field:NotEmpty` and `@field:Size(max = MAX_IDS)`. springdoc takes the `min` of `@Size` (0 by default), so the schema says `minItems: 0`. Bean Validation still rejects `[]` with 400. The 400 description says "No or too many ids", so the text is right, but the machine-readable schema says something else.
    - Why it matters: clients and generated validators built from the contract treat an empty list as a valid request. Small impact, because the web client does not validate against the schema.
    - Suggested fix: use `@field:Size(min = 1, max = MAX_IDS)`. You can keep or drop `@NotEmpty`. Then regenerate `api/openapi.yaml` and the web client. The expected result is `minItems: 1`.

## Checks of the new tests (commits ea2a06e0, daca89e3)

I traced each of the 11 new tests through `MailActionService` with the test fixture (`init` stubs, `cached`, `onServer`):

- `unread and unflag clear the flags ...`: these run `setFlags(..., false)` for SEEN and FLAGGED, and the cache flags follow. The assertions match `applyToRows` (`MailActionService.kt:358-362`).
- `a server error while opening the source folder ...`: a plain `MessagingException` on `open` reaches `MailConnectionException.of` (`:243-244`) and is caught in `applyInFolder` (`:181-186`). The test checks that nothing is published and the cache is unchanged, which is correct.
- `a server failure in one source folder fails only that folder's ids, in request order`: the folder groups run independently (`:140-142`), results come back in request order (`:143`), and only the inbox event is published. The assertions are correct.
- `messages gone from the server fail with NOT_FOUND while the others ...`: with `getMessagesByUID` returning `[null, msg]`, `missing = {7}` and only the found message gets `setFlags`. The row of the missing message is left alone, because of the `filter { it.uid !in done.missing }` at `:193`. The assertions are correct.
- `when no requested message is on the server any more ...`: DELETE from INBOX resolves to a move to Trash. `selected.isEmpty()` returns early (`:252`), and `updateCache` returns on an empty list before it publishes (`:332`). The test checks that no command is sent, no event is published and the row is unchanged, which is correct.
- `a source folder deleted on the server ...`: covers the `FolderNotFoundException` on `open` that maps to `Remote.Gone` (`:239-240`).
- `a message whose account row is gone ...`: covers the `account == null` branch (`:169-170`), and the test checks that the pool is never called.
- `a command answered with BAD ...`: `MessagingException.getCause()` returns the chained `BadCommandException`, so the cause-chain check at `:272` matches and the result is REJECTED.
- `a row a sync relocated between lookup and cache update is not overwritten`: the re-read row (now in Projects) is excluded by the `folderId == source.id` filter (`:355`). The test checks that it keeps uid 99 and is not deleted, which is correct.
- `a COPYUID is trusted when the target folder has no cached UIDVALIDITY yet`: covers the `current.uidValidity == null` branch of `relocate` (`:396`).
- `a failing close of the source folder ...`: covers `runCatching` around `close` (`:278`).

None of these tests weakens or replaces an existing assertion, and the commits change no production code. The mocks script only the IMAP and repository boundaries; the service logic under test runs for real.

Evidence I checked in the repository:
- `server/build/test-results/test/TEST-ch.sonensei.cockpit.mail.api.MailActionServiceTests.xml` lists 29 tests with 0 failures and 0 errors, and contains all 11 new test names. The file has 29 `@Test` methods.
- The sha256 hashes in `tests.md` for `MailActionServiceTests.kt`, `MailActionService.kt` and `MailActionController.kt` match the committed files at HEAD. The coverage and test run therefore used exactly the reviewed code.

## Review conclusion

No material issues found.

CR-1 is a minor contract inaccuracy and does not block the task. The parent worker decides whether to fix it now. If the fix is made, it changes production code, so the review and test evidence must be re-run.

Acceptance criteria:
1. The endpoint, all eight actions (DELETE goes to Trash, or deletes for good when the message is already in Trash), consistent updates on IMAP and in the cache, and per-id partial failures are all implemented. The implementation matches assumptions #1-#4 (`MailActionService.kt:128-224`). Criterion 1 still has no `tasks.py verify` entry in the task file. The parent must record it before `done`.
2. `MailActionGreenMailTests` covers every action against GreenMail. The task file has the recorded evidence: 16 tests, 0 failures. I did not run them again.
3. The `api/openapi.yaml` hunks match the controller annotations, apart from CR-1. The generated web models match the schema (`results`, `id`, `ok` required; `errorCode`/`detail` are `string | null`). `OpenApiContractTest` is recorded as passing without the flag.

Logging follows the project rules: server responses and exceptions are logged at DEBUG only, and INFO lines carry fixed text and error codes.
