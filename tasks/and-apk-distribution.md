---
title: APK build in CI and download from the server
status: done
owner: dell26/agent-1
deps: [and-login,p0-ci,p0-deploy,ci-split-check]
touches: [.github,server/src/main/kotlin/ch/sonensei/cockpit/core/download,server/src/test/kotlin/ch/sonensei/cockpit/core/download]
prio: 2
lease_until: 
---
## Goal
Distribute the app without the Play Store (SPEC.md §11).

## Acceptance criteria
- [~] CI builds a signed release APK (keystore from CI secrets; debug-signed when absent) and attaches it as an artifact
  - skipped (2026-10-25 23:28+0100 by dell26/agent-1): No GitHub Actions run observable here (gh CLI not permitted) and keytool/openssl blocked, so the keystore-signed path could not be built locally. Partial local evidence: cd android && ./gradlew ktlintCheck assembleRelease -PcockpitVersionCode=7 -> app-release.apk + output-metadata.json (versionCode 7); ./gradlew :app:signingReport -> release variant Config: debug (fallback when COCKPIT_KEYSTORE_FILE unset)
- [x] Server serves `${COCKPIT_DATA_DIR}/android/cockpit.apk` at `/download/android` (authenticated) with version info; test
  - verified (2026-10-25 23:28+0100 by dell26/agent-1): cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.download.*' -> AndroidDownloadWebTests 7/7 + AndroidDownloadEdgeCaseWebTests 11/11 pass: GET /download/android serves data-dir/android/cockpit.apk with X-Cockpit-App-Version, attachment name, ETag/304, ranges; /download/android/version JSON; 401 anonymous, 403 TOTP-enrollment session, 404 when absent
- [x] `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.core.download.*'` passes (Postgres via Testcontainers where persistence is involved)
  - verified (2026-10-25 23:28+0100 by dell26/agent-1): cd server && ./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.download.*' jacocoTestReport -> 45 tests, 0 failures (web tests on Postgres via Testcontainers PostgresTestBase); coverage download/api 36/36 lines, infra 28/30 lines

## Notes
- ASSUMPTION (2026-10-25 23:20+0100 by dell26/agent-1): Version info comes from AGP's output-metadata.json, shipped with cockpit.apk in the CI artifact and copied next to it in COCKPIT_DATA_DIR/android; GET /download/android sends X-Cockpit-App-Version and GET /download/android/version returns JSON. CI passes github.run_number as versionCode so sideloaded updates install. Small edits outside touches were needed: SecurityConfiguration (/download/** needs login), android/app/build.gradle.kts (signing), deploy/README.md, api/openapi.yaml + web generated client.
- ASSUMPTION (2026-10-25 23:28+0100 by dell26/agent-1): CI keystore-signed APK build not observed (no gh access, keytool blocked locally); assumed working since the Gradle signing config only reads COCKPIT_KEYSTORE_* env vars set by the android-apk job. Review finding CR-1 (unknown /download/* paths get SPA index.html for logged-in users) deferred: SpaWebConfiguration is outside this task's touches and the paths still need login.
- done by dell26/agent-1: `sh scripts/check.sh` passed in 66s on 9906e0b5d; criteria verified 2/3, skipped #1
