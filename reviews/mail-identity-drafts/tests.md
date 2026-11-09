# Test Report

Date: 2026-11-09

Task: mail-identity-drafts: Choose sending identity and resume drafts (web + Android)

Scope: base 01bc0c3481d44b5deb2289967beb3da453facf7d .. HEAD aba2e1ded5184e4a84b24899f178129919dd92bf (branch main, local, rebased). Task-owned commits: 214ef4783 (web picker + drafts open in compose), e6b4083ce (Android ComposeMode.Draft), 4a48acd50 (Android identity picker), aba2e1ded (review report only). The other commits in the range only touch `tasks/` (claim/assume/heartbeat of other tasks). Production paths came from `git diff --name-only 01bc0c348 HEAD -- server/src/main web/src android/app/src/main`, checked against the parent's description and the task's `touches`. The working tree held uncommitted test changes only (listed below), no production changes.

Requirements:
- tasks/mail-identity-drafts.md acceptance criteria and recorded assumptions (From picker lists the account address only when the account has no default identity; an untouched opened draft is kept on leave; identities that fail to load leave only account addresses)
- SPEC.md §7.3 ("choose sending identity")
- docs/ARCHITECTURE.md "Coverage" and code conventions; AGENTS.md
- Parent instructions (fix strict-mock setups, then add Android and web tests)

## Test files changed

Existing Android tests, updated for the changed production API (no assertion weakened):
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeViewModelTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeReviewFixesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeSecondReviewFixesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeRecipientsFilesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeRecipientsFilesEdgeCasesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeRecipientsFilesReviewFixesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeOverwriteLeaveEdgeCasesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeRouteTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeSaveSendStatesTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeStatesScreenTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeViewModelConcurrencyTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeInAppTest.kt` (also one new test)

Two changes were made in these files. The strict `mockk<MailComposeRepository>()` setups now stub `repository.identities(any())` to return `MailResult.Ok(emptyList())`. The positional `Draft(...)` constructions now pass `null` for the new third parameter `identityId`. Without that second change the test sources do not compile, because `identityId` was added between `accountId` and `etag`.

New test files:
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeIdentityDraftTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeIdentityRepositoryTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeIdentityScreenTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/ComposeModelsIdentityDraftTest.kt`
- `android/app/src/test/java/ch/sonensei/cockpit/mail/list/MailListDraftTest.kt`

Web specs extended:
- `web/src/app/features/mail/compose/mail-compose-page.spec.ts` (new `describe('sending identity')`)
- `web/src/app/features/mail/compose/mail-compose.service.spec.ts`
- `web/src/app/features/mail/list/mail-list-page.spec.ts` (new `describe('in a Drafts folder')`)

## Tests added or updated

Count: 52 new tests (Android 35, web 17) and 12 existing Android test files updated (setup stub and constructor only).

## Requirement coverage

