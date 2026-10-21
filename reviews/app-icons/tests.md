# Test Report

Date: 2026-10-21

Task: app-icons - App icon and favicon from the brand mark

Scope: `git diff f4493067c68a58be29d5a19dd352a7ed0f957d7d..6ad3dbca8e67ea9e392384e85944b796ec682ccc` (one task commit `6ad3dbca`, branch `main`, HEAD `6ad3dbca8e67ea9e392384e85944b796ec682ccc`). Uncommitted at test time: only the two new test files listed below.

Requirements:
- `tasks/app-icons.md` acceptance criteria and recorded assumptions (mipmap-anydpi instead of -v26, full-square touch icon, 50 % alpha sky in the monochrome icon, Playwright-rendered ICO/PNG)
- `docs/brand/README.md`, `docs/brand/mark.svg`, `docs/brand/app-icon.svg`
- `docs/ARCHITECTURE.md` "Coverage", `docs/QUALITY.md`, `AGENTS.md`

## Test files changed

- `web/src/app/shell/app-icons.spec.ts` (new, Vitest via `ng test`; reads `src/index.html` and `public/` with `node:fs`)
- `android/app/src/test/java/ch/sonensei/cockpit/ui/LauncherIconTest.kt` (new, Robolectric, same pattern as `MainActivityLaunchTest`)

## Tests added or updated

