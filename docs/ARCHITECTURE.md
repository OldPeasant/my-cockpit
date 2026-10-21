# Architecture & developer guide

What to build is in `SPEC.md`. This file covers how the repository is built, run and tested.
How agents work is in `AGENTS.md`, and the review evidence contract is in `docs/QUALITY.md`.

## Stack (pinned versions)

| Part | Folder | Stack |
|---|---|---|
| Server | `server/` | Kotlin 2.3.21 (+ `plugin.spring`, `plugin.jpa` with all-open for JPA entities), Spring Boot 4.1.1 (Web MVC, Actuator, Validation, Security, Data JPA/Hibernate, Flyway 12 + `flyway-database-postgresql`), PostgreSQL 16 (JDBC driver from the Boot BOM), JDK 21 (Gradle toolchain), Gradle 9.8.1 wrapper, ktlint (Gradle plugin 14.2.0), JaCoCo |
| Server libraries | `server/` | springdoc-openapi 3.0.0, logstash-logback-encoder 8.1 (JSON logs use Boot structured logging, `logstash` format), Angus Mail (Boot BOM), ical4j 4.1.1, ez-vcard 0.12.1, flexmark 0.64.8 (core + GFM strikethrough/tasklist, tables, autolink), jsoup 1.21.2, Thumbnailator 0.4.20, TwelveMonkeys `imageio-webp` 3.12.0 (WebP decoding for thumbnails), java-uuid-generator 5.1.0 (UUIDv7), BouncyCastle `bcprov-jdk18on` 1.81 (Argon2 with spring-security-crypto), java-otp 0.4.0 (TOTP), webauthn4j-core 0.29.5 |
| Server tests | `server/` | JUnit 5, Kotest assertions 6.0.4, MockK 1.14.6, springmockk 5.0.1, Testcontainers 2.0 (`testcontainers-postgresql`, `-junit-jupiter`, `spring-boot-testcontainers`), WireMock 3.13.1 (standalone), GreenMail 2.1.5 (`greenmail-junit5`) |
| Android | `android/` | AGP 9.4.1 (built-in Kotlin), Kotlin 2.3.21 + Compose compiler plugin, Compose BOM 2026.09.00, Material 3, compileSdk 37 / targetSdk 36 / minSdk 29, JDK 21, Gradle 9.8.1 wrapper, ktlint, Android Lint, JUnit 4, JaCoCo; libraries (versions in `android/gradle/libs.versions.toml`): KSP 2.3.4, Hilt 2.59 (+ androidx.hilt 1.3.0 for Navigation/WorkManager), Navigation Compose 2.9.6, Retrofit 3.0.0 + kotlinx.serialization converter (API client generated at build time from `api/openapi.yaml` by the openapi-generator Gradle plugin 7.26.0, `kotlin`/`jvm-retrofit2`, into `app/build/generated/openapi`), OkHttp 5.3.0, kotlinx.serialization 1.9.0, Room 2.8.4 (schemas in `android/app/schemas/`), WorkManager 2.11.0, DataStore 1.1.7, Tink 1.18.0 (local encryption), Credential Manager 1.5.0, Glance 1.1.1, Coil 3.3.0; tests: Robolectric 4.16 + Compose UI test (v2 rules, in `testDebugUnitTest`), Glance appwidget testing 1.1.1, MockK 1.14.6, kotlinx-coroutines-test 1.10.2, Turbine 1.2.1, OkHttp MockWebServer |
| Web | `web/` | Angular 22.2 (standalone, signals, zoneless), TypeScript 6.0, Vitest 5 via `@angular/build:unit-test` + jsdom, angular-eslint, `@vitest/coverage-v8`; Angular Material + CDK 22.2 (Material 3 theme in `web/src/theme/`: `--mat-sys-*`/`--app-*` tokens on `:root`, light/dark via `prefers-color-scheme` or `<html data-theme="light|dark">`), `@angular/localize` (source locale `en`, `npm run i18n:extract` → `src/locale/messages.xlf`), CodeMirror 6 (+ `@codemirror/lang-markdown`), FullCalendar 7 (`@fullcalendar/angular`, `fullcalendar` with daygrid/timegrid/interaction/list subpaths, `@fullcalendar/rrule` + `rrule`), TipTap 3 (core, starter-kit, link, image), pdfjs-dist 6, qrcode 1.5 (TOTP enrollment QR, rendered as SVG); dev: ng-openapi-gen, Playwright + `@axe-core/playwright`, otplib |
| Deploy | `Dockerfile`, `deploy/` | Multi-stage image (`node:24-bookworm-slim` → `eclipse-temurin:21-jdk` → `eclipse-temurin:21-jre`, non-root uid 10001), Docker Compose / podman-compose with `caddy:2` and `postgres:16-alpine`, restic (optional host backup) |
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
web/         Angular SPA, built into the server's static resources by the Dockerfile (core/spa serves it)
android/     Android app, namespace/applicationId ch.sonensei.cockpit
api/         openapi.yaml, generated from the server (how to regenerate: api/README.md)
deploy/      docker-compose.yml, Caddyfile, .env.example, gen-env.sh, host-backup.sh (deploy/README.md)
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

