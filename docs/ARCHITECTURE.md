# Architecture & developer guide

What to build is in `SPEC.md`. This file covers how the repository is built, run and tested.
How agents work is in `AGENTS.md`, and the review evidence contract is in `docs/QUALITY.md`.

## Stack (pinned versions)

| Part | Folder | Stack |
|---|---|---|
| Server | `server/` | Kotlin 2.3.21, Spring Boot 4.1.1 (Web MVC, Actuator, Validation), JDK 21 (Gradle toolchain), Gradle 9.8.1 wrapper, ktlint (Gradle plugin 14.2.0), JUnit 5, JaCoCo |
| Web | `web/` | Angular 22.2 (standalone, signals, zoneless), TypeScript 6.0, Vitest 5 via `@angular/build:unit-test` + jsdom, angular-eslint, `@vitest/coverage-v8` |
| Android | `android/` | AGP 9.4.1 (built-in Kotlin), Kotlin 2.3.21 + Compose compiler plugin, Compose BOM 2026.09.00, Material 3, compileSdk 37 / targetSdk 36 / minSdk 29, JDK 21, Gradle 9.8.1 wrapper, ktlint, Android Lint, JUnit 4, JaCoCo |
| Tooling | root | TFP (`tasks.py`, `agents.py`), Python 3.10+ |

The skeletons are deliberately minimal. Tasks add PostgreSQL, Flyway, Spring Security,
Testcontainers, Angular Material, Hilt, Room, Retrofit and the rest from `SPEC.md` §2.1. When a
task adds a library or tool, it updates this table.

Runtime versions are pinned in one place per runtime:
- **JDK 21:** the Gradle toolchain in `server/build.gradle.kts`, and `compileOptions` in
  `android/app/build.gradle.kts`.
- **Node 24:** `.nvmrc`, plus `engines` in `web/package.json`.
- **Gradle:** the `gradle-wrapper.properties` of each project. Both use the same version.

Every CI job, Dockerfile or build that downloads its own runtime must use these same versions.

## Repository layout

```
server/      Spring Boot app, base package ch.sonensei.cockpit (modules: SPEC.md §3)
web/         Angular SPA, built into the server's static resources for deployment (later task)
android/     Android app, namespace/applicationId ch.sonensei.cockpit
api/         openapi.yaml, generated from the server (later task)
deploy/      docker-compose.yml, Caddyfile (later task)
docs/        this file, QUALITY.md, adr/
scripts/     check.sh (the fast check)
tasks/       TFP task board, one file per task
reviews/     review evidence per task (docs/QUALITY.md)
tests/       tests of the TFP tooling itself (incl. the completion gate)
```

## Install

Every machine that runs agents needs all of the following, because agents run the full check
locally.

| Tool | Linux (Fedora/Debian) | macOS | Windows |
|---|---|---|---|
| git (+ git-subtree), Python 3.10+ | `sudo dnf install git git-subtree python3` / `sudo apt install git python3` | `xcode-select --install` | Git for Windows (provides `sh`), python.org installer ("Add to PATH") |
| JDK 21 | `sudo dnf install java-21-openjdk-devel` / `sudo apt install openjdk-21-jdk`, or Temurin 21 | `brew install --cask temurin@21` | Temurin 21 MSI |
| Node 24 + npm | `nvm install 24` (reads `.nvmrc`) | `nvm install 24` | nvm-windows `nvm install 24`, or the Node 24 MSI |
| Android SDK | see below | see below | Android Studio, or cmdline-tools as below in `%LOCALAPPDATA%\Android\Sdk` |
| Docker or Podman | `sudo dnf install podman podman-docker` + `systemctl --user enable --now podman.socket`, or Docker Engine | Docker Desktop / Podman Desktop | Docker Desktop |

Gradle needs no separate install: the wrappers (`./gradlew`) download it. The JDK is found through
`JAVA_HOME` or `PATH`.

**Android SDK without Android Studio** (about 1.5 GB):

```sh
SDK=$HOME/Android/Sdk; mkdir -p $SDK/cmdline-tools && cd /tmp
curl -fLO https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip   # mac: -mac-
unzip -q commandlinetools-*.zip && mv cmdline-tools $SDK/cmdline-tools/latest
yes | $SDK/cmdline-tools/latest/bin/sdkmanager --licenses
$SDK/cmdline-tools/latest/bin/sdkmanager --install "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
```

`scripts/check.sh` uses `$ANDROID_HOME`, falling back to `~/Android/Sdk`. Android Studio's
default location on macOS is `~/Library/Android/sdk`. There, `export ANDROID_HOME=...` in your
shell profile before starting agents.

**Docker/Podman** is only needed for Testcontainers-based integration tests (added by later
tasks). With Podman (rootless), set:

```sh
export DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
```

Agents inherit the environment of the shell that runs `python3 agents.py start`, so set these
variables (and `nvm use`) in that shell, or in your shell profile.

## Run

