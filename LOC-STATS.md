# Lines of code: My Cockpit

As of 2026-11-14, `main` @ `5fe75dd3c`. Counts cover git-tracked files only. Binaries (fonts, jars, images, SVG) are excluded.
All numbers are **non-blank lines**. Raw `wc -l` totals are about 10 % higher. These counts include comment lines; see "Code vs comments" for the split.

## Overview (non-blank lines, file count in parentheses)

| Area | Production | Tests | Docs (.md) | Generated | i18n | Config / build | **Total** |
|---|---:|---:|---:|---:|---:|---:|---:|
| **Web** (Angular) | 49,937 (409) | 77,746 (233) | 138 (3) | 23,447 (445) | 30,886 (2) | 542 (17) | **182,696** |
| **Android** (Kotlin/Compose) | 56,765 (304) | 101,975 (466) | 41 (1) | – | – | 2,211 (19) | **160,992** |
| **Server** (Kotlin/Spring) | 53,981 (439) | 101,165 (459) | – | – | – | 540 (9) | **155,686** |
| API contract (`api/`) | – | – | 30 (1) | 15,208 (1) | – | – | **15,238** |
| **TFP tooling** | 3,435 (9) | 2,880 (12) | 1,474 (6) | – | – | 598 (3) | **8,387** |
| **TFP records** (`tasks/`, `reviews/`, BOARD.md) | – | – | 75,251 (1,189) | – | – | 8,386 (307) | **83,637** |
| Project docs / infra | 166 (4) | – | 1,839 (15) | – | – | 648 (11) | **2,653** |
| **Total** | **164,284** | **283,766** | **78,773** | **38,655** | **30,886** | **12,925** | **609,289** |

### Product code only (Web + Android + Server)

| | Production | Tests | Test : production |
|---|---:|---:|---:|
| Web | 49,937 | 77,746 | 1.56 |
| Android | 56,765 | 101,975 | 1.80 |
| Server | 53,981 | 101,165 | 1.87 |
| **Sum** | **160,683** | **280,886** | **1.75** |

Hand-written product code totals about **441k lines**. There are 1.75 lines of test for every line of production code.

## Code vs comments

The tables above count every non-blank line, so comments are included. This section splits production and test lines into code and comments.
- A line with both code and a trailing comment counts as **code**.
- Comment-only lines and lines inside `/* … */`, KDoc, JSDoc, `<!-- … -->` or Python docstrings count as **comment**.
- Strings are recognized, so `"http://…"` is not mistaken for a comment.
- A grep cross-check of Kotlin and TypeScript comment lines agrees within 0.5 %.

| Area | Prod code | Prod comments | Comment share | Test code | Test comments | Comment share |
|---|---:|---:|---:|---:|---:|---:|
| Web | 45,920 | 4,017 | 8.0 % | 76,077 | 1,669 | 2.1 % |
| Android | 51,586 | 5,179 | 9.1 % | 99,710 | 2,265 | 2.2 % |
| Server | 47,182 | 6,799 | 12.6 % | 97,807 | 3,358 | 3.3 % |
| **Product sum** | **144,688** | **15,995** | **10.0 %** | **273,594** | **7,292** | **2.6 %** |
| TFP tooling | 3,119 | 316 | 9.2 % | 2,692 | 188 | 6.5 % |
| Project infra scripts | 137 | 29 | 17.5 % | – | – | – |

Counting pure code only, the product has **~145k lines of production code** and **~274k lines of test code**. That is a test : production ratio of **1.89**.

Production code by language:

| | Code | Comments | Comment share |
|---|---:|---:|---:|
| Kotlin, server | 46,348 | 6,591 | 12.4 % |
| Kotlin, Android | 47,670 | 5,081 | 9.6 % |
| TypeScript, web | 26,636 | 3,848 | 12.6 % |
| HTML templates, web | 10,226 | 10 | 0.1 % |
| SCSS, web | 8,909 | 139 | 1.5 % |
| Android XML resources | 3,916 | 98 | 2.5 % |
| SQL migrations | 789 | 182 | 18.7 % |
| Python, TFP | 3,081 | 316 | 9.3 % |

## Details per area

### Web
- Production by type: TypeScript 30,484 (265 files), HTML templates 10,236 (61), SCSS 9,048 (80), JS/MJS 169 (3).
- Tests: 196 unit specs (`*.spec.ts`) with 71,670 lines, plus Playwright e2e (`web/e2e/`) with 35 files and 5,924 lines.
- Generated: the OpenAPI client in `web/src/app/api/generated/` (444 files, 11,872 lines) and `package-lock.json` (11,575).
- i18n: `messages.xlf` (extracted) and `messages.de.xlf` (German translation).

### Android
- Production: Kotlin 52,751 (228 files) and XML resources/manifest 4,014 (76).
- Tests: Kotlin 101,975 (466 files) in `src/test`, `src/androidTest` and `src/sharedTest`.
- Config/build includes the Gradle files and the Room schema JSONs (`app/schemas/`).

### Server
- Production: Kotlin 52,939 (397 files), Flyway SQL migrations 971 (38), properties 70.
- Tests: Kotlin 99,347 (422 files), plus JSON/SQL fixtures and resources 1,818.

### TFP
- **Tooling** is the code that runs the agent process: `tasks.py`, `agents.py`, `board.py`, `repos.py`, `dashboard/`, `tests/`, `.claude/`, AGENTS.md and TEAM.md.
  - Production: Python 3,397 (6 files) and the dashboard front end (37).
  - Tests: Python 2,653 (10 files) and shell 227 (2).
  - Docs: AGENTS.md, TEAM.md and the agent definitions in `.claude/agents/`.
- **Records** are the process output, not code:
  - `tasks/*.md` (319 tasks, 9,274 lines).
  - `reviews/*/*.md` (869 test, review and fix reports, 64,848 lines).
  - `reviews/*/quality.json` (307 files, 8,386 lines).
  - BOARD.md (1,129 lines).

### Project docs / infra
- Docs: SPEC.md (704), `docs/` (ARCHITECTURE, QUALITY, ADRs), README.md, BRAINSTORM.md and `deploy/README.md`.
- Production: `scripts/check.sh` and the deploy scripts. Config: Dockerfile, compose files, Caddyfile and the CI workflow.

## Classification rules
- **Test:** `server/src/test/**`, `android/app/src/{test,androidTest,sharedTest}/**`, `web/**/*.spec.ts`, `web/e2e/**` and `tests/**`.
- **Doc:** every `*.md` file.
- **Generated:** `api/openapi.yaml`, `web/src/app/api/generated/**` and `package-lock.json`.
- **i18n:** `*.xlf`.
- **Production:** source files (`.kt .ts .html .scss .css .sql .py .js .mjs .sh`), `android/app/src/main/**` XML and `server/src/main/resources/**`.
- **Config/build:** everything else, such as Gradle, JSON, YAML, `angular.json`, `*.config.ts` and Gradle wrappers.
- The `tfp/` upstream clone is git-ignored and not counted.