Web libraries come from the lockfile (`cd web && npm ci`). Playwright browsers for the (slow, non-check)
E2E suites: `cd web && npx playwright install --with-deps chromium`.

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

**Docker/Podman** is needed for the server tests (Testcontainers Postgres, also in `.taskcheck`) and
the integration tests. With Podman (rootless), set:

```sh
export DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
```

Agents inherit the environment of the shell that runs `python3 agents.py start`, so set these
variables (and `nvm use`) in that shell, or in your shell profile.

## Run

| Part | Command | Notes |
|---|---|---|
| Server | `cd server && ./gradlew bootRun --args='--spring.profiles.active=dev'` | http://localhost:8080, health at `/actuator/health`. Needs Postgres 16 at `localhost:5432/cockpit` (user/password `cockpit`) or `SPRING_DATASOURCE_*`, e.g. `podman run -d -p 5432:5432 -e POSTGRES_DB=cockpit -e POSTGRES_USER=cockpit -e POSTGRES_PASSWORD=cockpit postgres:16-alpine`. Also needs `COCKPIT_MASTER_KEY`, e.g. `export COCKPIT_MASTER_KEY=$(openssl rand -base64 32)` (keep it: stored credentials need the same key) |
| Web | `cd web && npm ci && npm start` | http://localhost:4200. The dev proxy (`web/proxy.conf.json`) forwards `/api` and `/actuator` to `localhost:8080` |
| Android | `cd android && ./gradlew installDebug` | needs a device/emulator (`adb devices`). Only humans can verify on a real device |
| Full stack | `sh deploy/gen-env.sh localhost` (set ports ≥ 1024 for rootless Podman), then `docker compose -f deploy/docker-compose.yml up --build` | Caddy + server image + Postgres; see `deploy/README.md`. Image only: `docker build .` |

## Environment variables

Defined in `SPEC.md` §11. Introduced by the tasks that need them, and listed here when they are:

| Variable | Used by | Purpose |
|---|---|---|
| `ANDROID_HOME` | android build, check | Android SDK location |
| `DOCKER_HOST`, `TESTCONTAINERS_RYUK_DISABLED` | server tests, integration tests | Podman instead of Docker |
| `E2E_COMPOSE`, `E2E_PORT`, `E2E_IMAP_PORT`, `E2E_KEEP`, `E2E_BASE_URL` | `npm run e2e` | compose command, server and GreenMail IMAP ports, keep the stack, or use a running one (`web/e2e/README.md`) |
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | server | Postgres connection (`application.properties`) |
| `COCKPIT_MASTER_KEY` | server | AES-256-GCM key for stored credentials (base64, 32 bytes, `core.crypto`). Required: startup fails without it. Tests use a fictitious key from `server/src/test/resources/config/application.properties` |
| `GOOGLE_CLIENT_ID/SECRET`, `MS_CLIENT_ID/SECRET/TENANT` | server | OAuth apps (`core.oauth`); unset = provider disabled. Setup: `docs/oauth-setup.md` |
| `COCKPIT_DOMAIN` | server | public host; OAuth redirect URIs are `https://<domain>/api/v1/oauth/<provider>/callback` (`cockpit.oauth.public-url`, dev profile: `http://localhost:4200`) |
| `COCKPIT_DATA_DIR` | server | data directory (`cockpit.data-dir`, default `data` relative to the working directory); file blobs in `blobs/ab/cd/<uuid>` (`files.infra.BlobStore`). Tests use `server/build/test-data` |

Never commit secrets: `.env` files are Git-ignored, and `deploy/.env.example` holds placeholders only.

## API between clients and server

REST + JSON under `/api/v1`, plus server-sent events at `/api/v1/events` (`SPEC.md` §2, §4.2).
The contract lives in `api/openapi.yaml`, which is generated from the server (springdoc). Both
clients generate or derive their models from it. Contract tasks come before the backend and client
tasks that use the contract. A change to the contract updates the server, regenerates
`api/openapi.yaml` and updates the clients, in the same task or a dependent one. Until the first
API task lands, the only endpoint is `GET /actuator/health`. Errors, ETag/If-Match and cursor
pagination helpers live in `server/.../core/web` (summary in `api/README.md`).

## Testing

| Command | What | Time |
|---|---|---|
| `sh scripts/check.sh` (= `.taskcheck`) | TFP gate tests, server ktlint + tests, web eslint + Vitest + build, android ktlint + unit tests + Android Lint | ~25 s warm, ~3 min on a cold clone (downloads) |
| `cd server && ./gradlew check` | server lint + `test` (unit tests + Spring tests against the shared Postgres Testcontainer; needs Docker/Podman) | ~12 s warm |
| `cd server && ./gradlew integrationTest` | server tests tagged `external` (`@ExternalTest`: GreenMail, Radicale, WireMock, ...). Not part of `check`/`.taskcheck` | |
| `cd web && npm run e2e [-- <spec>]` | Playwright (chromium) against `deploy/docker-compose.e2e.yml` (server image in profile `e2e`, fresh Postgres, GreenMail 2.1.5): builds/starts the stack, waits for health, runs the specs in `web/e2e/`, tears down (`web/e2e/README.md`). Not part of `.taskcheck` | ~20 s for `support/smoke.spec.ts` with cached image layers (stack healthy after ~16 s, 2 tests ~2 s); a changed source rebuilds the image first |
| `cd web && npm run check` | web generated-client freshness check (`api:check`; `npm run api:gen` regenerates) + lint + tests + production build | |
| `cd android && ./gradlew ktlintCheck testDebugUnitTest lintDebug` | android | |
| `python3 tests/test_quality_gate.py` (and the other `tests/test_*.py`, `bash tests/test_publish.sh`) | TFP tooling | |

