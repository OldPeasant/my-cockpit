# Test Report

Date: 2026-10-20

Task: mail-sanitizer - HTML mail sanitizer

Scope: `git diff b10a8deef7c9ef1a1b7cd60260a9d620d5ea0565..5cadcca1644861f8946d30ab5cd6ba763723f980` (branch `main`).
The parent named task commits d8fea4b and e9882d7. They were rebased, and at the measured HEAD they are
`d2528ab` (WIP sanitizer and XSS corpus tests) and `5cadcca` (functional and newsletter tests, ktlint format),
with the same subjects and files. The other commits in the range (`07c1bab`, `498edc7` assume lines, `5ee5f09`,
`b3a235a` TFP state of other tasks) only touch `tasks/*.md` and are excluded. The task's `touches` list
is the source of the production-file scope. Uncommitted at measurement time: only the new test files below
(untracked), no production changes.

Requirements:
- `tasks/mail-sanitizer.md` acceptance criteria 1-3
- `SPEC.md` §4.1 ("HTML email is untrusted": jsoup Safelist + custom rules, sandboxed iframe, remote images blocked by default, proxied)
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md` project notes (fictitious test data, ktlint)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sanitize/InlineStyleSanitizerTests.kt` (new)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sanitize/HtmlMailSanitizerEdgeCaseTests.kt` (new)

Existing `HtmlMailSanitizerTests.kt` and `XssCorpusTests.kt` are unchanged.

## Tests added or updated

Count: 33 new test methods (93 test invocations including parameterized cases): 14 methods (63 invocations) in
`InlineStyleSanitizerTests`, 19 methods (30 invocations) in `HtmlMailSanitizerEdgeCaseTests`.

- `InlineStyleSanitizerTests`: direct unit tests of `InlineStyleSanitizer`, covering normalization, empty/blank input,
  malformed declarations, forbidden properties (`position` in every case, `behavior`, `-moz-binding`),
  resource-loading and obfuscated values (`url`, `URL (`, `url\n(`, `image-set`, `-webkit-image-set`,
  `image()`, `element()`, `expression`, `ex/**/pression`, `var`, `env`, `attr`, `paint`, CSS escapes,
  `<`, unclosed and stray comments, `@`, `javascript:`), allowed value functions (colors, `calc`/`clamp`/`min`,
  gradients, transforms), `;` inside quotes and parentheses, unclosed quote or parenthesis, a stray `)`, and a
  comment opened inside a string. It also has the regression test for TW-1.
- `HtmlMailSanitizerEdgeCaseTests`: covers empty input, text escaping, UTF-8 output, the default policy, upper-case
  `HTTPS:` images, images without an allowed source (relative, protocol-relative, empty, missing, `ftp:`,
  `file:`, empty or non-base64 `data:`, attribute-break attempt), upper-case and line-wrapped inline JPEG,
  the proxy policy without remote images, proxy builder input (trimmed, once per image, `cid:`/`data:`
  untouched, order preserved), blocked images keeping size and alt, a forged `data-cockpit-blocked-image` marker
  being dropped, `mailto:`/`tel:` links kept and relative or anchor `href` removed, `ping`/`download`/`rel`/`id`
  removed from links, an entity-encoded `url&#40;` in a style, remote `background` attributes on tables,
  unwrapping of unknown elements, the `noscript` mXSS vector, idempotence, and the TW-1 regression through the
  full HTML pipeline.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: removes scripts, event handlers, forms, iframes | `XssCorpusTests` (existing, 3 x corpus); `HtmlMailSanitizerTests.text content of removed elements does not leak`; `HtmlMailSanitizerEdgeCaseTests.noscript mutation vector ...`, `unknown wrapper elements are unwrapped ...` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sanitize.*' --rerun` | Pass |
| AC1: `javascript:`/`data:` URLs removed except inline images | `XssCorpusTests`; `HtmlMailSanitizerTests.inline raster data images and cid images are kept, svg ...`; `HtmlMailSanitizerEdgeCaseTests.images without an allowed source are removed and not counted` (10 cases), `upper-case and line-wrapped inline raster images are kept`, `mailto and tel links are kept, ...` | same | Pass |
| AC1: external CSS removed | `XssCorpusTests` (`<link>`, `<style>`, `@import`, `META Link`); `HtmlMailSanitizerTests.fictitious newsletter keeps its content and layout` | same | Pass |
| AC1: remote images rewritten to the proxy or blocked per policy | `HtmlMailSanitizerTests.remote images are blocked by default ...`, `... rewritten to the proxy ...`, `proxy policy without a proxy url builder is rejected`; `HtmlMailSanitizerEdgeCaseTests.default options block remote images`, `upper-case remote image schemes ...`, `proxy policy without remote images reports nothing blocked`, `proxy receives each remote url once, trimmed, ...`, `blocked images keep their size and alt ...`, `a blocked marker in the input is not trusted ...`, `remote background attributes on table cells are removed` | same | Pass |
| AC1: layout tables preserved | `HtmlMailSanitizerTests.layout tables and their presentational attributes survive`, newsletter tests; `HtmlMailSanitizerEdgeCaseTests.sanitizing is idempotent ...` | same | Pass |
| AC1: inline styles kept minus `position`/`url()` | `HtmlMailSanitizerTests.inline styles keep safe declarations ...`; `InlineStyleSanitizerTests` (all); `HtmlMailSanitizerEdgeCaseTests.entity-encoded url in a style attribute is still dropped` | same | Pass |
| AC1: `position` cannot pass through via a newline in a quoted value | `InlineStyleSanitizerTests.a newline inside a quoted string cannot smuggle a position declaration` (`\n`, `\r`, `\f`); `HtmlMailSanitizerEdgeCaseTests.newline in a quoted style value cannot smuggle position through the html sanitizer` | same | **Fail** (TW-1) |
| AC2: corpus of OWASP XSS vectors and fictitious newsletters | `XssCorpusTests` (existing, 117 vectors x 2 policies + concatenated document), `HtmlMailSanitizerTests` newsletter and notification tests, plus the edge-case vectors above | same | Pass |
| AC3: `./gradlew test --tests 'ch.sonensei.cockpit.mail.sanitize.*'` passes | whole package | same | **Fail**: 335 tests, 4 failed, all TW-1. Passes once TW-1 is fixed. No persistence is involved, so Testcontainers is not needed |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sanitize.*' --rerun`

Result:

Fail: 335 tests completed, 4 failed. The 4 failures are the TW-1 regression tests: `InlineStyleSanitizerTests` with
`\n`, `\r`, `\f`, and `HtmlMailSanitizerEdgeCaseTests.newline in a quoted style value ...`. All other new and
existing tests pass.

`cd server && ./gradlew ktlintCheck` -> BUILD SUCCESSFUL (main and test source sets, including the new files).

## Full test execution

Command:

`cd server && ./gradlew check --continue`

Result:

Fail: 356 tests completed, 4 failed (the same 4 TW-1 tests). ktlint tasks pass. No other server test fails.

## .taskcheck

Result:

Not run: the sandbox denied permission for `sh scripts/check.sh`. It would also fail on TW-1 until that is fixed. The
parent must run it after the fix. Per the user's memory notes, Docker is unavailable on this machine.

## Bugs found

### Inline style filter can be bypassed with a newline inside a quoted value (`position` survives)

Finding ID:

`TW-1`

Test:

`InlineStyleSanitizerTests.a newline inside a quoted string cannot smuggle a position declaration` (`\n`, `\r`, `\f`);
`HtmlMailSanitizerEdgeCaseTests.newline in a quoted style value cannot smuggle position through the html sanitizer`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/InlineStyleSanitizer.kt:78-99` (`splitDeclarations`)

Requirement:

AC1 "preserves ... inline styles minus `position`/`url()`"; SPEC.md §4.1 (HTML mail is untrusted).

Expected behavior:

No declaration that a browser applies as `position` may be in the output. Input
`style='font-family: "a&#10;; position: fixed; top: 0; x: "'` must not produce a style that contains a
`position` declaration.

Actual behavior:

`splitDeclarations` treats a quoted string as running across newlines, so the input above is seen as a single
`font-family` declaration and output unchanged:
`font-family: "a\n; position: fixed; top: 0; x: ""`. In CSS (CSS Syntax Level 3, "consume a string token"), an
unescaped newline (`\n`, `\r`, `\f`) ends a string as a bad-string. The browser drops the `font-family`
declaration at the next `;` and then applies `position: fixed; top: 0` as normal declarations. This allows
overlays and clickjacking inside the rendered mail frame. `url()` cannot be passed through this way, because
`functionCall` scans the whole value, but any other property-level rule can (`position`, `behavior`,
`-moz-binding`). Possible fixes, for the fixer to choose: end the quote state (or drop the declaration) at
`\n`/`\r`/`\f`, or reject values that contain those characters.

Severity:

Medium. The output is rendered in a sandboxed iframe, so the overlay stays inside the mail frame. It still breaks an
explicit acceptance criterion and is a known class of CSS filter bypass.

Fix required:

`Yes - production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed. The coverage data is fresh and comes from the focused run that included all new tests, but
that run exited with failures (TW-1). The numbers are diagnostic until the run is repeated after the fix.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured separately. Both files are new in this task (every line is a changed line), so
whole-file coverage equals changed-line coverage for this scope.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-sanitizer`; base
  `b10a8deef7c9ef1a1b7cd60260a9d620d5ea0565`; HEAD `5cadcca1644861f8946d30ab5cd6ba763723f980`; task commits
  `d2528ab`, `5cadcca` (rebased forms of the parent's d8fea4b/e9882d7), confirmed by `git log --stat` and the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): git blob hashes `HtmlMailSanitizer.kt` 7c18c0e3,
  `InlineStyleSanitizer.kt` a116894f, `HtmlMailSanitizerTests.kt` 2411fe10, `XssCorpusTests.kt` ff190492,
  `InlineStyleSanitizerTests.kt` 7cf33ee9 (untracked), `HtmlMailSanitizerEdgeCaseTests.kt` e195aa0a (untracked).
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/HtmlMailSanitizer.kt`,
  `server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/InlineStyleSanitizer.kt` (both added). No renamed or
  deleted files. Excluded: `tasks/*.md` (TFP metadata), test files.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (Gradle
  9.8.1, JDK 21.0.5). In `server/`: `./gradlew test --tests 'ch.sonensei.cockpit.mail.sanitize.*' --rerun`, then
  `./gradlew jacocoTestReport -x test`. `jacocoTestReport` has no `--tests` option and depends on `test`, so the
  report was generated from the exec data of the focused run.
- Run start/completion time and exit status: JaCoCo session `R1963MACOS.local-aaee85f4`, start 1792486798721,
  dump 1792486799864 (2026-10-20 08:59:58-08:59:59 UTC). Test exit: failed (4 TW-1 failures). Report task:
  BUILD SUCCESSFUL. `test.exec` was written at 10:59:59 local time and the XML at 11:00:03 local time.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (session id above), `server/build/jacoco/test.exec`. A later `./gradlew check` run overwrote `test.exec` with
  full-suite data. The XML report was not regenerated after that and still holds the focused-run session.
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun` forced the test run. The session timestamps fall
  after the last test-file edit. The production files are unchanged from HEAD (`git status` showed only the new
  untracked test files).
- Final run after latest test additions and production fixes: yes, after the latest test additions. No production fix
  exists yet, and this coverage describes the pre-fix revision `5cadcca`.

Values below use covered/total (percentage).

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/HtmlMailSanitizer.kt` | 931/935 (99.6%) | 22/22 (100%) | 15/17 (88.2%) | 136/137 (99.3%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/InlineStyleSanitizer.kt` | 450/450 (100%) | 42/42 (100%) | 4/4 (100%) | 70/70 (100%) |
| **Aggregate (summed counters, 2/2 files measured, none excluded or missing)** | 1381/1385 (99.7%) | 64/64 (100%) | 19/21 (90.5%) | 206/207 (99.5%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `HtmlMailSanitizer.kt:120,125` (`HtmlMailSanitizer$Companion.getINLINE_IMAGE` / `getSafelist`, 2 instructions each; line 125 is the 1 missed line) | None. These are Kotlin-generated accessors of the private companion. The fields themselves are used, and lines 120/123/126-214 are covered | Justified gap. No test added (accessors are not reachable from outside) |

Line and branch coverage are not the issue. The one gap in the required behavior is TW-1, which is a logic error
on covered lines, found by a behavior test.

## Unable to test

- Rendering in a real browser (sandboxed iframe plus CSP, SPEC.md §4.1) is outside this server-only task. The TW-1
  analysis relies on CSS Syntax Level 3 tokenization rules, not on a browser run. Follow-up: a web/e2e task should
  render sanitized output in the iframe.
- `.taskcheck` was not run (permission denied in this sandbox). The parent must run it after the TW-1 fix.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion.
TW-1 (Medium): a newline inside a quoted CSS value lets `position` through `InlineStyleSanitizer`.
