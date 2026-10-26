# Test Report

Date: 2026-10-26

Task: mail-compose-identity-fallback - New mail starts From the account address unless an identity is default

Scope: `git diff b5a29e658c4130d7f04c9a82df5368bf94442386..42c33370dac6a4c9dabff186df2d23cfe10e4450` (base = merge-base with origin/main, supplied by the parent). Task commits: `775cfdc8a` (server fix + new-draft test), `b8a1cf546` (reply/forward test + reports), `1a5781299` (CR-1/CR-2: settings hint text and spec, DraftController API descriptions, regenerated `api/openapi.yaml` and web generated client), `42c33370d` (reviews only). Working tree clean (branch `main`); this pass changed no test file. It replaces the earlier report, which covered only `775cfdc8a` plus the then uncommitted reply/forward test.

Requirements:
- `tasks/mail-compose-identity-fallback.md` (goal, single acceptance criterion, notes/assumption about the CR-1/CR-2 hint and API doc fixes)
- Parent instructions (re-run on the final code; check the web spec change)
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- None in this pass. The task's test files (committed in the task commits) were reviewed:
  - `server/src/test/kotlin/ch/sonensei/cockpit/mail/compose/DraftComposeGreenMailTests.kt`
  - `web/src/app/features/settings/mail/identities.spec.ts`

## Tests added or updated

Count: 0 in this pass. Coverage of the task by the existing tests:

- `a new draft is From the account address unless an identity is default` (server, added in `775cfdc8a`): with only a non-default identity, a new draft has `identityId` null and From "Jane Doe" + the account address, also on the sent message received by the recipient. After a default identity is added, a new draft uses it (id, name, address). This covers both halves of the criterion.
- `a reply or forward addressed to no identity is From the account address unless an identity is default` (server, added in `b8a1cf546`): a reply matching no identity is From the account, its stored IMAP draft has no Reply-To from the non-default identity, and after a default identity is added a forward is From that identity. If the old fallback to the first identity comes back, the reply half fails.
- `says that new mail starts with the default identity, else the account address` (web, updated in `1a5781299`): checks the new hint text with the account address filled in, and the new default-toggle hint in the edit form. It also checks that neither text still mentions "first one" or "first identity" (the old wording), so the CR-1 fix is guarded.

No test is missing for the criterion or for the web change. The DraftController changes are only OpenAPI description strings. `OpenApiContractTest` (committed contract equals generated) and the web `api:check` (generated client up to date) both ran and passed.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| New draft, no identityId, no default identity -> From account display name + address | `DraftComposeGreenMailTests.a new draft is From the account address unless an identity is default` | `cd server && ./gradlew -I ../.tmp/itcov.init.gradle.kts integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.compose.*' tmpIntegrationCoverage` (GreenMail + Postgres Testcontainers, Docker) | Pass |
| New draft with a default identity -> that identity | same test | same | Pass |
| Reply/forward matching no identity: account address with no identity Reply-To when there is no default; the default identity when there is one | `DraftComposeGreenMailTests.a reply or forward addressed to no identity is From the account address unless an identity is default` | same | Pass |
| Unchanged: a reply addressed to an identity uses that identity | `DraftSendGreenMailTests.a reply is sent by default as the identity the original was addressed to` | same | Pass |
| Unchanged: an explicit identity is used (From + Reply-To) | `DraftComposeGreenMailTests.a draft sent as an identity carries its From and Reply-To` | same | Pass |
| Settings hint names the account-address fallback (CR-1) | `MailIdentities > says that new mail starts with the default identity, else the account address` | `cd web && npm run check` | Pass |
| API docs/contract in sync (CR-2): openapi.yaml matches the server, generated client matches openapi.yaml | `OpenApiContractTest.api openapi yaml matches the generated contract`; web `api:check` | `cd server && ./gradlew test jacocoTestReport`; `cd web && npm run check` | Pass |

## Focused test execution

Command:

`cd server && ./gradlew -I ../.tmp/itcov.init.gradle.kts integrationTest --rerun --tests 'ch.sonensei.cockpit.mail.compose.*' tmpIntegrationCoverage` (run as `server/gradlew -p server -I` with the absolute path of `.tmp/itcov.init.gradle.kts` from the clone root, 08:27:53 -> 08:30:23, exit 0; `integrationTest.exec` and the old report were deleted first)

Result:

Pass: DraftComposeGreenMailTests 17 + DraftSendGreenMailTests 15 = 32 tests, 0 failures/errors/skips.

## Full test execution

Command:

1. `cd server && ./gradlew test jacocoTestReport` (08:30:52 -> 08:38:21, exit 0; old XML report deleted first)
2. `cd web && npm run check` (api:check, lint, e2e typecheck, test:ci, build; exit 0)
3. `cd web && npm run coverage` (08:30:56 -> 08:32:09, exit 0; `web/coverage/web` deleted first)

Result:

Pass: (1) 4687 server tests, 0 failures, 0 errors, 0 skipped (including `OpenApiContractTest`). (2) "src/app/api/generated is up to date", "All files pass linting", 175 test files / 3665 tests passed, build OK. The only output is a pre-existing initial bundle budget warning. (3) 3665 tests passed.

## .taskcheck

Result:

Not run in this pass. The parent runs `sh scripts/check.sh` before `done`. Its server tests and web check parts ran separately above and passed. Android is not touched by the task.

## Bugs found

None.

## Coverage

Status: verified (server via a Git-ignored init script for the integration suite, see below; web via the documented command).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: worked out by hand from the provider line records.
- `DraftService.kt`: the diff changes lines 293 and 437 (KDoc, not executable) and line 443 (`if (identityId == null) return all.firstOrNull { it.isDefault }`). Line 443 is the only changed executable line. In the integration run it has ci=10, mi=0, cb=2, mb=0, so 1/1 changed executable lines and 2/2 of their branches are covered.
- `DraftController.kt`: the changed lines are `@Operation`/`@Parameter`/`@Schema` description strings, which compile to annotation metadata, not executable code (0 executable changed lines, N/A).
- `identities.html`: changed template lines 3-8 and 173. Their v8 statements (ids 140, 171-173 and the statements on line 172/174) have 2-26 hits each. Every changed statement is covered.