CI (`.github/workflows/ci.yml`, GitHub Actions, on pushes to `main` and on PRs; commits that only touch
`tasks/`, `reviews/` or `BOARD.md` are skipped, and runs on `main` are never cancelled) runs `sh scripts/check.sh`,
`./gradlew integrationTest` and `docker build .` with the runtimes above. Lint it with
`docker run --rm -v "$PWD:/repo:Z" -w /repo docker.io/rhysd/actionlint:latest` after editing.

Server test infrastructure (`server/src/test/kotlin/ch/sonensei/cockpit/support/`):
- Spring tests that need the database extend `PostgresTestBase`: one `postgres:16-alpine` container
  per test JVM, wired in through `@ServiceConnection` (datasource + Flyway). Keep Spring test
  configurations uniform so the context cache can reuse contexts.
- Tests against external systems are annotated `@ExternalTest` (JUnit tag `external`). `test` excludes
  them, `integrationTest` runs only them.
- Docker/Podman is required for `./gradlew test`, and therefore for `.taskcheck`. With Podman set
  `DOCKER_HOST` and `TESTCONTAINERS_RYUK_DISABLED=true` (see "Install").

Server profiles: none = production-like (JSON logs in Logstash format, datasource from
`SPRING_DATASOURCE_*`), `dev` = plain-text logs and local defaults, `e2e` = the end-to-end stack (adds
the unauthenticated test hooks `POST /api/test/clock` and `/api/test/reset` from `core.testsupport`; never in production).

Rules:
- `.taskcheck` must stay below 2 minutes. Slow suites (Testcontainers integration tests, Playwright
  e2e, Android instrumented tests, Docker image builds) get their own Gradle task/npm script, are not
  part of `.taskcheck`, and are run by the tasks whose acceptance criteria name them.
- Lint warnings are errors (`ng lint --max-warnings=0`, ktlint, Android Lint `warningsAsErrors`).
  Android Lint's version-freshness checks are disabled on purpose. Upgrades are explicit tasks.
- Use fictitious test data only.
- Web unit tests run with Vitest `isolate: true` (`web/angular.json` `test` target), so each spec file
  gets a fresh module graph. `app.i18n.spec.ts` needs this (Angular caches i18n template constants on
  first render); don't remove it for speed.

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

## Persistence conventions

Building blocks in `ch.sonensei.cockpit.core.persistence` (SPEC.md §4.2):
- **Entities** extend `BaseEntity` (or `SoftDeletableEntity` for user content that goes to the Trash).
  Tables declare `id uuid primary key, created_at timestamptz not null, updated_at timestamptz not null,
  version bigint not null` (+ `deleted_at timestamptz` for soft delete). Equality is by class and id;
  `equals`/`hashCode`/`toString` called on an uninitialized lazy proxy load it, so compare ids for detached references.
- **Ids** are UUIDv7 (`UuidV7.generate()`), created in the entity constructor. A client id (offline
  capture, idempotent create) is passed to the constructor and must be a UUIDv7.
- **Time:** inject the `Clock` bean (UTC) and never call `Instant.now()`. `createdAt`/`updatedAt` come from it
  through JPA auditing, at microsecond precision like `timestamptz`. Tests import a `@Primary` `MutableClock` to fix or advance time.
- **Optimistic locking:** `@Version`. Saving a stale copy throws `OptimisticLockingFailureException`.
- **Soft delete:** `entity.softDelete(clock)` / `restore()`. Repositories extend `SoftDeletableRepository`.
  Normal reads use `findActiveById`/`findAllActive`, and derived queries add `DeletedAtIsNull`.
- **Migrations:** `server/src/main/resources/db/migration/<module>/V<yyyyMMddHHmm>__<module>_<desc>.sql`
  (e.g. `gtd/V202610211430__gtd_tasks.sql`), using the UTC time you create the file. Flyway scans the
  subfolders and runs `out-of-order`, so parallel tasks never clash on version numbers. Never edit or
  rename an applied migration: change the schema in a new one. The first migration,
  `core/V202610200000__core_enable_pg_trgm.sql`, only enables `pg_trgm`. Module tables come in module
  migrations. Test-only tables go to `server/src/test/resources/db/migration/<module>/` as
  `V<yyyyMMddHHmm>_<n>__<module>_test_<desc>.sql`: the `_<n>` suffix keeps them off real version numbers.
