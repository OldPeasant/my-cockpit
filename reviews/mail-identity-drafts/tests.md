# Test Report

Date: 2026-11-09

Task: mail-identity-drafts: Choose sending identity and resume drafts (web + Android)

Scope: base 7e0d05a872840148fb11271d4baad499bd9eb23a .. HEAD b93d060acf9c46fc2bcf041b6ab50c46f255f8a4 (branch main, local). Task code commits: a10b695db (web picker + drafts open in compose), bb142855a (Android ComposeMode.Draft), 2dabe1bdf (Android identity picker), 0b2384c18 (tests, first test report), b93d060ac (fixes for CR-1, CR-2, CR-4/TW-1, CR-5; this is commit 0e3156e93 after the rebase, same message and content). 5bafe51d9 is the review report only. The other commits in the range only touch `tasks/`. Production paths come from `git diff --name-only 7e0d05a87 HEAD -- server/src/main web/src android/app/src/main`, checked against the task's `touches` and `reviews/mail-identity-drafts/fixes.md`. The working tree holds uncommitted test changes only (the two files below). There are no production changes in it.

This report replaces the earlier one (rev aba2e1ded, before the fixes). Every number below comes from runs on b93d060ac plus the uncommitted tests.

Requirements:
- tasks/mail-identity-drafts.md acceptance criteria and recorded assumptions (the account address is listed only when the account has no default identity; an untouched opened draft is kept on leave; identities that fail to load leave only the account address; a resumed Android draft loads its plain text into the editor; leaving while an opened draft is loading keeps it)
- SPEC.md §7.3 ("choose sending identity")
- docs/ARCHITECTURE.md "Coverage"; AGENTS.md
- reviews/mail-identity-drafts/review.md and fixes.md (CR-1, CR-2, CR-4/TW-1, CR-5)
- Parent instructions: check that the fixes have adequate tests and re-run everything on the final code

## Test files changed

