# Code Review

Date: 2026-11-09

Task: mail-identity-drafts - Choose sending identity and resume drafts (web + Android)

Scope: `git diff 7e0d05a87 4cbbdf631 -- web android server` (whole task on the final revision). Delta since the previous review (b93d060ac): `git diff b93d060ac 4cbbdf631 -- android`, test-only (4 ViewModel tests in MailComposeIdentityDraftTest.kt, 1 screen test in MailComposeIdentityScreenTest.kt). Reviewed revision: 4cbbdf63157b5b7d3bd7345916aefd098a439057

Requirements:
- tasks/mail-identity-drafts.md (acceptance criteria and all recorded assumptions, including the one on plain-text resume of Android drafts)
- SPEC.md §7.3 (choose sending identity, drafts)
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- api/openapi.yaml (CreateDraftRequest, DraftContentRequest, DraftResponse, MailIdentityResponse); server DraftService and ComposeText
- reviews/mail-identity-drafts/tests.md, fixes.md, previous review of b93d060ac
- Parent agent instructions: no Gradle runs; no angle-bracket placeholders

Git range:
- Branch: main
- HEAD: 4cbbdf63157b5b7d3bd7345916aefd098a439057 (reviewed). While this review ran, the task commits were rebased onto newer main (now 7e7ef87d4); `git diff 4cbbdf631 7e7ef87d4 -- web android server api` is empty, only `tasks/` files differ, so this review applies unchanged.
- Base/start: 7e0d05a87
- Commits reviewed: 6 code commits (a10b695db web picker and drafts open, bb142855a Android ComposeMode.Draft, 2dabe1bdf Android identity picker, 0b2384c18 tests, b93d060ac fixes, 4cbbdf631 more tests); the other commits in the range only touch `tasks/` or `reviews/`
- Uncommitted changes: none (besides this report)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

Checks run: Gradle was not run, as instructed. Android and full-check results come from tests.md for this revision (Android mail tests 906 passed; web suite 4751 passed; `sh scripts/check.sh` exit 0). The web spec run from the previous review (3 files, 211 tests passed) still applies: no web code changed since.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Android From label empty for an opened draft without default or chosen identity until accounts load**
    - File: `android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeScreen.kt:519`
    - Category: Correctness
    - Problem: The label is `identity?.label ?: account?.sender.orEmpty()`. For an opened draft (or reply/forward) whose account has identities but none is default, and no identity is chosen, `identity` is null and `account` is null until `loadAccounts()` returns, so the From button shows no text. If `repository.accounts()` fails (offline), `ComposeSession` ignores the failure and the label stays empty for the whole session. Confirmed by the test-writer's observation (tests.md "Bugs found") and by reading the code; the new screen test exercises exactly this state but asserts only the dropdown.
    - Why it matters: Cosmetic only. The picker still opens and lists the identities, and the saved draft and the sender used by the server (account address, `identityId == null`) are correct. The user just cannot see which address will be used in that narrow case.
    - Suggested fix: Optional. Show a fallback while the account is unknown, e.g. a "loading" placeholder or the account address from the draft if it becomes available, or leave as is and record it as accepted.

## Test delta since b93d060ac (reviewed for correctness)

- "a reply also loads the accounts for its From row, its account stays locked": correct; covers `ComposeSession.prepared` calling `loadAccounts()` for reply mode with `accountLocked` kept.
- "accounts that cannot be read for an opened draft leave its identities and keep it editable": correct; `accounts()` returns Offline, asserts no problem, EDITING phase, SAVED draft state and the editable body. Good regression guard for the ignored-failure path.
- "choosing the account address while it is already used changes nothing": correct; with identities `[workSupport]` (no default) and `identityId == null`, `selectAccountAddress()` returns early (MailComposeViewModel.kt:299), and the test verifies no `update` call.
- "an opened draft can switch from the account address to an identity and back before saving": correct; captures both saved contents, asserts `identityId` workSupport then null, and the unchanged body HTML (one paragraph "Draft so far"). Re-stubbing `update` with the same capture list is valid MockK usage.
- Screen test "without a default identity and before the accounts are loaded...": correct; asserts the button is enabled, no ACCOUNT_ADDRESS entry without a known account (matches `addressEntry` needing `account`), and choosing the identity calls back. It does not assert the label text (see CR-1).
- Tests assert behavior through public ViewModel/UI state and repository calls; no mocks hide the behavior under test; no existing tests were weakened or removed.

## Previous findings (earlier revisions), still resolved at 4cbbdf631

- From row hidden for reply/forward/opened draft: fixed (MailComposeScreen.kt:499 returns early only with no account, no account list and no identities; `ComposeSession.prepared` loads accounts).
- Resumed draft body read-only as quoted text: fixed (draft `text` goes into the editable body; untouched opened drafts are neither re-saved nor deleted on leave; formatting loss on re-save is a recorded assumption).
- Missing tests: resolved (Android ViewModel, repository, screen, models, list; web picker, service and list specs).
- Account address and single non-default identity not choosable on Android: fixed (address entry when no default identity, same rule as the web `senders`).
- Fully qualified UUID: fixed.

## Also checked and found correct

- Web: `senders` lists each account's identities plus the account address only without a default identity; `senderKeyValue` falls back to default identity, then address; same-account choice is an edit; cross-account moves go through `changeSender`/`followSender` and restore on failure; identities that fail to load leave the account address.
- Web list: Drafts-folder rows (by folder role) link to `/mail/compose?draft={id}`; compose loads with `get` and saves via `update` on the same id.
- Android: `selectAccount` resets and reloads identities; stale identity answers are dropped; `identityId` mapped in create, update and read DTOs per `api/openapi.yaml`; invalid identity ids in responses are ignored; `ComposeMode.Draft` survives `rememberSaveable`; `MailListUiState.isDraft` checks folder role.
- No server or OpenAPI change needed; generated web client already contains `listMailIdentities`.
- Strings: Android "Draft"/"Entwurf" in en and de; no new web i18n units.
- Security: no new logging, no secrets, user text escaped before becoming HTML.

## Review conclusion

No material issues found. (CR-1 is a low-impact cosmetic issue; the parent may fix it or record it as accepted.)
