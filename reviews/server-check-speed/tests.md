# Test Report

Date: 2026-11-08

Task: server-check-speed - Server check: make .taskcheck's server tests fast again

Scope: `git diff bb487db914cd59cc0dcd661523f992d6f3ef6ca4..1c9fce8f3a731217b6f37091b54d4c5b73428b8f` on branch main (tested revision: HEAD 1c9fce8f3a731217b6f37091b54d4c5b73428b8f). Task code commits: 0a50db551, bdcf9832b, bec5c06be, fc2b8f257 (tests), f5ba05c16 (CR-2 fix; rebased copy of fab402e79, identical code: `git diff fab402e79 f5ba05c16` touches only `tasks/`). Other commits in the range are board commits (`tasks/` only) or reports (`reviews/`). No uncommitted changes.

This report replaces the previous one (written for revision 568abb8b0). The only code change since then is the CR-2 fix: `server/build.gradle.kts` deletes `build/test-data` in `doFirst` of every `Test` task, plus comments and a `docs/ARCHITECTURE.md` note.

Requirements:
- tasks/server-check-speed.md acceptance criteria (faster server `test`, no flakiness, CI still runs every server test, coverage that matters kept)
- AGENTS.md project notes, docs/ARCHITECTURE.md "Testing"/"Coverage" (as changed by the task)
- reviews/server-check-speed/review.md finding CR-2 and reviews/server-check-speed/fixes.md
- Parent instructions: production crypto parameters stay covered, production code changes tested, order-independent tests really are, re-assess the CR-2 build-script change

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupSecretsCryptoTests.kt` (committed in fc2b8f257)
- `server/src/test/kotlin/ch/sonensei/cockpit/backup/BackupWorkDirCleanupTests.kt` (committed in fc2b8f257)

No test files changed in this re-assessment.

## Tests added or updated

Count: 3 added by this helper in the earlier pass (0 added for the CR-2 change; reason below)

- `BackupSecretsCryptoTests`: `a freshly loaded BackupSecrets encrypts with the default KDF, so production is not lowered like Spring tests`
- `BackupWorkDirCleanupTests`: `without a temp root the backup service works in the JVM temp dir and deletes its work dir there`
- `BackupWorkDirCleanupTests`: `without a temp root the restore service works in the JVM temp dir and deletes its work dir there`

Why these gaps were real:

1. The task changed `BackupSecrets.encrypt`'s default from `DEFAULT_KDF` to the mutable `encryptionKdf`. The adjusted test `the default KDF is 64 MiB, 3 passes, one lane and is what encrypt uses` sets `encryptionKdf = DEFAULT_KDF` itself, so it would still pass if the production initializer were weakened. Reading the field is unreliable because `FastTestCrypto` lowers it in any test JVM that created a Spring context. The new test loads a fresh `BackupSecrets` in an isolated `URLClassLoader`, checks that it came from that loader, and asserts `DEFAULT_KDF` and the initial `encryptionKdf` are `Kdf(memoryKiB=65536, iterations=3, parallelism=1)`. Deterministic and fork-independent.
2. `BackupJobService.tempRoot` / `RestoreService.tempRoot` were added and `BackupWorkDirCleanupTests` always sets `tempRoot`, so no test asserted the production path (`tempRoot == null`): work dir in the JVM temp dir, deleted on `destroy()`. The new tests identify their own work dir without diffing the shared temp dir (backup via `zip(id).parent`, restore via the dir containing the upload's unique `{reportId}.zip`), so concurrent forks cannot affect them.

### CR-2 change (build script): no test added

The fix adds `testDataDir.get().asFile.deleteRecursively()` to `doFirst` of the `configureEach` block for all `Test` tasks in `server/build.gradle.kts`. No automated test was added, because:

- It is build logic inside the project's own build script, not production code. The project has no build-logic test setup (no `buildSrc`/included build with Gradle TestKit tests); adding one would be new test infrastructure, outside this helper's authority and the task's scope.
- A test in the server's JUnit suite cannot observe it meaningfully: the deletion runs in the Gradle daemon before the test JVMs start, so a test could only see that its own fork folder exists, not that folders of earlier runs were removed.
- It is a two-line, directly observable behavior that the parent verified end-to-end in the real configuration (below): after each of 3 consecutive `./gradlew --quiet cleanTest check` runs, `server/build/test-data` contained only that run's 4 fork folders (run1: fork-43..46, run2: fork-47..50, run3: fork-51..54). Without the fix the earlier runs' folders would have remained. That is the behavior CR-2 asked for.
- Safety by inspection: the delete happens before any test JVM of that task starts; `build/test-data` is only used as `cockpit.data-dir` by test JVMs (test `config/application.properties`), and every run starts with fresh Postgres containers, so no test depends on data from an earlier run. The 3 green full runs confirm no test regressed. Two `Test` tasks running concurrently in one build could delete each other's data, but the server build has only the `test` task of type `Test` running in `check` (noted, not a defect).

## Review of the other task test changes (no change needed)

- `FastTestCryptoTests`: covers the production password encoder's parameters (`m=16384,t=2,p=1`, Argon2id) independently of Spring, and that fast hashes/envelopes are accepted by production verification/decryption. Adequate.
- `BackupSecretsCryptoTests` (task change): restores the previous `encryptionKdf` in `finally`; no JUnit in-fork parallelism, so the temporary change cannot leak into a concurrently running test.
- `BackupWorkDirCleanupTests` (task change): listings use the test's own `@TempDir`; every service in the class (except the two production-path tests) gets `tempRoot = tmp`. Order- and fork-independent.
- `GoogleContactsSyncTests`: `reset()` also runs `@AfterEach`, so trashed contacts no longer leak into other classes. Order-independent.
- `HabitEdgeCaseTests`: `reset()` runs before and after each test and deletes every reminder, so the `fireDue()` counts no longer depend on other classes. Order-independent.
- `config/application.properties`: `cockpit.data-dir=build/test-data/fork-${org.gradle.test.worker:0}` separates file blobs per fork; together with the CR-2 fix old fork folders are removed at the start of each run.

## Requirement coverage

All results below come from runs by the parent worker on the final code (HEAD code = fab402e79 code); this helper cannot run Gradle. This helper checked the copied JUnit XML in `.tmp/run1-results` and `.tmp/run3-results` for the focused classes.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| Production crypto parameters stay covered (backup KDF) | `BackupSecretsCryptoTests` (15 tests, incl. the fresh-classloader test), `FastTestCryptoTests` backup KDF test | `cd server && ./gradlew --quiet cleanTest check` (parent, 3 runs) | Pass (run1 XML: BackupSecretsCryptoTests 15/0 failures/0 errors/0 skipped; FastTestCryptoTests 3/0/0/0) |
| Production password encoder parameters stay covered | `FastTestCryptoTests` | same | Pass |
| `tempRoot` set: work dirs in the given root, cleaned on destroy | `BackupWorkDirCleanupTests` (existing, task-adjusted) | same | Pass (run1 and run3 XML: 12 tests, 0 failures/errors/skipped) |
| `tempRoot` null (production default): work dirs in JVM temp dir, cleaned on destroy | 2 `without a temp root ...` tests | same | Pass (present and green in run1, run2 and run3 XML) |
| Order-independent tests and no flakiness | whole server suite with parallel forks | `cd server && ./gradlew --quiet cleanTest check` three times in a row (parent) | Pass: rc=0 each, 5211 tests, 0 failures each |
| Server `test` much faster | whole server suite | same command, baseline main vs task code back to back (parent) | Pass: 536 s baseline vs 242 s task code; the 3 runs took 237 s / 270 s / 256 s |
| CR-2: old per-fork data folders do not pile up | build-script behavior (no unit test, see above) | parent inspected `server/build/test-data` after each of the 3 runs | Pass: only that run's 4 fork folders present each time |
| CI still runs every server test | `.github/workflows/ci.yml` | needs a GitHub Actions run | Not run: no CI access from this helper |
| `.taskcheck` passes | all parts | `sh scripts/check.sh` | Not run by this helper: the parent is running it now |

## Focused test execution

Command:

`cd server && ./gradlew --quiet cleanTest check` (run by the parent; covers the focused classes `BackupSecretsCryptoTests`, `BackupWorkDirCleanupTests`, `FastTestCryptoTests`)

Result:

Pass (focused classes: 0 failures, 0 errors, 0 skipped in the JUnit XML checked by this helper)

## Full test execution

Command:

`cd server && ./gradlew --quiet cleanTest check` (3 consecutive runs by the parent on the final code)

Result:

Pass: rc=0 each, 5211 tests, 0 failures each; 237 s / 270 s / 256 s wall.

## .taskcheck

Result:

Not run by this helper (Gradle is denied to sub-agents; `scripts/check.sh` is being run by the parent).

## Bugs found

None. Note for the parent (not a defect): `tempRoot` only takes effect when set before the first use of the work dir (read by the lazy initializer); all current callers set it right after construction.

## Coverage

Status: verified - measured by the parent worker on the final code (Gradle is denied to this helper); this helper re-read the per-file counters directly from the generated JaCoCo XML and they match the parent's figures. The CR-2 change is in `server/build.gradle.kts`, which JaCoCo does not instrument, so it adds nothing to the coverage scope (its behavior was verified at runtime by the 3 `cleanTest check` runs).

Metric: whole-file coverage of task-changed production files (JaCoCo counters: INSTRUCTION as statements, BRANCH, METHOD as functions, LINE).
Changed-line coverage: measured by the parent (JaCoCo line elements intersected with the task diff, parsed with `.tmp/cov.py`): task-changed executable lines `BackupSecrets.kt` 57-60 and 82, `BackupJobService.kt` 49-56, `RestoreService.kt` 70-77 are all covered, with no partial branches on them, including both sides of the `tempRoot?.let { ... } ?: ...` initializers at `BackupJobService.kt:54` and `RestoreService.kt:75`. Not re-derived line by line by this helper.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: server-check-speed; base bb487db914cd59cc0dcd661523f992d6f3ef6ca4 (from the parent); HEAD 1c9fce8f3a731217b6f37091b54d4c5b73428b8f; task commits from `git log bb487db914cd..HEAD` (0a50db551, bdcf9832b, bec5c06be, fc2b8f257, f5ba05c16) and `git diff --stat` of the code paths.
- Source/test snapshot identifier (including uncommitted inputs): HEAD 1c9fce8f3, clean tree. Blob ids: `server/build.gradle.kts` 4e7444c37650f0a0a420c429b60ba9ee2bfb73c9, `BackupSecretsCryptoTests.kt` f4c9931ce07b37079e415bf178938fa44f1c7cc3, `BackupWorkDirCleanupTests.kt` 2ebf6b049f462e940a1dce2c1dfe2aa695c89d64.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupSecrets.kt`, `.../backup/domain/BackupJobService.kt`, `.../backup/domain/RestoreService.kt`. Excluded: `server/build.gradle.kts` (build script, not instrumented by JaCoCo; CR-2 change verified by the parent's runs instead), `docs/ARCHITECTURE.md` (documentation), all `server/src/test/**` files (tests and test support). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo (Gradle `jacoco` plugin as configured); `server/gradlew -p server --quiet test jacocoTestReport` run by the parent from the clone root; full server `test` selection.
- Run start/completion time and exit status: exit 0, all tests green (parent); report written 2026-11-08 19:45:34 +0100.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`, sha256 6a253dafec3d6f7fbb6a23456bd34a89b54cc03ac215f390c95d8566d0aa594e.
- Evidence outputs are fresh and inputs stayed unchanged: the report postdates the last code commit (CR-2 fix, 19:24) and was generated on the final code per the parent; tree clean at HEAD 1c9fce8f3.
- Final run after latest test additions and production fixes: yes.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupSecrets.kt` | 491/491 (100.0%) | 40/40 (100.0%) | 28/28 (100.0%) | 67/67 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/BackupJobService.kt` | 680/709 (95.9%) | 24/26 (92.3%) | 37/37 (100.0%) | 85/85 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/backup/domain/RestoreService.kt` | 911/975 (93.4%) | 52/60 (86.7%) | 37/38 (97.4%) | 141/149 (94.6%) |
| Aggregate (summed counters, all 3 scoped files measured; excluded: `server/build.gradle.kts`, docs, tests) | 2082/2175 (95.7%) | 116/126 (92.1%) | 102/103 (99.0%) | 293/301 (97.3%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `BackupJobService.kt`: partial branches at lines 116 and 167 | Pre-existing code, not changed by this task | Outside task scope; no test added |
| `RestoreService.kt`: missed lines 183, 185, 235-237, 248, 250, 258; partial branches at 139, 203, 209, 234, 245, 255, 269 | Pre-existing restore code, not changed by this task | Outside task scope; no test added |
| `BackupSecrets.kt`: `encryptionKdf` initial value (production default) | Production must encrypt with 64 MiB / 3 passes | Added fresh-classloader test (green in all 3 runs) |
| `BackupJobService.kt` / `RestoreService.kt`: `tempRoot == null` branch | Production work dirs in the JVM temp dir must be deleted on destroy | Added 2 tests (green in all 3 runs) |
| `server/build.gradle.kts`: `doFirst` delete of `build/test-data` (CR-2) | Old per-fork data folders must not pile up | Not instrumentable by JaCoCo, no build-logic test setup; verified by the parent's inspection after 3 runs |

## Unable to test

- Running tests, lint and coverage myself: Gradle is denied to sub-agents; results above come from the parent's runs (coverage counters cross-checked against the XML by this helper). No separate task needed.
- CR-2 build-script behavior as an automated test: no Gradle TestKit/build-logic test setup exists; verified manually by the parent instead (see above). No follow-up needed.
- CI running every server test (`.github/workflows/ci.yml`): needs a CI run; not executable here.

## Summary

Tests and task behavior are sufficiently tested; no production defects found. The CR-2 build-script change needs no automated test (no build-logic test setup; verified end-to-end by the parent's 3 green `cleanTest check` runs and the `build/test-data` inspection). Task-scoped JaCoCo coverage on the final code: lines 293/301, branches 116/126 across the three changed files; every task-changed line is covered, and the remaining gaps are in pre-existing code outside the task.