In this run (uncommitted):
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeIdentityDraftTest.kt` (4 new tests)
- `android/app/src/test/java/ch/sonensei/cockpit/mail/compose/MailComposeIdentityScreenTest.kt` (1 new test)

Already committed for this task (0b2384c18 by the test-writer, b93d060ac by the fixer): `ComposeModelsIdentityDraftTest.kt`, `MailComposeIdentityDraftTest.kt`, `MailComposeIdentityRepositoryTest.kt`, `MailComposeIdentityScreenTest.kt`, `mail/list/MailListDraftTest.kt`, `MailComposeInAppTest.kt` (one test), 11 existing compose test files changed for the new API (strict mocks stub `repository.identities`, `Draft(...)` gets `identityId`; no assertion weakened), and the web specs `mail-compose-page.spec.ts`, `mail-compose.service.spec.ts`, `mail-list-page.spec.ts`.

## Tests added or updated

Count: 5 new tests in this run. The task now has 66 new tests: 52 from the first test-writer run, 9 from the fixer and 5 from this run (Android 49, web 17).

Review of the fixer's tests: each fix has a test that would fail without it. CR-1: the screen shows identities with no account list, and an opened draft loads its accounts. CR-2: the editable body, a save that stores only the edited text, a reply that still quotes, and a draft left while loading is not discarded. CR-4/TW-1: the TW-1 test now passes, the address entry is offered or hidden, and `selectAccountAddress` saves null or is refused. CR-5 is checked by ktlint. This run fills these gaps:
- `a reply also loads the accounts for its From row, its account stays locked` (CR-1: the fixer's test of that name only covered an opened draft)
- `accounts that cannot be read for an opened draft leave its identities and keep it editable` (CR-1: a failed account load is ignored)
- `choosing the account address while it is already used changes nothing` (CR-4: no spurious edit or save)
- `an opened draft can switch from the account address to an identity and back before saving` (CR-4 + CR-2: the chosen identity is saved, then null, on the same draft, with only the body text)
- `without a default identity and before the accounts are loaded the identity shows and no address entry is offered` (CR-1 + CR-4 together on the screen)

## Requirement coverage

Android command A: `android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' createDebugUnitTestCoverageReport` (906 tests, 0 failures, ktlint clean).
Web command W: `npm --prefix web run coverage` (full web unit suite, 196 files, 4751 tests passed; it includes the 3 changed mail specs).

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 web: the From picker lists identities per account (the account address only without a default identity) | `sending identity`: lists the identities of each account...; leaves out identities without an address; shows the From value of the chosen identity in the picker; offers only the account addresses when identities cannot be read, and still saves | W | Pass |
| AC1 web: sends/saves with the chosen identity (same account = edit, another account = move, a failed move restores it) | creates a new mail with the identity picked before the first save; shows the identity of an opened draft and saves another identity of its account as an edit; shows the default identity when the draft's identity is not listed any more; choosing the entry already shown changes nothing; moves a stored draft to an identity of another account...; puts account and identity back when the move fails; keeps identities of another account disabled once a reply cannot move | W | Pass |
| AC1 web: identities endpoint client | `MailComposeService` lists the sending identities of an account | W | Pass |
| AC1 Android: identities loaded per account, the chosen identity saved with the draft | `MailComposeIdentityDraftTest`: a new mail loads the identities...; a chosen identity is an edit and is created with the new draft; an identity not listed... changes nothing; another account forgets the identity...; identities of an account left meanwhile are not shown (reverse-order answer); identities that cannot be read...; without any account no identities are asked for; a reply loads the identities... | A | Pass |
| AC1 Android: API mapping (identities JSON, identityId in create/update/read) | `MailComposeIdentityRepositoryTest` (5 tests) | A | Pass |
| AC1 Android: From picker UI (identities, then other accounts; locked account; not editable) | `MailComposeIdentityScreenTest` (the 7 picker tests of the first run) | A | Pass |
| AC1 Android, TW-1/CR-4: a single non-default identity can be chosen; the account address is offered without a default identity and saves `identityId = null` | `MailComposeIdentityScreenTest`: a single identity that is not the default can still be chosen; without a default identity the account address is offered and can be chosen back; with a default identity the account address is not offered; without a default identity and before the accounts are loaded...; `MailComposeIdentityDraftTest`: the account address replaces a chosen identity only while...; the account address is not chosen while the account has a default identity; choosing the account address while it is already used changes nothing; an opened draft can switch from the account address to an identity and back before saving | A | Pass |
| AC1 Android, CR-1: the From row and its identities show for replies, forwards and opened drafts | `MailComposeIdentityScreenTest`: an opened draft or reply shows its identities before the accounts are loaded; `MailComposeIdentityDraftTest`: a reply or opened draft loads the accounts...; a reply also loads the accounts...; accounts that cannot be read for an opened draft... | A | Pass |
| AC1 server/API: change the server only if needed | No server change in the task diff. The API already accepts `identityId`, and the generated web client has `listMailIdentities`. | n/a | Not testable (no server change) |
| AC2 web: opening Drafts-folder mail opens it in compose; saving updates it | `in a Drafts folder` (5 tests); the existing `opens a stored draft without creating another`; "...saves another identity of its account as an edit" | W | Pass |
| AC2 Android: the list opens Drafts-folder mail in compose | `MailListDraftTest` (4 tests); `MailComposeInAppTest`: a mail of a Drafts folder opens in compose as that draft... | A | Pass |
| AC2 Android, CR-2: the opened draft's text is editable, saving updates that draft with only the edited text, and leaving keeps it | `MailComposeIdentityDraftTest`: an opened draft puts its text into the editable body...; a reply keeps the original as quoted text...; an opened draft left while it still loads is kept, unlike a reply; an opened draft shows its content and identity, saving updates that draft...; leaving an opened draft untouched keeps it; leaving an edited opened draft saves it into the same draft; a draft that cannot be opened offers retry; `ComposeModelsIdentityDraftTest` | A | Pass |
| AC3 new strings in English and German | `MailComposeIdentityScreenTest` "a continued draft is titled Draft" / "...Entwurf in German"; the existing `MailComposeRecipientsFilesStringsTest` (every English compose string has a German one). The fixes add no strings. Web: no new i18n unit (the picker shows names and addresses only) | A | Pass |
| AC4 unit tests per changed part; `sh scripts/check.sh` passes | All the above | `sh scripts/check.sh` | Pass |

