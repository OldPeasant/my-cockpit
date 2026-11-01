# Test Report

Date: 2026-11-01

Task: mail-account-label-email - Mail screens label accounts by email address, not the sender name

Scope: base 801926989627716b85b51517689cdba4b3a39e74 (`git merge-base HEAD origin/main`) .. HEAD 2970e9f79a7ce5c7e395ed47ee61def5a27c004d. Task commits (given by the parent): 659e0fa26 (implementation and tests) and 2970e9f79 (adds the web test from the previous test pass, plus a comment-only change in `MailRepository.kt`). No uncommitted source or test changes; `git status --short` shows only the untracked `reviews/mail-account-label-email/` folder.

Requirements:
- tasks/mail-account-label-email.md (acceptance criteria and recorded assumption: Android `MailAccount.name` stays the display name, new computed `label` = address, falls back to the name if the address is blank)
- docs/ARCHITECTURE.md "Coverage"
- Parent instructions: re-run focused web tests and web coverage for mail/list on the final code; the parent runs `npm run check` and the Android Gradle check plus Android coverage

## Test files changed

None in this pass. The test added in the previous pass (`web/src/app/features/mail/list/mail-list-page.spec.ts`, test `MailListPage > tells accounts with the same sender name apart by email address`) is now committed in 2970e9f79.

## Tests added or updated

Count: 0 in this pass.

Reason: between the previously tested snapshot (659e0fa26 plus the then-uncommitted spec) and 2970e9f79, the only change is the spec being committed (identical sha256 2dd77ab7...) and a KDoc comment change in `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailRepository.kt` ("Every account by name" became "Every account by its label (email address)"). Neither changes behavior, so nothing new needs a test.

Task tests in scope (all committed):
- Web `mail-folder-tree.spec.ts`: two accounts share the display name "Ann Example"; the section `aria-label` and the `h2` text are the two email addresses.
- Web `mail-list-page.spec.ts`: shared fixture with same-named accounts; folder heading account line for acc-1 and acc-2, both "All inboxes" chips (`acc-1@example.test`, `acc-2@example.test`), and "Ann Example" not shown anywhere on the page.
- Android `MailModelsTest`: label is the address for two same-named accounts, name stays the display name, label falls back to the name when the address is blank.
- Android `MailRepositoryTest`: accounts labelled and sorted by address, name unchanged.
- Android `MailListScreenTest`: folder picker account titles show the two addresses.
- Android `MailListScreenStatesTest`: message chips show the two addresses.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 Web: folder tree account heading text and aria-label show the email address | `MailFolderTree > lists every account with its folders as links and groups as plain text`; `MailListPage > shows the unified Inbox with account names when there are several accounts` | `cd web && npx ng test --watch=false --include='src/app/features/mail/list/**/*.spec.ts' --coverage ...` (run 2026-11-01 06:05 on 2970e9f79) | Pass (7 files, 193 tests) |
| AC1 Web: account line under the folder title (`accountName`) shows the email address | `MailListPage > shows the folder with its title, account, unread and cursor state`; `MailListPage > tells accounts with the same sender name apart by email address` | same | Pass |
| AC1 Web: account chip in "All inboxes" (`accountOf`) shows the email address | `MailListPage > shows the unified Inbox with account names when there are several accounts`; `MailListPage > tells accounts with the same sender name apart by email address` | same | Pass |
| AC2 Android: folder picker account title and message chip show the email address; label comes from the address | `MailModelsTest` (label test), `MailRepositoryTest` (accounts test), `MailListScreenTest` (picker), `MailListScreenStatesTest` (chips) | `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` (run by the parent on HEAD 2970e9f79) | Pass (BUILD SUCCESSFUL, reported by the parent; this test-writer cannot run Gradle) |
| AC3 The display name is unchanged elsewhere (compose sender, identities, Settings) | `MailModelsTest` and `MailRepositoryTest` assert `name` stays the display name; the task diff (`git show --stat 659e0fa26 2970e9f79`) touches no compose, identities or settings file | Diff inspection; Android Gradle as above | Diff inspection: confirmed. Android tests: Pass (parent run on 2970e9f79) |
| AC4 Unit tests on both platforms tell two same-named accounts apart; `npm run check` and the Android Gradle check pass | Web and Android tests above | `cd web && npm run check`; Android Gradle as above | Web unit tests: Pass (focused). `npm run check`: Not run in this pass, the parent reruns it (previous pass: lint passed, one unrelated load-dependent `app.spec.ts` timeout). Android check: Pass (parent run on 2970e9f79) |