| Part | Command | Notes |
|---|---|---|
| Server | `cd server && ./gradlew bootRun` | http://localhost:8080, health at `/actuator/health` |
| Web | `cd web && npm ci && npm start` | http://localhost:4200. A dev proxy from `/api` to `:8080` comes with the first API task |
| Android | `cd android && ./gradlew installDebug` | needs a device/emulator (`adb devices`). Only humans can verify on a real device |

## Environment variables

Defined in `SPEC.md` §11. Introduced by the tasks that need them, and listed here when they are:

| Variable | Used by | Purpose |
|---|---|---|
| `ANDROID_HOME` | android build, check | Android SDK location |
| `DOCKER_HOST`, `TESTCONTAINERS_RYUK_DISABLED` | integration tests | Podman instead of Docker |
| `COCKPIT_DATA_DIR`, `COCKPIT_MASTER_KEY`, `COCKPIT_DOMAIN`, `SPRING_DATASOURCE_*`, `GOOGLE_CLIENT_ID/SECRET`, `MS_CLIENT_ID/SECRET/TENANT` | server | see SPEC.md §11 (not yet used) |

Never commit secrets: `.env` files are Git-ignored, and `deploy/.env.example` holds placeholders only.

## API between clients and server

REST + JSON under `/api/v1`, plus server-sent events at `/api/v1/events` (`SPEC.md` §2, §4.2).
The contract lives in `api/openapi.yaml`, which is generated from the server (springdoc). Both
clients generate or derive their models from it. Contract tasks come before the backend and client
tasks that use the contract. A change to the contract updates the server, regenerates
`api/openapi.yaml` and updates the clients, in the same task or a dependent one. Until the first
API task lands, the only endpoint is `GET /actuator/health`.

## Testing

| Command | What | Time |
|---|---|---|
| `sh scripts/check.sh` (= `.taskcheck`) | TFP gate tests, server ktlint + tests, web eslint + Vitest + build, android ktlint + unit tests + Android Lint | ~25 s warm, ~3 min on a cold clone (downloads) |
| `cd server && ./gradlew check` | server lint + all tests | |
| `cd web && npm run check` | web lint + tests + production build | |
| `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` | android | |
| `python3 tests/test_quality_gate.py` (and the other `tests/test_*.py`, `bash tests/test_publish.sh`) | TFP tooling | |

Rules:
- `.taskcheck` must stay below 2 minutes. Slow suites (Testcontainers integration tests, Playwright
  e2e, Android instrumented tests, Docker image builds) get their own Gradle task/npm script, are not
  part of `.taskcheck`, and are run by the tasks whose acceptance criteria name them.
- Lint warnings are errors (`ng lint --max-warnings=0`, ktlint, Android Lint `warningsAsErrors`).
  Android Lint's version-freshness checks are disabled on purpose. Upgrades are explicit tasks.
- Use fictitious test data only.

## Coverage

Coverage informs the `test-writer`. It is not a gate, and there is no repository-wide threshold.

| Part | Command | Provider | Reports |
|---|---|---|---|
| Server | `cd server && ./gradlew test jacocoTestReport` | JaCoCo (Gradle `jacoco` plugin) | `server/build/reports/jacoco/test/jacocoTestReport.xml` (machine-readable), `.../html/index.html` |
| Web | `cd web && npm run coverage` | Vitest + `@vitest/coverage-v8` | `web/coverage/web/coverage-summary.json`, `coverage-final.json` (per file/line), `lcov.info`, text summary on stdout |
| Android | `cd android && ./gradlew createDebugUnitTestCoverageReport` | JaCoCo via AGP `enableUnitTestCoverage` | `android/app/build/reports/coverage/test/debug/report.xml`, `index.html` |

All outputs are Git-ignored (`build/`, `coverage/`). To get task-scoped coverage, the test-writer:
1. lists the production files the task changed: `git diff --name-only <base>..HEAD -- server/src/main web/src android/app/src/main`
   (excluding `*.spec.ts` and test folders);
2. runs the coverage command(s) of the affected part(s). They are fresh runs, because Gradle
   reruns tests when inputs change and Vitest rewrites `coverage/`;
3. reads per-file counters for exactly those files: in JaCoCo XML from `<package name>` /
   `<sourcefile name>` counters (`INSTRUCTION`, `BRANCH`, `LINE`, `METHOD`), in
   `coverage-final.json` per absolute file path;
4. records the numbers and significant gaps in `reviews/<task-id>/tests.md`.

## Code conventions

- **Kotlin** (server and android): ktlint `ktlint_official` style. The server indents with tabs
  (Spring Initializr style), Android with 4 spaces. See each project's `.editorconfig`. Run
  `./gradlew ktlintFormat` to fix. Composables are PascalCase.
- **Server packages:** `ch.sonensei.cockpit.<module>.{api,domain,infra}` (`SPEC.md` §3). Modules talk
  to each other through services and events, never through another module's repositories.
- **Web:** Angular style guide, standalone components, signals, `inject()`. One feature folder per
  server module, with lazy routes. The `app-` selector prefix. Format with Prettier (`web/.prettierrc`).
- **Android:** MVVM with unidirectional data flow, one package per feature.
- All user-facing strings are externalized (i18n-ready, English first).
- Every schema change is a new Flyway migration. Applied migrations are never edited.
