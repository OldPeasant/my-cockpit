# Code Review

Date: 2026-11-09

Task: mail-identity-drafts - Choose sending identity and resume drafts (web + Android)

Scope: `git diff 01bc0c348 0c767df3b`, excluding `tasks/` (web mail compose and list, Android mail compose and list, Android compose strings)

Requirements:
- tasks/mail-identity-drafts.md (acceptance criteria and the recorded assumptions)
- SPEC.md §7.3 (compose: choose sending identity, drafts)
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- api/openapi.yaml (CreateDraftRequest, DraftContentRequest, DraftResponse, MailIdentityResponse) and server DraftService (identity resolution, draft id = id of the message in the Drafts folder)
- Parent agent instructions: no Gradle runs; Android test mock fixes by the concurrent test-writer are not a finding

Git range:
- Branch: main
- HEAD: 0c767df3bc75deb5d6e0cecdaf9f1866bb4ec978
- Base/start: 01bc0c3481d44b5deb2289967beb3da453facf7d
- Commits reviewed: 2 (57cc71881 merge of the earlier wip work, 0c767df3b Android identity picker)

Summary: 0 P0, 3 P1, 1 P2, 1 P3

Checks run: `npm --prefix web run test:ci -- --include src/app/features/mail/compose --include src/app/features/mail/list` -> 14 files, 573 tests passed. The working tree also had uncommitted test changes from the test-writer; that number is the same as before those changes. Gradle was not run, as instructed.

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | Android From row (and identity picker) is hidden for opened drafts, replies and forwards**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeScreen.kt:492`
    - Category: Correctness
    - Problem: `From` returns early with `if (account == null && state.accounts.isEmpty()) return`. `state.accounts` is only filled in `ComposeSession.load` for `ComposeMode.New` (ComposeSession.kt:67-87). For `ComposeMode.Draft`, `Reply`, `ReplyAll` and `Forward`, `prepared` sets `accountId` and starts `loadIdentities`, but `accounts` stays empty. So `account` is null, the early return fires, and the From row with the new identity dropdown never shows. The identities are loaded but cannot be seen or chosen.
    - Why it matters: Criterion 1 requires the From picker to list identities in compose. On Android it works only for a new mail. A draft resumed from the Drafts folder (criterion 2, new in this task) or a reply cannot change its identity, and the user cannot even see which identity it will be sent from. The web client does allow this (other accounts disabled, own identities selectable).
    - Suggested fix: Render the row when identities exist even without an account list, e.g. return only when `account == null && state.accounts.isEmpty() && state.identities.isEmpty()`, with a label that falls back to the identity. Add a screen or ViewModel test for picking an identity in `ComposeMode.Draft` and `ComposeMode.Reply`.

- [ ] **CR-2 | Android resumed draft: the body written earlier cannot be edited; it is shown as hidden quoted text**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt:105`
    - Category: Correctness
    - Problem: `ComposeMode.Draft` goes through the same `prepared` path as a reply. The whole stored body goes into `quotedHtml` / `quotedText` and the editable body starts empty. The screen shows `quotedText` only through the "Show quoted text" toggle, as read-only text (`Quoted`, MailComposeScreen.kt:672-689). `content()` saves `markupToHtml(body) + quotedHtml`. So new text can only be added above the old body, and the text the user wrote before cannot be changed. The web client loads `draft.html` into the editor (mail-compose-page.ts:419-420), where it can be edited.
    - Why it matters: The task goal is to "continue" a draft, and criterion 2 says opening a Drafts-folder message "opens it in compose". On Android, the user's own unfinished text appears as a collapsed "quoted" original that cannot be edited. Every later resume adds more text above it. That does not meet the criterion's intent, and it differs from the web client.
    - Suggested fix: For `ComposeMode.Draft`, put the stored body into the editable body (convert `draft.text` or the HTML into the editor markup the compose already uses) and keep `quotedHtml` empty. If a lossless conversion is not possible, record the limitation as an assumption. Cover it with a test that resumes a draft, edits the existing text and checks the saved `html`.

- [ ] **CR-3 | No unit tests for the new behavior at the reviewed revision**
    - File: `web/src/app/features/mail/compose/mail-compose-page.spec.ts:72` (fake only), Android tests unchanged
    - Category: Tests
    - Problem: The diff adds no test cases. The web spec only adds `identities()` to the fake service. Nothing covers: the `senders` list (own address only when no default identity), `chooseSender` within the same account and across accounts, `identityId` in the create/update payloads and its restore on a failed move, `readIdentities` failure, or the list page opening Drafts-folder rows/keys in `/mail/compose?draft=`. On Android nothing covers `selectIdentity`, the identity reset and stale-load guard in `selectAccount`/`loadIdentities`, `identityId` in the DTOs, `ComposeMode.Draft` encode/parse, start via `api.get`, keeping an untouched opened draft on leave, or `MailListUiState.isDraft`.
    - Why it matters: Criterion 4 requires unit tests for the new behavior on each changed part. CR-1 shows that a test of the Draft/Reply picker would have caught a real defect.
    - Suggested fix: Add the tests listed above. A test-writer was changing test files when this review ran, so check its final result against this list.

## P2 Medium

- [ ] **CR-4 | Android picker cannot choose the account address, or a single identity that is not the default**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeScreen.kt:495`
    - Category: Correctness
    - Problem: The dropdown lists only `state.identities`, plus other accounts. Unlike the web `senders` (mail-compose-page.ts:125-149), it has no entry for the account address (`identityId = null`) when the account has no default identity. So after picking an identity, the user cannot go back to the account address. `canPick` also requires `identities.size > 1` or other accounts. An account with exactly one identity that is not the default therefore cannot select it: the button is disabled and the From label shows the account address.
    - Why it matters: The two clients differ in what can be chosen, against the parity goal and the recorded assumption that the address is offered when there is no default identity. Some sending options cannot be reached on Android.
    - Suggested fix: When no identity is default, add an account-address entry that sets `identityId = null` (selectIdentity taking a nullable id, or a separate callback). Base `canPick` on the number of choosable entries.

## P3 Low

- [ ] **CR-5 | Fully qualified `java.util.UUID` in ComposeSession**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/ComposeSession.kt:126`
    - Category: Quality
    - Problem: `fun loadIdentities(accountId: java.util.UUID)` spells out the full package name, while the rest of the compose package imports `java.util.UUID`.
    - Why it matters: A small style inconsistency in a file that other code in the package will copy.
    - Suggested fix: Import `java.util.UUID` and use `UUID`.

Checked and found correct: the web identity is kept when the account changes and restored when a move fails (`followSender` with `previousIdentity`). Android resets identities and the identity when `selectAccount` runs, and `loadIdentities` ignores results for an account that is no longer selected. An opened draft keeps its id, so saving updates the same draft (web `?draft=` with `get`, then `update`; Android `ComposeMode.Draft` with `api.get` and `stored`). The server resolves the draft id as the message in a Drafts folder. `identityId` matches `api/openapi.yaml` on both clients. No new web strings were added; the Android title string exists in en and de. No secrets or logging changes.

## Review conclusion

Material issues found; fix before completing the task.