## Focused test execution

Command:

`cd web && npx ng test --watch=false --include='src/app/features/mail/list/**/*.spec.ts' --coverage --coverage-reporters=text-summary --coverage-reporters=json-summary --coverage-reporters=json --coverage-reporters=lcov`

Result:

Pass (7 test files, 193 tests)

## Full test execution

Command:

`cd web && npm run check`

Result:

Not run in this pass, as instructed: the parent reruns it. In the previous pass on the same web code, `npm run test:ci` passed (184 files, 3858 tests). `npm run check` had lint pass and 3857 of 3858 tests pass; the one failure was an unrelated 5000 ms timeout in `src/app/app.spec.ts`, which passed when run alone.

Android: `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` - Pass (BUILD SUCCESSFUL), run by the parent on HEAD 2970e9f79 and reported to this test-writer, which cannot run Gradle.

## .taskcheck

Result:

Not run (it runs Gradle, which this sandbox cannot do; the parent runs `sh scripts/check.sh`)

## Bugs found

None. No production defect was found.

## Coverage

Status: verified for the two web files (focused mail list spec selection, fresh run on 2970e9f79). Android: measured by the parent on HEAD 2970e9f79 with `createDebugUnitTestCoverageReport` (JaCoCo, `android/app/build/reports/coverage/test/debug/report.xml`). The parent sent the line and branch counters; this test-writer did not read or hash that report itself.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: web, measured by hand from `coverage-final.json` (unchanged from the previous pass, because the web inputs are byte-identical). Changed TS lines `mail-list-page.ts:66` and `:244` were executed. The changed template bindings at `mail-folder-tree.ts:28` and `:30` map to executed statements. Changed-line branches: the email-address arms were executed; the fallback arms `?? ''` at :66 and `|| ''` at :244 were not (see gaps table). Android changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-account-label-email; base 801926989627716b85b51517689cdba4b3a39e74; HEAD 2970e9f79a7ce5c7e395ed47ee61def5a27c004d; task commits 659e0fa26 and 2970e9f79 supplied by the parent and checked with `git show --stat`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 2970e9f79, no uncommitted source or test changes. sha256: `mail-list-page.ts` d87a8612abfd3e15c8be99e821a5d25d1c49d73fb16f801d923129a55545ca1c, `mail-folder-tree.ts` f2f6edffb192e55520b898017dd47a185116a27424a4414af9930ce900f444e4, `mail-list-page.spec.ts` 2dd77ab7756ff380b215841b7de720adf3c6b7ff87f00c0a04854a00b3d6235b, `mail-folder-tree.spec.ts` 62670529063b0581ae4af0032f42fec6eae1bc11857a2ffb87108e47e0fb5617. Identical before and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: web `web/src/app/features/mail/list/mail-folder-tree.ts` and `web/src/app/features/mail/list/mail-list-page.ts` (measured). Android `android/app/src/main/java/ch/sonensei/cockpit/mail/list/MailModels.kt`, `MailRepository.kt` and `MailScreen.kt`: measured by the parent with JaCoCo on HEAD 2970e9f79, because Gradle cannot run in this sandbox. No renamed or deleted files, and no exclusions.
- Provider/version, command, working directory, and test selection: Vitest 5.0.5 with v8 coverage provider; working directory `web/`; command as in "Focused test execution" (same reporters as `npm run coverage`, selection narrowed to the 7 mail list spec files).
- Run start/completion time and exit status: started 2026-11-01 06:05:43 (+01:00), tests started 06:05:52, duration 6.46 s, reports written 06:05:58; exit status 0, 193/193 passed.
- Generated report paths and report hashes/run identifier: `web/coverage/web/coverage-summary.json` (sha256 132bf5bdabab47d0e703467f2b3983055556599daffebb99247d6b3ee02269a0), `web/coverage/web/coverage-final.json` (219b37a6568c42a84ae0eb1fb69a6af785776fb120ad815feb5f3aaf842b4730), `web/coverage/web/lcov.info` (b2a114d6150beb9f53fc176edaead9736c4e707ab0a93816b74b13582714676e).
- Evidence outputs are fresh and inputs stayed unchanged: all report files were rewritten by this run (mtimes 06:05:58, previous run 06:01:35). Their content hashes equal the previous run's, as expected for byte-identical inputs and the same test selection. Input hashes were unchanged after the run.
- Final run after latest test additions and production fixes: yes for web (2970e9f79 is the final code; no production fixes since). Android: yes, the parent's run was on HEAD 2970e9f79.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/app/features/mail/list/mail-folder-tree.ts` | 49/49 (100%) | 21/22 (95.45%) | 3/3 (100%) | 35/35 (100%) |
| `web/src/app/features/mail/list/mail-list-page.ts` | 137/138 (99.27%) | 85/95 (89.47%) | 39/40 (97.5%) | 113/113 (100%) |
| Web aggregate (summed counters, 2 files) | 186/187 (99.47%) | 106/117 (90.60%) | 42/43 (97.67%) | 148/148 (100%) |
| `android/.../mail/list/MailModels.kt` (parent, JaCoCo) | N/A (not reported) | 61/62 (98.39%) | N/A (not reported) | 69/69 (100%) |
| `android/.../mail/list/MailRepository.kt` (parent, JaCoCo) | N/A (not reported) | 26/26 (100%) | N/A (not reported) | 56/56 (100%) |
| `android/.../mail/list/MailScreen.kt` (parent, JaCoCo) | N/A (not reported) | 143/183 (78.14%) | N/A (not reported) | 294/299 (98.33%) |
| Android aggregate (summed counters, 3 files) | N/A | 230/271 (84.87%) | N/A | 419/424 (98.82%) |

