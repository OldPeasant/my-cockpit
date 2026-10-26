# Test Report

Date: 2026-10-26

Task: mail-compose-identity-fallback - New mail starts From the account address unless an identity is default

Scope: `git diff 4c3b0c95d405b6e825f2dfd11a232b717b9eb851..ee86fc7b05cc476b97050891c3f1835378e55cfd` (base supplied by the parent = the task's claim commit; one task commit `ee86fc7b0`), plus the uncommitted test addition of this report (working tree on branch `main`).

Requirements:
- `tasks/mail-compose-identity-fallback.md` (goal + single acceptance criterion)
- Parent instructions (also consider reply/forward via `respond` when no identity matches and none is default)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftComposeGreenMailTests.kt`

## Tests added or updated

Count: 1 added (`a reply or forward addressed to no identity is From the account address unless an identity is default`). The task commit's test `a new draft is From the account address unless an identity is default` was reviewed and kept unchanged: it covers both halves of the criterion for a new draft (no default -> account name + address, also on the received message; default -> that identity).

The added test covers `respond` (reply, forward): the original is addressed to the account address and two other people, the account has a non-default identity with a Reply-To. The reply is From "Jane Doe" `jane.doe@example.test` (the account), `identityId` is null in the create response and on GET, and the stored IMAP draft has the account From and no Reply-To (the identity's Reply-To does not leak). After a default identity is added, a forward of the same message is From the default identity (response and stored draft). Without the fix (fallback to the first identity) the reply half fails, so the test also guards against regression.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| New draft, no identityId, no default identity -> From account display name + address | `a new draft is From the account address unless an identity is default` | `cd server && ./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.compose.*'` (GreenMail + Postgres Testcontainer, Docker) | Pass |
| New draft with a default identity -> that identity | `a new draft is From the account address unless an identity is default` | same | Pass |
| Reply/forward that matches no identity, no default -> account address, no identity Reply-To; with a default -> default identity | `a reply or forward addressed to no identity is From the account address unless an identity is default` | same | Pass |
| Reply addressed to an identity still uses it (unchanged behavior, regression check) | `DraftSendGreenMailTests.a reply is sent by default as the identity the original was addressed to` | same | Pass |
| Explicit identity still used (unchanged behavior) | `a draft sent as an identity carries its From and Reply-To` | same | Pass |

## Focused test execution

Command:

`cd server && ./gradlew integrationTest --tests '*DraftComposeGreenMailTests*'` -> 17 tests, 0 failures. Then `./gradlew integrationTest --tests 'ch.sonensei.cockpit.mail.compose.*'` -> DraftComposeGreenMailTests 17 + DraftSendGreenMailTests 15 = 32 tests, 0 failures/errors/skips.

Result:

Pass

## Full test execution

Command:

`cd server && ./gradlew test jacocoTestReport` (2026-10-26 07:33:47 -> 07:40:01, exit 0)

Result:

Pass: 4667 tests, 0 failures, 0 errors, 0 skipped (server unit + Spring/Postgres suite; external-tagged tests run separately as above).

## .taskcheck

Result:

Pass: `sh scripts/check.sh` (07:44:13 -> 07:47:32, exit 0): tfp gate tests, server ktlint + tests, web eslint + tests + build (3665 tests), android ktlint + unit tests + lint; "all checks passed".

## Bugs found

None.

## Coverage

Status: verified (with the scope caveat below).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: measured by hand from the JaCoCo XML line records. The task diff changes three lines of `DraftService.kt`: 293 and 437 are KDoc (not executable); 443 (`if (identityId == null) return all.firstOrNull { it.isDefault }`) is the only executable changed line. In the integration run it is covered with 2/2 branches (ci=10, mi=0, cb=2, mb=0): 1/1 changed executable line covered.

The tests that exercise this behavior are `@ExternalTest` (GreenMail) and run only in `integrationTest`. The documented coverage command `./gradlew test jacocoTestReport` reads only `test.exec`, so it barely reaches `DraftService` (only via a few Spring tests). Both results are listed below. The integration report was made by a one-off Gradle init script outside the repository (`.tmp/itcov.init.gradle.kts`, Git-ignored), which registers a `JacocoReport` over `build/jacoco/integrationTest.exec` and the main classes/sources. No project build or coverage configuration was changed.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-compose-identity-fallback`; base `4c3b0c95d405b6e825f2dfd11a232b717b9eb851` (claim commit, from the parent); HEAD `ee86fc7b05cc476b97050891c3f1835378e55cfd`; task-owned commit `ee86fc7b0` (from the parent, checked with `git diff --stat`: it changes only DraftService.kt and the test file).
- Source/test snapshot identifier (including uncommitted inputs): HEAD `ee86fc7b0` + sha256 `DraftService.kt` 947479ebced4b0d371798066fee8cde19981b9068e38af05468ef9aefdc5391d (unchanged from HEAD), `DraftComposeGreenMailTests.kt` b4dc58904981601f600bd7821da8c44972473d545f7fd70041f64a4f47fb3494 (uncommitted, contains the added test). The same hashes were checked after the runs.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt`. Excluded: the test file (test code). No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin, Gradle 9.8.1), working dir `server/`. (a) `./gradlew test jacocoTestReport` with the full `test` task. (b) `./gradlew -I ../.tmp/itcov.init.gradle.kts integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.compose.*' tmpIntegrationCoverage` with 32 external tests (`integrationTest.exec` deleted first).
- Run start/completion time and exit status: (a) 07:33:47 -> 07:40:01, exit 0 (old XML report deleted first). (b) 07:42:26 -> 07:44:05, exit 0.
- Generated report paths and report hashes/run identifier: (a) `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 1ce362ced016746504407fef4ee1f020ec3d7149466262b7bd1795ade64024bf (written 07:40:01). (b) `server/build/tmp-itcov/report.xml` sha256 8e8e8e4b290010b9968134913aedf7a11bdf85369550e73eb446095683c32d55 (written 07:44:05, exec file 07:44:04).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Old outputs were deleted before each run, file times fall inside the runs, and the snapshot hashes match after the runs.
- Final run after latest test additions and production fixes: yes (no production fixes so far; if the fixer changes code these numbers are stale).

Values below use covered/total (percentage).

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `DraftService.kt`, (b) integrationTest compose suites | 2509/2897 (86.6%) | 177/260 (68.1%) | 87/89 (97.8%) | 336/354 (94.9%) |
| `DraftService.kt`, (a) documented `test jacocoTestReport` | 453/2897 (15.6%) | 31/260 (11.9%) | 36/89 (40.4%) | 73/354 (20.6%) |

Aggregate = the single file above (no other production file in scope, nothing unmeasured). Method `identity` in (b): instructions 29/36, branches 3/4, lines 3/3. Method `respond`: lines 24/24, branches 18/24.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `DraftService.kt:444` (1 branch, the `throw` for an identityId of another account), in run (b) | Validation of an explicit identityId. This existed before the task and is not changed by it | Not added. Pre-existing behavior outside the criterion. Run (a) reaches line 444 through a Spring test. |
| `DraftService.kt:340-342` (blank name -> null personal, `ifBlank` branches) | Display-name fallback for a blank identity or account name. Pre-existing, not changed | Not added. Outside the task's changed lines. |
| `DraftService.kt:159, 486-487, 502-503, 515-518, 584-595, 694` and other partial branches | Other draft operations (attachments, size limits, locking), not part of this task | Not in task scope; no test added. |

## Unable to test

Nothing in scope. The documented coverage command cannot see the GreenMail tests, so integration coverage needed the out-of-repository init script described above. Follow-up for the project, optional: make `jacocoTestReport` (or a sibling task) include `integrationTest.exec`.

## Summary

Tests added and all validation passed. No production defects found. The acceptance criterion is covered for new drafts (task test) and for reply/forward (added test), and the one changed executable line is covered on both branches.