The GreenMail tests that check the behavior are `@ExternalTest` and run only in `integrationTest`. The documented `./gradlew test jacocoTestReport` reads only `test.exec`. Both server runs are listed. The integration report comes from `.tmp/itcov.init.gradle.kts` (Git-ignored, outside the build files), which registers a `JacocoReport` over `integrationTest` exec data and the main classes/sources. No project build or coverage configuration was changed.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-compose-identity-fallback`; base `b5a29e658c4130d7f04c9a82df5368bf94442386`; HEAD `42c33370dac6a4c9dabff186df2d23cfe10e4450`; task commits `775cfdc8a`, `b8a1cf546`, `1a5781299`, `42c33370d` (from the parent, checked with `git log`/`git diff --stat` base..HEAD).
- Source/test snapshot identifier (including uncommitted inputs): HEAD `42c33370d`, clean tree. sha256: `DraftService.kt` 947479eb...5391d, `DraftController.kt` 4d7d50d0...a9ff, `DraftComposeGreenMailTests.kt` b4dc5890...3494, `identities.html` fdc4e5e7...2bda, `identities.spec.ts` fe0b8e7a...d234. Recorded before the runs (`.tmp/mcif2-snap-before.txt`) and checked again after all runs: all OK.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftService.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/compose/DraftController.kt`, `web/src/app/features/settings/mail/identities.html`. Excluded: test files; `api/openapi.yaml` (generated contract, checked by `OpenApiContractTest`); `web/src/app/api/generated/**` (5 generated files with doc comment changes only, checked by `api:check`); `reviews/**`. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: server JaCoCo (Gradle `jacoco` plugin, Gradle 9.8.1), dir `server/`: (a) `./gradlew test jacocoTestReport`, full `test` task; (b) the integrationTest command above, 32 compose external tests. Web Vitest + `@vitest/coverage-v8`, dir `web/`: (c) `npm run coverage`, all 175 spec files.
- Run start/completion time and exit status: (a) 08:30:52 -> 08:38:21, exit 0; (b) 08:27:53 -> 08:30:23, exit 0; (c) 08:30:56 -> 08:32:09, exit 0.
- Generated report paths and report hashes/run identifier: (a) `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 1cc26ccbad1b6daf8e40b461ebd0e90c16a8647e69566c2154b8cae5678df33f (written 08:38:07). (b) `server/build/tmp-itcov/report.xml` sha256 32f66b48f6054c36625ab915732c5be7bb285201b76744204fa28e0fb58a9d43 (08:30:18), exec `server/build/jacoco/integrationTest.exec` sha256 6527b5cdd069875a8687f2894f9c4bd6cce78dccc6f7c81420f953fc2deeba39 (08:30:17). (c) `web/coverage/web/coverage-summary.json` sha256 86bcfa2a98b360ba3a8c86fb36a1ac93097ae48264874e882623eb7706b9a48c, `coverage-final.json` sha256 5fd953c45282fca8fb681c70daafe259fa2cfeddb0b143f93796b54d77e3521c (08:32:04).
- Evidence outputs are fresh and inputs stayed unchanged: yes. Old outputs were deleted before each run, file times fall inside the runs, and the snapshot hashes match after the runs.
- Final run after latest test additions and production fixes: yes. The runs measure HEAD `42c33370d`, which includes the CR-1/CR-2 fixes.

Values below use covered/total (percentage). Server Statements = JaCoCo instructions, Functions = methods.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `DraftService.kt`, (b) integrationTest compose suites | 2509/2897 (86.6%) | 177/260 (68.1%) | 87/89 (97.8%) | 336/354 (94.9%) |
| `DraftService.kt`, (a) documented `test jacocoTestReport` | 453/2897 (15.6%) | 31/260 (11.9%) | 36/89 (40.4%) | 73/354 (20.6%) |
| `DraftController.kt`, (b) | 1064/1239 (85.9%) | 46/76 (60.5%) | 57/74 (77.0%) | 158/170 (92.9%) |
| `DraftController.kt`, (a) | 1141/1239 (92.1%) | 66/76 (86.8%) | 59/74 (79.7%) | 168/170 (98.8%) |
| `identities.html`, (c) | 183/183 (100%) | 28/28 (100%) | 8/8 (100%) | 116/116 (100%) |

No cross-provider aggregate: JaCoCo and v8 counters measure different things, and runs (a) and (b) cover the same server files from different test sets. All three in-scope files were measured; there are no unmeasured files. `DraftService.identity` in (b): instructions 29/36, branches 3/4, lines 3/3.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `DraftService.kt:444` (1 of 2 branches: the `throw` for an identityId that is not the account's), run (b) | Validation of an explicit identityId. Existed before the task and is not changed by it | No test added. Pre-existing behavior outside the criterion. Run (a) covers 1/2 branches of line 444 through Spring tests. |
| `DraftService.kt:340-342` (blank name -> null personal) and other partial branches (attachments, size limits, locking: e.g. 159, 486-487, 502-503, 515-518, 584-595, 694) | Other draft operations, not changed by this task | Not in the task's changed lines. No test added. |
| `DraftController.kt` uncovered methods/branches | Endpoints not changed by the task (only description strings changed) | Not in task scope. No test added. |

## Unable to test

Nothing in scope. The documented server coverage command cannot see the GreenMail tests, so integration coverage needed the Git-ignored init script described above. Optional follow-up for the project: have `jacocoTestReport` (or a sibling task) include `integrationTest.exec`.

## Summary

Tests and task-scoped coverage are sufficient; no production defects found. On the final code (HEAD `42c33370d`), the server tests for the criterion (new draft without a default identity -> account name + address; with a default -> that identity; also for reply/forward) and the updated settings hint spec pass. The one changed executable server line is covered on both branches, and every changed template statement is covered.