Android command A: `cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'` (892 tests, 1 failed: TW-1).
Web command W: `npm --prefix web run test -- --watch=false --include src/app/features/mail/compose/mail-compose-page.spec.ts --include src/app/features/mail/compose/mail-compose.service.spec.ts --include src/app/features/mail/list/mail-list-page.spec.ts` (211 passed).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 web: the From picker lists identities per account (account address only without a default identity) | `sending identity`: lists the identities of each account...; leaves out identities without an address; shows the From value of the chosen identity in the picker; offers only the account addresses when identities cannot be read, and still saves | W | Pass |
| AC1 web: sends/saves with the chosen identity (same account = edit, other account = move with that identity, failure restores it) | creates a new mail with the identity picked before the first save; shows the identity of an opened draft and saves another identity of its account as an edit; shows the default identity when the draft's identity is not listed any more; choosing the entry already shown changes nothing; moves a stored draft to an identity of another account...; puts account and identity back when the move fails; keeps identities of another account disabled once a reply cannot move | W | Pass |
| AC1 web: identities endpoint client | `MailComposeService` lists the sending identities of an account (GET `/api/v1/mail/accounts/{id}/identities`, silent errors) | W | Pass |
| AC1 Android: identities loaded per account, chosen identity saved with the draft | `MailComposeIdentityDraftTest`: a new mail loads the identities of its account; a chosen identity is an edit and is created with the new draft; an identity not listed... changes nothing; another account forgets the identity and loads that account's identities; identities of an account left meanwhile are not shown for the new one (reverse-order answer); identities that cannot be read...; without any account no identities are asked for; a reply loads the identities... and keeps the identity the server chose | A | Pass |
| AC1 Android: API mapping (identities JSON, identityId in create/update/read) | `MailComposeIdentityRepositoryTest` (5 tests) | A | Pass |
| AC1 Android: From picker UI lists identities, then other accounts | `MailComposeIdentityScreenTest`: From shows the default identity and lists the identities, then the other accounts; From shows the chosen identity; another account is chosen from the same list; once the account is locked only its identities are offered; a locked account without identities cannot be changed...; without identities the accounts are listed as before; the From picker cannot be opened while the mail is not editable | A | Pass |
| AC1 Android: an account's only (non-default) identity can be chosen | `MailComposeIdentityScreenTest`: a single identity that is not the default can still be chosen | A | Fail (TW-1) |
| AC1 server/API: server change only if needed | No server change in the task diff. The server already accepts `identityId` (generated web client has `listMailIdentities` and `identityId`). | n/a | Not testable here (no server change to test) |
| AC2 web: opening Drafts-folder mail opens it in compose | `in a Drafts folder`: links a draft to compose with its id...; opens a clicked draft in compose; opens the cursor draft in compose with o and Enter; opens a mail of another folder... in the read view; treats a draft listed in the unified inbox like one in its folder | W | Pass |
| AC2 web: saving updates that draft | Existing `opens a stored draft without creating another`, plus the new "...saves another identity of its account as an edit" (update to the same draft id, no create) | W | Pass |
| AC2 Android: list opens Drafts-folder mail in compose | `MailListDraftTest` (4 tests on `MailListUiState.isDraft`: listed, found in search, by role not name, unknown folder); `MailComposeInAppTest`: a mail of a Drafts folder opens in compose as that draft, not in the read view, and back keeps it | A | Pass |
| AC2 Android: compose loads the draft, saving updates it, untouched leave keeps it | `MailComposeIdentityDraftTest`: an opened draft shows its content and identity, saving updates that draft...; leaving an opened draft untouched keeps it; leaving an edited opened draft saves it into the same draft; a draft that cannot be opened offers retry...; `MailComposeIdentityRepositoryTest`: continuing a draft reads it from the server; `ComposeModelsIdentityDraftTest`: encode/parse of `ComposeMode.Draft` | A | Pass |
| AC3 new strings en + de | Android: `MailComposeIdentityScreenTest` "a continued draft is titled Draft" / "...Entwurf in German"; existing `MailComposeRecipientsFilesStringsTest` "every compose string in English has a German one". Web: no new i18n message in the diff (the picker shows addresses only) | A | Pass |
| AC4 unit tests per changed part; `sh scripts/check.sh` passes | All the above | `sh scripts/check.sh` | Fail: only TW-1 fails |

## Focused test execution

Command:

`cd android && ./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*'`

Result:

Fail: ktlint passes; 892 tests, 1 failed (`MailComposeIdentityScreenTest > a single identity that is not the default can still be chosen`, TW-1).

Command:

`npm --prefix web run test -- --watch=false --include (the 3 changed specs)`, plus `npm --prefix web run lint`

Result:

Pass: 3 files, 211 tests. Lint: "All files pass linting".

## Full test execution

Command:

`npm --prefix web run coverage` (full web unit suite)

Result:

Pass: 196 files, 4751 tests.

Command:

`sh scripts/check.sh` (tfp, server, web incl. build, android)

Result:

Fail: tfp OK (22 tests), server OK, web OK (lint, tests, build; only the pre-existing initial-bundle budget warning). Android: 4928 tests, 1 failed (TW-1), so `lintDebug` did not run.

## .taskcheck

Result:

Fail: only because of TW-1 (see above). It passes once TW-1 is fixed in production code.

## Bugs found

### Android From picker cannot choose an account's only identity when it is not the default

Finding ID:

`TW-1`

Test:

`ch.sonensei.cockpit.mail.compose.MailComposeIdentityScreenTest.a single identity that is not the default can still be chosen`

Production source:

`android/app/src/main/java/ch/sonensei/cockpit/mail/compose/MailComposeScreen.kt:496` (`canPick = state.editable && (state.identities.size > 1 || otherAccounts.isNotEmpty())`), and the item list at `:528`

Requirement:

AC1: "the From picker lists identities (per account) and sends with the chosen one". The web parity behavior offers the account address plus every identity when no identity is the default.

Expected behavior:

Take an account with exactly one identity that is not the default, with no identity chosen (so mail goes out from the account address), and an account that cannot change: a reply, an opened or saved draft, or a single configured account. The From button should be enabled, and the identity should be choosable.

