# Test Report

Date: 2026-10-25

Task: and-apk-distribution - APK build in CI and download from the server

Scope: `git diff 9579d2a07f72a0b32fed7dcf954c0d35f1aa754e..ff3360e3916efd24a0ddb407050e9be28712cfc1` (base supplied by the parent worker, single task commit ff3360e39 "and-apk-distribution: release APK in CI, /download/android on the server"; HEAD = ff3360e39 on `main` at the time of writing, plus the three untracked test files listed below).

Requirements:
- `tasks/and-apk-distribution.md` acceptance criteria and its recorded assumption (version from AGP `output-metadata.json`, `X-Cockpit-App-Version` header, `GET /download/android/version` JSON)
- SPEC.md §11 (distribution without the Play Store), via the task goal
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`
- Parent instructions: add tests only in `server/src/test/kotlin/ch/sonensei/cockpit/core/download/`; this sandbox cannot run Gradle

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/core/download/AndroidApkStoreTests.kt` (new, plain JUnit + `@TempDir`, no Spring)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/download/AndroidDownloadControllerTests.kt` (new, plain JUnit)
- `server/src/test/kotlin/ch/sonensei/cockpit/core/download/AndroidDownloadEdgeCaseWebTests.kt` (new, `@SpringBootTest` + MockMvc + `PostgresTestBase`, same setup as the existing `AndroidDownloadWebTests`)

The existing `AndroidDownloadWebTests.kt` (7 tests) was not modified.

## Tests added or updated

Count: 38 (21 in `AndroidApkStoreTests`, 6 in `AndroidDownloadControllerTests`, 11 in `AndroidDownloadEdgeCaseWebTests`)

## Requirement coverage

All "Not run" entries below have the same reason: the test-writer sandbox cannot run Gradle (known limitation); the parent worker must run the command and record the result.

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: CI builds a signed release APK (keystore from CI secrets, debug-signed when absent) and attaches it as an artifact | none (CI workflow and Gradle signing config, not unit-testable) | parent ran `cd android && ./gradlew assembleRelease -PcockpitVersionCode=7` (no keystore) | Not run by test-writer: needs a GitHub Actions run; keystore path not exercised locally (keytool/openssl blocked). Parent reports the debug-signed fallback built, `signingReport` shows release -> debug, `output-metadata.json` has versionCode 7 |
| AC2: serves `${COCKPIT_DATA_DIR}/android/cockpit.apk` at `/download/android` | `AndroidDownloadWebTests.serves the APK with its version to a logged-in user`; `AndroidApkStoreTests.looks in the android folder of the data dir`, `normalizes a data dir with dot segments`, `reports path, size and modification time of the APK`, `an empty APK file is still an APK of size 0` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.download.*'` | Not run: no Gradle in sandbox |
| AC2: authenticated | `AndroidDownloadWebTests.needs a login`; `AndroidDownloadEdgeCaseWebTests.a session that must enroll TOTP again gets 403`, `a 403 for an enrolling session does not leak the version`, `an anonymous request does not leak the version or the APK` | same | Not run: no Gradle in sandbox |
| AC2: with version info (header + JSON, from `output-metadata.json`) | `AndroidDownloadWebTests.reports the version as JSON`; `AndroidApkStoreTests.reads versionName and versionCode from the metadata`, `reads a versionCode beyond the Int range`, `uses the first element when the metadata lists several`, `keeps one field when the other is missing`; `AndroidDownloadEdgeCaseWebTests.HEAD answers with the headers of the download` | same | Not run: no Gradle in sandbox |
| AC2: missing, broken or odd metadata never breaks the download (version unknown) | `AndroidDownloadWebTests.serves the APK without version when the metadata is missing or broken`; `AndroidApkStoreTests.has no version without metadata`, `has no version when the metadata is a directory`, `has no version when the metadata has no elements`, `has no version when elements is empty or not an array`, `has no version when the metadata is not an object`, `has no version when the metadata is broken JSON`, `has no version when the metadata is still empty while it is copied`, `ignores a versionName that is not a string but keeps the versionCode`, `ignores a versionCode that is not an integer but keeps the versionName` | same | Not run: no Gradle in sandbox |
| AC2: no APK -> 404 Problem Details | `AndroidDownloadWebTests.answers 404 problem details when no APK is deployed`; `AndroidApkStoreTests.has no APK when the android folder does not exist`, `has no APK when the folder exists but the APK does not`, `has no APK when cockpit apk is a directory`; `AndroidDownloadEdgeCaseWebTests.a removed APK answers 404 again`, `metadata without an APK still answers 404` | same | Not run: no Gradle in sandbox |
| Attachment file name `cockpit-VERSION.apk`, unsafe versions dropped | `AndroidDownloadWebTests.keeps an unsafe version out of the file name`; `AndroidDownloadControllerTests.the file name carries a safe version`, `the file name is cockpit apk without a version`, `the file name drops a version that is unsafe in a file name or header`, `the file name allows a version of at most 64 characters` | same | Not run: no Gradle in sandbox |
| Validators (ETag, Last-Modified) and caching | `AndroidDownloadWebTests.answers 304 when the APK did not change`; `AndroidDownloadControllerTests.the ETag is a weak validator from size and modification time in hex`, `the ETag changes when size or modification time change, not with the version`; `AndroidDownloadEdgeCaseWebTests.the download may only be cached privately and must be revalidated`, `answers 304 to If-Modified-Since the deployment`, `a replaced APK is downloaded again despite the old ETag` | same | Not run: no Gradle in sandbox |
| Replacing the files takes effect without a restart (store KDoc) | `AndroidApkStoreTests.reads both files again on every call, so a new deployment needs no restart`; `AndroidDownloadEdgeCaseWebTests.a replaced APK is downloaded again despite the old ETag` | same | Not run: no Gradle in sandbox |
| Byte ranges (resumable download; not an AC, Spring MVC behavior for `Resource` bodies) | `AndroidDownloadEdgeCaseWebTests.serves a byte range, so an interrupted download can resume`, `answers 416 to a range beyond the end of the APK` | same | Not run: no Gradle in sandbox |
| AC3: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.download.*'` passes | all 45 tests in the package (7 existing + 38 new) | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.download.*'` | Not run by test-writer: no Gradle in sandbox. Parent reported the 7 existing tests pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.download.*'`