The web and Android aggregates are kept apart because they come from different providers (v8 and JaCoCo). All five task-changed production files are measured, and none are excluded. The Android numbers are whole-file numbers from the parent's run. `MailScreen.kt` is a large file, and most of what is uncovered there is code this task did not change. This test-writer did not get per-line locations for those gaps.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `mail-list-page.ts:66` branch `?? ''` (folder whose account is not in the account list) | Defensive fallback; pre-existing, the task only swapped `displayName` for `emailAddress` | Left uncovered: cannot happen with consistent store data; no acceptance criterion covers it |
| `mail-list-page.ts:244` branch `|| ''` (message whose account is unknown) | Same defensive fallback for the chip | Left uncovered for the same reason |
| `mail-list-page.ts` other uncovered branches (lines 39, 55, 60, 70, 77, 183, 232, 265) and statement at line 88 | Code the task did not change | Outside the task scope |
| `mail-folder-tree.ts:55` branch | Code the task did not change (folder row rendering) | Outside the task scope |
| Android `MailModels.kt` `label` getter (both arms), `MailRepository.kt` sort by label, `MailScreen.kt` picker title and chip | AC2 | Covered by existing tests in `MailModelsTest`, `MailRepositoryTest`, `MailListScreenTest` and `MailListScreenStatesTest` (read here). These tests passed in the parent's Gradle run on 2970e9f79. `MailModels.kt` and `MailRepository.kt` have every line covered. The task changed only two lines in `MailScreen.kt` (picker title and chip), and the screen tests assert both. Its remaining uncovered lines and branches were not reported per location |

## Unable to test

- Android unit tests, lint and JaCoCo coverage: Gradle cannot run in this sandbox. The parent ran `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug createDebugUnitTestCoverageReport` on 2970e9f79 (BUILD SUCCESSFUL) and supplied the coverage numbers above. No follow-up needed.
- Web `npm run check` and `.taskcheck`: not run in this pass by instruction; the parent reruns them.
- AC3 on web (Settings account list, identities): outside `web/src/app/features/mail/list` and untouched by the task diff; confirmed by diff inspection only.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. The re-run on the final revision 2970e9f79 passed (7 files, 193 tests), and the web coverage numbers are unchanged. No further tests were needed, because the only change since the previous pass is a comment. The parent's Android Gradle check passed on 2970e9f79, and Android coverage (measured by the parent) is 419/424 lines and 230/271 branches across the three changed files.
