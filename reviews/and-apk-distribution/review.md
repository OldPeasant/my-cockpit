# Code Review

Date: 2026-10-25

Task: and-apk-distribution - APK build in CI and download from the server

Scope: Task commits `9579d2a07..HEAD` (ff3360e39 implementation, 1ade75589 tests): `.github/workflows/ci.yml` (job `android-apk`), `android/app/build.gradle.kts` (signing from env, debug fallback, `-PcockpitVersionCode`), `server/.../core/download/{api,infra}`, `SecurityConfiguration` (`/download/**`), `deploy/README.md` "Android app", regenerated `api/openapi.yaml` and `web/src/app/api/generated/` (sanity only).

Requirements:
- `tasks/and-apk-distribution.md` acceptance criteria and recorded assumption (version from `output-metadata.json`, `X-Cockpit-App-Version`, `/download/android/version`, `github.run_number` as versionCode)
- SPEC.md §11 (APK built by CI, direct download from the server), §4.1/§4.2 (login, Problem Details)
- docs/ARCHITECTURE.md (runtime versions in CI, conventions), AGENTS.md project notes
- Parent's reported runs: server `ktlintCheck test --tests 'ch.sonensei.cockpit.core.download.*' jacocoTestReport` -> 45 tests, 0 failures; android `ktlintCheck assembleRelease -PcockpitVersionCode=7` -> built, debug-signed release, versionCode 7

Git range:
- Branch: main
- HEAD: 1ade75589
- Base/start: 9579d2a07f72a0b32fed7dcf954c0d35f1aa754e
- Commits reviewed: 2

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-1 | Unknown paths under `/download/` fall back to the SPA `index.html`**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/core/spa/SpaWebConfiguration.kt:53`
    - Category: Integration
    - Problem: The task made `/download/` a server prefix (secured with `/api/**` and `/actuator/**` in `SecurityConfiguration.kt:73`), but `SpaFallbackResourceResolver.SERVER_PREFIXES` is still only `api` and `actuator`. A logged-in request to an unmapped path such as `/download/ios` or `/download/android/latest` therefore matches the `/**` resource handler, `isClientRoute("download/...")` is true, and the server answers 200 with `index.html` rather than 404.
    - Why it matters: The SPA config says server paths "never fall back: an unknown API path stays a 404 instead of HTML". A client that asks for a wrong download URL gets an HTML page with status 200, which is hard to spot, and an Android update check that parses it fails without a clear cause.
    - Suggested fix: Add `download` to `SERVER_PREFIXES` (and update its KDoc), and add a web test that an unknown `/download/...` path returns 404 for a logged-in user. `SpaWebConfiguration.kt` is outside the task's `touches`, so the parent may decide to defer this. The change is one line.

## Review conclusion

No material issues found.

Notes checked without findings:
- CI job `android-apk` uses the same JDK (`env.JAVA_VERSION`) and Android SDK packages as the existing `android` job. When `ANDROID_KEYSTORE_BASE64` is empty (fork PRs, no secrets) it skips the keystore step, and Gradle falls back to the debug signing config. The artifact contains `cockpit.apk` and `output-metadata.json` with `if-no-files-found: error`. The keystore is decoded into `$RUNNER_TEMP`, and secrets reach Gradle only through env vars.
- The Gradle signing config is created only when `COCKPIT_KEYSTORE_FILE` is set and not blank. `cockpitVersionCode` defaults to 1.
- The server reads the APK and metadata on each request. A missing, broken, empty or mistyped metadata file means the version is unknown, not an error. A file that disappears between check and read gives 404 (Problem Details through `NotFoundException`). The file name is restricted to a safe version pattern. `HttpEntityMethodProcessor` handles ETag and Last-Modified conditional requests (tests cover 304), and range requests are covered too.
- Security: `/download/**` uses the same `fullAccess` rule as the API. Tests cover anonymous requests (401 for GET, HEAD and `/version`, no leak) and a TOTP-enrolling session (403).
- The OpenAPI and generated client match the controller annotations. `AndroidAppVersion` marks `size` and `updatedAt` as required, and the version fields are nullable.
- Criterion 3: the web tests extend `PostgresTestBase` (Testcontainers). The parent reported 45 passing tests.
- Evidence: no criterion is recorded yet (`tasks.py verify` shows all unticked). Criterion 1 requires the GitHub Actions run, and the keystore-signed path, which the parent could not observe. It must be skipped with a reason or verified on a real CI run, not ticked from the local `assembleRelease` alone.