Result:

Not run: the test-writer sandbox cannot run Gradle (known limitation). The parent worker must run it. The new test classes are:
- `ch.sonensei.cockpit.core.download.AndroidApkStoreTests`
- `ch.sonensei.cockpit.core.download.AndroidDownloadControllerTests`
- `ch.sonensei.cockpit.core.download.AndroidDownloadEdgeCaseWebTests`

The new test files are not checked by ktlint yet either. The parent should also run `cd server && ./gradlew ktlintCheck` (or `./gradlew check`).

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Not run: no Gradle in sandbox. Left to the parent worker.

## .taskcheck

Result:

Not run: `sh scripts/check.sh` runs Gradle, which this sandbox cannot do. Left to the parent worker.

## Bugs found

None confirmed. No test has run yet, so this is unverified. If any of the new tests fails when the parent runs them, it needs a look before anyone treats it as a test bug. These are the likeliest to fail, with what a failure would mean:

- `AndroidApkStoreTests.has no version when the metadata is still empty while it is copied`: if Jackson 3 `readTree(File)` gave Kotlin `null` for an empty file (and not `MissingNode` or a `JacksonException`), `AndroidApkStore.readVersion` (`server/src/main/kotlin/ch/sonensei/cockpit/core/download/infra/AndroidApkStore.kt`, `jsonMapper.readTree(metadata.toFile())["elements"]`) would throw an NPE. `GET /download/android` would then answer 500 while CI copies the metadata, and not serve the APK without a version. That would be a production defect (Medium).
- `AndroidDownloadEdgeCaseWebTests.serves a byte range ...` / `answers 416 ...`: these depend on Spring MVC's built-in Range support for `ResponseEntity` of a `Resource`. Ranges are not an acceptance criterion. A failure here is a missing feature, Low, for the parent to decide on. It is not a requirement violation.

## Coverage

