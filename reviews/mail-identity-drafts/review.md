# Code Review

Date: 2026-11-09

Task: mail-identity-drafts - Choose sending identity and resume drafts (web + Android)

Scope: `git diff 7e0d05a87 HEAD -- web android server` (whole task on the final revision, including fix commit b93d060ac for CR-1, CR-2, CR-4/TW-1 and CR-5 of the previous review). Reviewed revision: b93d060acf9c46fc2bcf041b6ab50c46f255f8a4

Requirements:
- tasks/mail-identity-drafts.md (acceptance criteria and all recorded assumptions, including the 02:45 one on plain-text resume of Android drafts)
- SPEC.md §7.3 (choose sending identity, drafts)
- AGENTS.md project notes, docs/ARCHITECTURE.md conventions
- api/openapi.yaml (CreateDraftRequest, DraftContentRequest, DraftResponse, MailIdentityResponse); server DraftService and ComposeText (draft `text` is derived from the HTML: paragraphs become blank lines, list items `- `)
- reviews/mail-identity-drafts/review.md (previous revision 0c767df3b), tests.md, fixes.md
- Parent agent instructions: no Gradle runs; no angle-bracket placeholders

Git range:
- Branch: main
- HEAD: b93d060acf9c46fc2bcf041b6ab50c46f255f8a4
- Base/start: 7e0d05a87
- Commits reviewed: 5 code commits (a10b695db web picker and drafts open, bb142855a Android ComposeMode.Draft, 2dabe1bdf Android identity picker, 0b2384c18 tests, b93d060ac fixes); the other commits in the range only touch `tasks/` or `reviews/`
- Uncommitted changes: none

Summary: 0 P0, 0 P1, 0 P2, 0 P3

Checks run: `npm --prefix web run test -- --watch=false --include src/app/features/mail/compose/mail-compose-page.spec.ts --include src/app/features/mail/compose/mail-compose.service.spec.ts --include src/app/features/mail/list/mail-list-page.spec.ts` -> 3 files, 211 tests passed. Gradle was not run, as instructed; the Android results come from fixes.md (`./gradlew ktlintFormat ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` -> 901 tests, 0 failures; `lintDebug` passed). Note: tests.md still describes the pre-fix revision (892 tests, TW-1 failing); the parent should have the test-writer re-run on this revision as planned.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Previous findings, re-checked on b93d060ac

- CR-1 (From row hidden for reply/forward/opened draft): fixed. `From` (MailComposeScreen.kt:497) returns early only with no account, no account list and no identities; the label falls back to the identity. `ComposeSession.prepared` now also calls `loadAccounts()` (ComposeSession.kt:131-142), which skips when accounts are known, ignores failures and results after the session ended. Covered by the screen test "an opened draft or reply shows its identities before the accounts are loaded" and the ViewModel test for accounts loaded with the account still locked.
- CR-2 (resumed draft body read-only as quoted text): fixed. For `ComposeMode.Draft` the trimmed `draft.text` goes into the editable `body`, `quotedText` and `quotedHtml` stay empty (ComposeSession.kt:111-124), so `content()` saves only the edited body. `markupToHtml` escapes the text, and the server's plain-text rendering (blank lines between paragraphs, `- ` list items) maps back to the same paragraph/list structure. Loss of formatting, links and images on re-save is recorded as an assumption. An untouched opened draft is never re-saved (`dirty` is false), so it keeps its original HTML. Leaving while the draft still loads no longer deletes it (ComposeSession.kt:107), and `leave()` keeps untouched opened drafts (line 488). Tests cover the editable body with the exact saved HTML, the unchanged reply path and leaving during load.
- CR-3 (no tests): resolved by the test-writer's tests (Android ViewModel, repository, screen, models, list; web picker, service and list specs).
- CR-4 / TW-1 (account address and a single non-default identity not choosable on Android): fixed. The dropdown lists the account address when the account is known, has identities and none is default (same rule as the web `senders`). `selectAccountAddress()` clears the identity as an edit only in that case. `canPick` is now true when an entry other than the shown one can be chosen. Covered by screen and ViewModel tests, including the saved `identityId == null`.
- CR-5 (fully qualified UUID): fixed (import added).

## Also checked and found correct

- Web: `senders` lists each account's identities (skipping ones without id or email), plus the account address only without a default identity. `senderKeyValue` falls back to the default identity, then the address, so an unlisted or unloaded identity still selects an existing entry. Choosing within the same account is an edit. Another account goes through `changeSender`/`followSender`, which sends the chosen identity with the move and restores account and identity when the move fails. Other accounts' entries are disabled once the draft cannot move. Identities that fail to load leave the account address (`readIdentities` catches per account).
- Web list: Drafts-folder rows link to `/mail/compose?draft={id}` (click and keyboard `open`), determined by folder role, not name. Compose loads the draft with `get` and saves through `update` on the same id.
- Android: `selectAccount` resets identities and identity and reloads them; `loadIdentities` drops answers for an account that is no longer selected. `identityId` is mapped in create, update and read DTOs as in `api/openapi.yaml`; an invalid identity id in a response is ignored instead of crashing. `ComposeMode.Draft` encodes/parses for `rememberSaveable`. `MailListUiState.isDraft` checks listed and found mails by folder role.
- No server change was needed (the API already accepts `identityId` and lists identities); `api/openapi.yaml` and the generated web client are unchanged and already contain `listMailIdentities`.
- Strings: the Android title "Draft"/"Entwurf" exists in en and de; no new web i18n units (the picker shows names and addresses only).
- Security: no new logging, no secrets, user text is escaped before it becomes HTML.

## Review conclusion

No material issues found.