Actual behavior:

The From button is disabled (`identities.size == 1` and no other account), so that identity can never be chosen on Android. Related: when an account has identities but none is the default, the Android menu never offers the account address. Once an identity is picked, the user cannot go back to sending from the account address. The web lists the address in that case.

Severity:

Medium

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: verified for web; partial for Android. The Android coverage run left out one test method (the TW-1 failure), because a failing test stops `createDebugUnitTestCoverageReport` from running. That test only renders the screen, and the other 9 tests of its class were included.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no existing changed-line tooling).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-identity-drafts; base 01bc0c3481d44b5deb2289967beb3da453facf7d; HEAD aba2e1ded5184e4a84b24899f178129919dd92bf; commits 214ef4783, e6b4083ce, 4a48acd50 (code), aba2e1ded (report only); file scope from `git diff --name-only 01bc0c348 HEAD -- server/src/main web/src android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD aba2e1ded plus the uncommitted test files listed above. Combined sha256 over the sha256 of each modified/untracked file: f996f1a23bc3092f51c1a7990c496c74c8f0ce147cb11aab76fb993f8b7935d2 (same before and after the runs).
- Exact included files: Android `mail/compose/{ComposeModels,ComposeSession,MailComposeApi,MailComposeRepository,MailComposeScreen,MailComposeViewModel}.kt`, `mail/list/{MailListViewModel,MailScreen}.kt`; web `mail/compose/{mail-compose-page.ts,mail-compose.service.ts,mail-compose-page.html}`, `mail/list/{mail-list-page.ts,mail-list-page.html}`. Excluded: `strings_mail_compose.xml` en/de (resources, not executable; checked by string tests); `mail-compose-page.spec.ts` (test file). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: Android JaCoCo via AGP; `./gradlew createDebugUnitTestCoverageReport testDebugUnitTest --tests` (all `mail.list.*`, `mail.read.*` and every `mail.compose` test class, `MailComposeIdentityScreenTest` without the TW-1 method) in `android/`; 891 tests, 0 failures. Web: `@vitest/coverage-v8` (Vitest 5.0.5), `npm --prefix web run coverage`, full suite.
- Run start/completion time and exit status: Android 2026-11-09 02:22:58 to 02:23:51 +01:00, exit 0. Web 02:24:19 to 02:25:28 +01:00, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (sha256 31cfe9c4d600bd328edeebf91581f20af141958eb0d2fb29b7f5298009cd1c6d, written 02:23); `web/coverage/web/coverage-summary.json` (sha256 1cff27e00ada4a1e939469da236e3c8b0ac6dea2c14205bac2135b859d15a6a3, written 02:25:28), `web/coverage/web/coverage-final.json` (sha256 ca7e52cfad58cb51f4cc76d21b0bfeb85d2d2f71c4f263784f3c7ca9b42abfc7).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Both reports were rewritten by these runs (the Gradle test task executed, and Vitest rewrote `coverage/`). The snapshot hash and HEAD were the same after the runs.
- Final run after latest test additions and production fixes: yes for the test additions. No production fix has been made yet: after the TW-1 fix, coverage must be measured again.

Values below use covered/total (percentage). Android: JaCoCo INSTRUCTION (statements), BRANCH, METHOD, LINE. Web: v8 summary counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/.../mail/compose/ComposeModels.kt` | 1324/1333 (99.3%) | 132/134 (98.5%) | 72/75 (96.0%) | 168/168 (100.0%) |
| `android/.../mail/compose/ComposeSession.kt` | 3497/3527 (99.1%) | 297/320 (92.8%) | 82/82 (100.0%) | 325/325 (100.0%) |
| `android/.../mail/compose/MailComposeApi.kt` | 303/669 (45.3%) | N/A | 32/69 (46.4%) | 52/75 (69.3%) |
| `android/.../mail/compose/MailComposeRepository.kt` | 1139/1167 (97.6%) | 84/90 (93.3%) | 29/30 (96.7%) | 110/110 (100.0%) |
| `android/.../mail/compose/MailComposeScreen.kt` | 5451/5583 (97.6%) | 191/219 (87.2%) | 109/130 (83.8%) | 449/450 (99.8%) |
| `android/.../mail/compose/MailComposeViewModel.kt` | 1615/1638 (98.6%) | 90/110 (81.8%) | 81/81 (100.0%) | 165/165 (100.0%) |
| `android/.../mail/list/MailListViewModel.kt` | 4710/4774 (98.7%) | 396/416 (95.2%) | 117/117 (100.0%) | 450/450 (100.0%) |
| `android/.../mail/list/MailScreen.kt` | 8367/8568 (97.7%) | 308/351 (87.7%) | 172/202 (85.1%) | 670/679 (98.7%) |
| Android aggregate (8 files, summed) | 26406/27259 (96.9%) | 1498/1640 (91.3%) | 694/786 (88.3%) | 2389/2422 (98.6%) |
| `web/.../mail/compose/mail-compose-page.ts` | 287/290 (98.97%) | 139/157 (88.54%) | 84/84 (100.00%) | 245/245 (100.00%) |
| `web/.../mail/compose/mail-compose.service.ts` | 36/36 (100.00%) | 25/25 (100.00%) | 13/13 (100.00%) | 31/31 (100.00%) |
| `web/.../mail/list/mail-list-page.ts` | 255/256 (99.61%) | 178/186 (95.70%) | 67/68 (98.53%) | 213/213 (100.00%) |
| `web/.../mail/compose/mail-compose-page.html` | 41/168 (24.40%) | 2/26 (7.69%) | 0/15 (0.00%) | 36/113 (31.86%) |
| `web/.../mail/list/mail-list-page.html` | 251/251 (100.00%) | 44/44 (100.00%) | 20/20 (100.00%) | 168/168 (100.00%) |
| Web aggregate (5 files, summed) | 870/1001 (86.91%) | 388/438 (88.58%) | 184/200 (92.00%) | 693/770 (90.00%) |