## Focused test execution

Command:

`android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.compose.MailComposeIdentity*' -q`

Result:

Pass: ktlint clean. MailComposeIdentityDraftTest 22/22, MailComposeIdentityRepositoryTest 5/5, MailComposeIdentityScreenTest 14/14.

Command:

`android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' createDebugUnitTestCoverageReport -q` (the command the parent asked for, plus the coverage report)

Result:

Pass: ktlint clean, 906 tests (901 after the fixer plus 5 new), 0 failures, 0 errors, 0 skipped (summed from `android/app/build/test-results/testDebugUnitTest/*.xml`).

## Full test execution

Command:

`npm --prefix web run coverage` (full web unit suite)

Result:

Pass: 196 files, 4751 tests.

Command:

`sh scripts/check.sh` (tfp, server, web lint/tests/build, android ktlint/all unit tests/lintDebug)

Result:

Pass: exit 0, "== all checks passed" (2026-11-09, about 02:49 to 02:58 +01:00, on HEAD b93d060ac plus the 2 uncommitted test files). tfp gate tests OK, server ktlint + tests OK, web eslint + 4751 tests + build OK, Android ktlint + 4943 unit tests (0 failures, 0 errors, 0 skipped, summed from the test-result XML) + lintDebug OK.

## .taskcheck

Result:

Pass (`.taskcheck` is `sh scripts/check.sh`, see above).

## Bugs found