Status: unavailable. The test-writer sandbox cannot run Gradle/JaCoCo. The parent worker will supply the task-scoped numbers in `quality.json` from a fresh run made after these tests were added.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `and-apk-distribution`; base `9579d2a07f72a0b32fed7dcf954c0d35f1aa754e` (from the parent); HEAD `ff3360e3916efd24a0ddb407050e9be28712cfc1`; the one task commit is `ff3360e39` (from the parent, checked against `git diff --stat`)
- Source/test snapshot identifier (including uncommitted inputs): committed `ff3360e39` plus untracked test files with these sha256 hashes: `AndroidApkStoreTests.kt` 9aab3eecf988d29b27831e9a41e3bb41941be8bb1c86050369383760202e26be, `AndroidDownloadControllerTests.kt` 55cf2562f918c8b434e8e8ad533d0185a77921d971a09b88aa159f4768014e6f, `AndroidDownloadEdgeCaseWebTests.kt` 4e940162d6bb6b51796442a88cc27aed0867ca839f00de0854e019ea8e258808
- Exact included files; renamed/deleted/excluded files and reasons: in scope are `server/src/main/kotlin/ch/sonensei/cockpit/core/download/infra/AndroidApkStore.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/core/download/api/AndroidDownloadController.kt` and `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/SecurityConfiguration.kt` (a one-matcher change; whole-file numbers include pre-existing auth code). Excluded: `.github/workflows/ci.yml` and `android/app/build.gradle.kts` (build/CI configuration, no JaCoCo-instrumentable code), `api/openapi.yaml` and `web/src/app/api/generated/**` (generated), `deploy/README.md` (documentation). No renames or deletions.
- Provider/version, command, working directory, and test selection: planned is JaCoCo (Gradle `jacoco` plugin) via `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.download.*' jacocoTestReport`, report `server/build/reports/jacoco/test/jacocoTestReport.xml`. Not executed by the test-writer.
- Run start/completion time and exit status: none (not run)
- Generated report paths and report hashes/run identifier: none (not run)
- Evidence outputs are fresh and inputs stayed unchanged: not applicable. No measurement was made, and no earlier coverage output was used.
- Final run after latest test additions and production fixes: no. The parent must run it after the snapshot above.

Values below use covered/total (percentage). Zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/core/download/infra/AndroidApkStore.kt` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/download/api/AndroidDownloadController.kt` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |
| `server/src/main/kotlin/ch/sonensei/cockpit/core/auth/infra/SecurityConfiguration.kt` | N/A (not measured) | N/A (not measured) | N/A (not measured) | N/A (not measured) |

No aggregate is reported because nothing was measured. JaCoCo reports INSTRUCTION, not statements; the parent should record INSTRUCTION/BRANCH/METHOD/LINE counters from the `sourcefile` elements `AndroidApkStore.kt` and `AndroidDownloadController.kt` in package `ch/sonensei/cockpit/core/download/...`, and `SecurityConfiguration.kt` in `ch/sonensei/cockpit/core/auth/infra`. Kotlin JaCoCo also counts the generated members of `AndroidApk` / `AndroidAppVersion` data classes (`copy`, `componentN`, `hashCode`, `toString`), so method coverage there will be lower without a real gap.

Expected gaps from code inspection, for the parent to check against the real report:

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `AndroidApkStore.kt` `current()`, the `catch (_: IOException) { return null }` branch | APK deleted between `isRegularFile` and `Files.size` (race) | Justified gap. It can't be triggered deterministically without mocking the file system, and the fallback is the same 404 the "no APK" tests already cover |
| `AndroidApkStore.kt` `readVersion()`, the `JacksonException` catch | broken metadata is logged and ignored | Covered by `has no version when the metadata is broken JSON` and the existing web test |
| `AndroidDownloadController.kt` `download()`, the `versionName?.let` header branch | version header only when known | Both branches covered (with and without metadata) |
| `SecurityConfiguration.kt` `/download/**` matcher | login and full access needed | Covered for anonymous (401), TOTP-enrollment session (403) and owner (200) |

## Unable to test

- AC1 (CI builds a signed release APK and uploads artifact `cockpit-android`): needs a GitHub Actions run with and without the `ANDROID_KEYSTORE_*` secrets. The test-writer can't run CI or `gh`. The parent verified the debug-signed fallback locally. The keystore-signed path is unverified (keytool/openssl blocked) and needs a follow-up on the first CI run with secrets.
- No test was executed by the test-writer, since Gradle is unavailable in this sandbox. The parent must run the three new classes and report back any failure.
- Bearer device-token access to `/download/android` was not tested separately. It goes through the same `fullAccess` matcher as `/api/**`, which `core/auth` tests already cover.

## Summary

Tests are written (38 new across 3 classes) but none has run yet, so meaningful task behavior is still unverified. The parent must run `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.download.*' jacocoTestReport` and `./gradlew ktlintCheck`, then record coverage in `quality.json`. No production defects are confirmed yet. AC1's keystore-signed CI path needs a follow-up on a real CI run.