Count: 10 (6 web, 4 Android)

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: index.html links the SVG favicon first, the ICO as fallback, then the touch icon | `links the SVG favicon first, then the ICO fallback, then the touch icon`, `declares the sizes the ICO actually contains` | `npx ng test --watch=false --include src/app/shell/app-icons.spec.ts` (in `web/`); `npm --prefix web run check` | Pass |
| AC1: `favicon.svg` is `docs/brand/mark.svg` and keeps its dark-mode switch | `favicon.svg is the brand mark and keeps its dark-mode switch` (byte-equal to `mark.svg`, contains `prefers-color-scheme: dark`) | same | Pass |
| AC1: `favicon.ico` holds 16, 32, 48 px | `favicon.ico holds 16, 32 and 48 px images whose PNG data matches the directory` (ICO header, directory sizes, each embedded PNG's IHDR matches its entry, offsets inside the file) | same | Pass |
| AC1: `apple-touch-icon.png` is 180 px (assumption: full opaque square) | `apple-touch-icon.png is a 180 px square PNG`, `apple-touch-icon.png is opaque (iOS fills transparent pixels black)` | same | Pass |
| AC1: `npm run check` passes and the `ng build` output contains the three files | whole web suite + build | `npm --prefix web run check`; `ls web/dist/web/browser` | Pass (120 files / 2364 tests; `apple-touch-icon.png`, `favicon.ico`, `favicon.svg` present in `web/dist/web/browser`) |
| AC2: manifest references `@mipmap/ic_launcher` | `manifestUsesTheAdaptiveLauncherMipmap` (ApplicationInfo.icon == `R.mipmap.ic_launcher`) | `android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug` | Pass. `roundIcon` is not exposed through a public API; checked by reading the manifest (line 18 `@mipmap/ic_launcher_round`) and by `ic_launcher_round` inflating below |
| AC2: adaptive `ic_launcher` + `ic_launcher_round` with background, foreground and monochrome layers | `squareAndRoundIconsAreAdaptiveWithAllThreeLayers` (both inflate to `AdaptiveIconDrawable`, all three layers non-null) | same | Pass |
| AC2: layers are 108 dp vectors | `layersUseTheFullAdaptiveIconCanvas` (intrinsic size 108 dp for all three drawables) | same | Pass |
| AC2: old placeholder `drawable/ic_launcher.xml` removed | `placeholderDrawableIconIsRemoved` (no `R.drawable.ic_launcher`; guarded so reflection must see `ic_launcher_foreground`) | same | Pass |
| AC2: ktlintCheck + testDebugUnitTest + lintDebug, no new lint warnings | whole Android suite | same | Pass (1231 tests, 0 failures/errors/skipped; lint report has only one `Hint`-severity `AutoboxingStateCreation` issue, not in task files) |
| AC2: foreground mark inside the 66 dp safe zone, colours as in `app-icon.svg`; background ink `#14243A` | none automated (manual geometry check below) | inspection of `ic_launcher_foreground.xml` / `ic_launcher_background.xml` | Not testable as a cheap unit test: Robolectric's default (legacy) graphics mode does not rasterize vectors. Manual check: scale 0.7875, translate 29.19375 maps the mark centre (31.5, 31.5) to (54, 54); farthest point (dial at 12,12, r 8) is (27.58 + 8) * 0.7875 = 28.0 dp from centre, inside 33 dp; colours `#14243A`, `#EEF2F7`, `#E8962E` match `app-icon.svg` |
| AC3: rendered icons match `docs/brand/` | none | n/a | Not run by test-writer: visual comparison is the parent's evidence (render/inspection); the byte-equality test above covers `favicon.svg` vs `mark.svg` |
| AC4: generation documented in `docs/brand/README.md` | none | n/a | Not testable as automated test (documentation); parent verifies |

## Focused test execution

Command:

`npx ng test --watch=false --include src/app/shell/app-icons.spec.ts` (in `web/`) -> 6 passed

`./gradlew ktlintCheck testDebugUnitTest --tests 'ch.sonensei.cockpit.ui.LauncherIconTest'` (in `android/`) -> 4 passed (JUnit XML: tests=4, failures=0, errors=0, skipped=0)

Result:

Pass

## Full test execution

Command:

`npm --prefix web run check` (api:check, lint, e2e typecheck, `ng test --watch=false`, `ng build`)

`android/gradlew -p android ktlintCheck testDebugUnitTest lintDebug --console=plain`

Result:

Pass (web: 120 test files, 2364 tests; Android: BUILD SUCCESSFUL, 1231 unit tests, 0 failures). Both run after the final test edits.

## .taskcheck

Result:

Pass (`sh scripts/check.sh` -> "all checks passed", run after the final test edits)

## Bugs found

None. No production defects found.

## Coverage

Status: not applicable - the task changed no instrumentable production code in the measured parts.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured (no executable changed lines under an instrumented source root).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `app-icons`, base `f4493067c68a58be29d5a19dd352a7ed0f957d7d`, HEAD `6ad3dbca8e67ea9e392384e85944b796ec682ccc`, task commit `6ad3dbca` (supplied by the parent; `git log f4493067..HEAD` shows only that commit).
- Source/test snapshot identifier (including uncommitted inputs): HEAD `6ad3dbca8e67ea9e392384e85944b796ec682ccc` plus untracked `web/src/app/shell/app-icons.spec.ts` (sha256 `cef69d899e7fe0f9c4c856ea31d89bea4be409b0eceaf3b306f3e81f296b100c`) and `android/app/src/test/java/ch/sonensei/cockpit/ui/LauncherIconTest.kt` (sha256 `50adb98457c4acc625d6736e3e4dc9d78f5c1d8c4e8f7ca0347474ccdee80485`).
- Exact included files; renamed/deleted/excluded files and reasons: `git diff --name-only f4493067..HEAD -- server/src/main web/src android/app/src/main` lists only `web/src/index.html` (HTML, no executable code), `android/app/src/main/AndroidManifest.xml` and `android/app/src/main/res/**` XML resources (no bytecode, JaCoCo cannot count them), and the deleted `android/app/src/main/res/drawable/ic_launcher.xml` (not present at HEAD). Excluded: `web/public/*` (binary/static assets), `docs/brand/README.md` (documentation). `docs/brand/generate-icons.mjs` is the only executable file: a dev tool outside every configured coverage root (Vitest coverage instruments `web/src` code imported by specs; the script runs top-level Playwright/Chromium and writes into `web/public`, so it cannot be imported by a unit test without side effects). Classified as missing instrumentation, not as covered.
- Provider/version, command, working directory, and test selection: no coverage command run, since no file in scope is instrumentable by `npm run coverage` (Vitest v5.0.5 + @vitest/coverage-v8) or `createDebugUnitTestCoverageReport` (JaCoCo).
- Run start/completion time and exit status: N/A (no coverage run).
- Generated report paths and report hashes/run identifier: N/A.
- Evidence outputs are fresh and inputs stayed unchanged: test runs above were on the snapshot listed; no coverage numbers from earlier runs are used.
- Final run after latest test additions and production fixes: yes for tests (no production fixes); coverage not applicable.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `web/src/index.html` | N/A | N/A | N/A | N/A |
| `android/app/src/main/AndroidManifest.xml`, `res/drawable/ic_launcher_{background,foreground,monochrome}.xml`, `res/mipmap-anydpi/ic_launcher{,_round}.xml` | N/A | N/A | N/A | N/A |
| `docs/brand/generate-icons.mjs` | N/A (not instrumented) | N/A | N/A | N/A |

No aggregate: no file in scope has coverage counters.

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `docs/brand/generate-icons.mjs` (whole file) | Regenerating the ICO/PNG when the mark changes (AC4); ICO writer correctness | Not instrumented (dev tool, needs Chromium and writes production assets). Its output is checked instead: `app-icons.spec.ts` validates the ICO header/directory/embedded PNG sizes and the touch icon size, so a broken regeneration fails `npm run check` |
| `web/src/index.html`, Android XML resources | AC1 links, AC2 adaptive icon wiring | Covered behaviourally by the 10 new tests (not by coverage counters) |

## Unable to test

- Visual match of the rendered icons with `docs/brand/app-icon.svg` (AC3) and the exact foreground geometry/colours (AC2): needs rasterization (browser or device/launcher); Robolectric legacy graphics does not render vectors. Parent must record its render/inspection evidence. Manual geometry check above found no defect.
- `android:roundIcon` attribute value: no public API exposes it; verified by reading the manifest.
- `docs/brand/generate-icons.mjs` was not executed by the test-writer (it overwrites `web/public` production assets).

## Summary

Tests added and all validation passed. No production defects found; coverage is not applicable (no instrumentable production code in scope) and the generator script's output is guarded by the new web spec.