The aggregates cover every file in scope. No scoped file was left unmeasured. The Android numbers leave out one test method (see Status).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MailComposeApi.kt:111-194` (DTO declarations, incl. new `MailIdentityDto`/`MailIdentityListDto`) | Serializer-generated members (encode paths, `copy`, getters) | Non-material: decoding and encoding are covered by `MailComposeIdentityRepositoryTest` |
| `MailComposeScreen.kt:496,528` | TW-1: a single non-default identity cannot be picked | Failing test kept (TW-1); its method was left out of the coverage run |
| `MailComposeScreen.kt:502-510` (label fallback when the account address is blank or equals its name) | Not part of the task logic (it was there before) | Left uncovered |
| `ComposeSession.kt:122,129` (`draft.accountId` null; identities answer arriving after the compose ended) | Defensive guards | Reverse-order answer covered; the ended guard is left uncovered (low risk) |
| `MailComposeViewModel.kt:287` (`selectIdentity` without a session) | Defensive | Left uncovered |
| `mail-compose-page.ts:127,145,156-157,413` (`?? []` / `?? ''` / `?? null` fallbacks: account missing in identities map, identity without name, no account, opened draft without `identityId`) | Nullish fallbacks | Non-material; the main paths are covered by the new specs |
| `mail-list-page.ts:385` (`store.items() ?? []`) | Fallback while nothing is loaded | Non-material |
| `mail-compose-page.html` (24% statements) | Template | Most likely a source-map artifact: the spec recompiles the component with `TestBed.overrideComponent` (stub editor), so v8 cannot map the template. The From `mat-select` and the chosen label are checked in the DOM ("shows the From value of the chosen identity in the picker"). Not a test gap. |

## Unable to test

- Server/API part of AC1: the task made no server change because the API already accepts `identityId`, so there is nothing new to test on the server. `api/openapi.yaml` and the generated client were not changed.
- Real browser/device flows: not run (no e2e named by the criteria). Covered at unit, Robolectric and in-app (Hilt activity) level only.
- Ambiguity for the parent to decide (no test written, to avoid guessing): on Android an opened draft (`ComposeMode.Draft`) goes through the reply path in `ComposeSession.prepared`. Its stored body becomes `quotedHtml`/`quotedText` and is shown read-only by `Quoted(...)`, and the editable body starts empty. Saving keeps the old body, appended below any new text, so nothing is lost. But the user cannot edit the text they already wrote, while the web loads it into the editor. If "continue a draft" means editing the existing body, this needs a production change (Medium). If showing it read-only is acceptable, record an assumption.
- Formatting: Prettier was not run on the web specs (running it directly was not permitted here). `npm run lint` passes, and Prettier is not part of `scripts/check.sh`.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1). Everything else passes: web (focused, full suite, lint, build) and Android (891 of 892 mail tests; the full Android suite 4927 of 4928). Separately, the parent should decide on the read-only body of an opened Android draft (see Unable to test).