None open. TW-1 from the first run (an account's only, non-default identity could not be chosen on Android) is fixed in b93d060ac. Its test `MailComposeIdentityScreenTest > a single identity that is not the default can still be chosen` passes now, unchanged.

Observation, not a defect against a criterion (no failing test written): an opened Android draft whose account has no default identity and no chosen identity shows an empty From label until the account list arrives. If `repository.accounts()` fails, the label stays empty. The picker still opens and lists the identities (covered by the new screen test). The fixer's change made this fallback the account name and address, which is only known once the accounts are loaded. Low impact. The parent may decide whether a label is needed while the list is loading.

## Coverage

Status: verified (Android and web, fresh runs on the final code, 0 failures).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no existing changed-line tooling).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-identity-drafts; base 7e0d05a872840148fb11271d4baad499bd9eb23a; HEAD b93d060acf9c46fc2bcf041b6ab50c46f255f8a4; code commits a10b695db, bb142855a, 2dabe1bdf, b93d060ac (tests in 0b2384c18, b93d060ac); file scope from `git diff --name-only 7e0d05a87 HEAD -- server/src/main web/src android/app/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD b93d060ac plus two modified test files, sha256 `MailComposeIdentityDraftTest.kt` 5cfffe52b517f62b20300b58e4a315632e4128508bfd3a22e08ef94d4e8c4345 and `MailComposeIdentityScreenTest.kt` 696c5c48087ccecc06abbcf2db0683ed0d4fab9dc90eeb6dac2d3cb55eb117b7. These were the same before and after the runs, and there were no other working-tree changes.
- Exact included files: Android `mail/compose/{ComposeModels,ComposeSession,MailComposeApi,MailComposeRepository,MailComposeScreen,MailComposeViewModel}.kt`, `mail/list/{MailListViewModel,MailScreen}.kt`; web `mail/compose/{mail-compose-page.ts,mail-compose.service.ts,mail-compose-page.html}`, `mail/list/{mail-list-page.ts,mail-list-page.html}`. Excluded: `res/values{,-de}/strings_mail_compose.xml` (resources, not executable; checked by the string tests). No server files, and no renamed or deleted files.
- Provider/version, command, working directory, and test selection: Android JaCoCo via AGP `enableUnitTestCoverage`, `android/gradlew -p android ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.mail.*' createDebugUnitTestCoverageReport` from the clone root (all mail.* tests, 906). Web: Vitest + `@vitest/coverage-v8`, `npm --prefix web run coverage`, full suite.
- Run start/completion time and exit status: Android 2026-11-09 02:46:42 to 02:47:30 +01:00, exit 0. Web about 02:47:40 to 02:48:52 +01:00, exit 0.
- Generated report paths and report hashes/run identifier: `android/app/build/reports/coverage/test/debug/report.xml` (written 02:47:28, sha256 8f877ca055e0aaa0cb5b0fd79cdd4aaf35295563e199bf064ff042279bafed5c); `web/coverage/web/coverage-summary.json` (written 02:48:51, sha256 1cff27e00ada4a1e939469da236e3c8b0ac6dea2c14205bac2135b859d15a6a3; same bytes as the earlier run, because no web code or spec changed since then), `web/coverage/web/coverage-final.json` (sha256 be388a2525caff7c4363292cf50981c414d27fda020a82e9a561b63992cee84a).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Both reports were rewritten by these runs: the JaCoCo report is new (its hash differs from the run before the fixes), and Vitest rewrote `coverage/`. The test-result timestamps fall in the run window. HEAD and the snapshot hashes did not change.
- Final run after latest test additions and production fixes: yes (after the fixer's b93d060ac and the 5 new tests).

Values below use covered/total (percentage). Android: JaCoCo INSTRUCTION (statements), BRANCH, METHOD (functions), LINE. Web: v8 summary counters.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `android/.../mail/compose/ComposeModels.kt` | 1324/1333 (99.3%) | 132/134 (98.5%) | 72/75 (96.0%) | 168/168 (100.0%) |
| `android/.../mail/compose/ComposeSession.kt` | 3619/3652 (99.1%) | 312/338 (92.3%) | 85/85 (100.0%) | 335/335 (100.0%) |
| `android/.../mail/compose/MailComposeApi.kt` | 303/669 (45.3%) | N/A | 32/69 (46.4%) | 52/75 (69.3%) |
| `android/.../mail/compose/MailComposeRepository.kt` | 1139/1167 (97.6%) | 84/90 (93.3%) | 29/30 (96.7%) | 110/110 (100.0%) |
| `android/.../mail/compose/MailComposeScreen.kt` | 6147/6278 (97.9%) | 327/409 (80.0%) | 113/135 (83.7%) | 462/462 (100.0%) |
| `android/.../mail/compose/MailComposeViewModel.kt` | 1667/1692 (98.5%) | 95/116 (81.9%) | 83/83 (100.0%) | 170/170 (100.0%) |
| `android/.../mail/list/MailListViewModel.kt` | 4710/4774 (98.7%) | 396/416 (95.2%) | 117/117 (100.0%) | 450/450 (100.0%) |
| `android/.../mail/list/MailScreen.kt` | 8367/8568 (97.7%) | 308/351 (87.7%) | 172/202 (85.1%) | 670/679 (98.7%) |
| Android aggregate (8 files, summed) | 27276/28133 (97.0%) | 1654/1854 (89.2%) | 703/796 (88.3%) | 2417/2449 (98.7%) |
| `web/.../mail/compose/mail-compose-page.ts` | 287/290 (98.96%) | 139/157 (88.53%) | 84/84 (100.00%) | 245/245 (100.00%) |
| `web/.../mail/compose/mail-compose.service.ts` | 36/36 (100.00%) | 25/25 (100.00%) | 13/13 (100.00%) | 31/31 (100.00%) |
| `web/.../mail/list/mail-list-page.ts` | 255/256 (99.60%) | 178/186 (95.69%) | 67/68 (98.52%) | 213/213 (100.00%) |
| `web/.../mail/compose/mail-compose-page.html` | 41/168 (24.40%) | 2/26 (7.69%) | 0/15 (0.00%) | 36/113 (31.85%) |
| `web/.../mail/list/mail-list-page.html` | 251/251 (100.00%) | 44/44 (100.00%) | 20/20 (100.00%) | 168/168 (100.00%) |
| Web aggregate (5 files, summed) | 870/1001 (86.91%) | 388/438 (88.58%) | 184/200 (92.00%) | 693/770 (90.00%) |

The aggregates cover every file in scope. No scoped file was left unmeasured. JaCoCo's branch total for `MailComposeScreen.kt` grew (219 to 409) because the fixed `From` composable has more conditions (`canPick`, `addressEntry`). All its lines are covered.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `ComposeSession.kt:136` (`loadAccounts` when the accounts are already loaded) | Defensive. `prepared` runs only for reply, forward and draft modes, which never load accounts first | Left uncovered (not reachable in the current flows) |
| `ComposeSession.kt:139,148` (accounts or identities arriving after compose ended) | Defensive guards against late answers | A failed account load is now covered (new test). The ended branch is left uncovered (low risk; same pattern as the identities guard) |
| `ComposeSession.kt:130` (`draft.accountId` null) | Defensive | Left uncovered |
| `MailComposeScreen.kt:499,512` (one side of the early return / `addressEntry && chosen` condition) | CR-1/CR-4 conditions | Main combinations covered by 5 screen tests (incl. the new one). The remaining short-circuit side is non-material |
| `MailComposeScreen.kt:564` (`MailAccount.sender`: blank address, or the address equals the name) | Label fallback moved from pre-existing code | Left uncovered (not task logic) |
| `MailComposeViewModel.kt:287,297` (`selectIdentity` / `selectAccountAddress` without a session) | Defensive | Left uncovered |
| `MailComposeApi.kt` (DTO declarations, incl. `MailIdentityDto`/`MailIdentityListDto`) | Members generated by the serializer (`copy`, getters, unused encode paths) | Non-material. Decoding and encoding are covered by `MailComposeIdentityRepositoryTest` |
| `mail-compose-page.ts:127,145,156-157,413` (`?? []` / `?? ''` / `?? null` fallbacks) | Nullish fallbacks | Non-material; the main paths are covered by the specs |
| `mail-list-page.ts:385` (`store.items() ?? []`) | Fallback while nothing is loaded | Non-material |
| `mail-compose-page.html` (24% statements) | Template | A source-map artifact: the spec recompiles the component with `TestBed.overrideComponent`, so v8 cannot map the template. The From `mat-select` and the chosen label are checked in the DOM. Not a test gap |

## Unable to test

- Server/API part of AC1: no server change was made (the API already accepts `identityId`), so there is nothing new to test on the server.
- Real browser/device flows: not run (no criterion names e2e). They are covered at unit, Robolectric and in-app (Hilt activity) level.
- Formatting of a resumed Android draft (bold, links, images) is lost by design: it loads as plain text (recorded assumption). No test asserts that formatting is kept, and one asserts that the saved HTML is the edited plain text.

## Summary

Tests added and all validation passed. The fixes for CR-1, CR-2, CR-4/TW-1 and CR-5 are covered by tests (the fixer's 9, plus 5 added here for the remaining gaps). No production defects are open. Android mail tests (906), the full web suite (4751) and `sh scripts/check.sh` pass on the final code. Fresh task-scoped coverage: Android 98.7% lines / 89.2% branches, web 90.0% lines / 88.6% branches. One low-impact observation for the parent: the From label is empty on Android while the accounts load (see Bugs found).
