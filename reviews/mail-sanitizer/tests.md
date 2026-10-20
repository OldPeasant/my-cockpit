# Test Report

Date: 2026-10-20

Task: mail-sanitizer - HTML mail sanitizer (round 2, final revision after the fix commit f9ae82f)

Scope: `git diff c038ac42de3a5dd415a2b1d1e9abc05b650ad5f0..eaf98b480550c02ce069dee961eded8470ce13fb` (base =
`git merge-base HEAD origin/main`, HEAD = merge commit `eaf98b4` on branch `main`). Task commits, all on the merged
side branch `origin/wip/mail-sanitizer`: `1b03cfe` (WIP sanitizer and XSS corpus tests), `433691f` (functional and
newsletter tests), `bf0aef1` (round-1 test-writer tests and reports), `f9ae82f` (fix: newline string-smuggling and
quadratic style parsing), `dd56813` (WIP hand-off: `InlineStyleSanitizerParityTests.kt` and review.md). The
first-parent commits `30655ee`, `f49149c`, `c038ac4` only touch `tasks/*.md` (TFP state) and are outside the range
anyway. Uncommitted at measurement time: my edits to two test files (listed below), no production changes.

Requirements:
- `tasks/mail-sanitizer.md` acceptance criteria 1-3 and its three recorded assumptions
- `SPEC.md` §4.1 (HTML email is untrusted: jsoup Safelist + custom rules, sandboxed iframe, remote images blocked by
  default or proxied)
- `docs/ARCHITECTURE.md` "Coverage"; `AGENTS.md` project notes (fictitious test data, ktlint)
- `reviews/mail-sanitizer/fixes.md` (what f9ae82f changed)

## Test files changed

In this round (uncommitted, for the parent to commit):
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sanitize/HtmlMailSanitizerEdgeCaseTests.kt` (2 tests added)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sanitize/InlineStyleSanitizerParityTests.kt` (comment only: the
  reference regexes are "before commit f9ae82f"; the old text named `3b9d491`, a pre-rebase hash that is not in
  HEAD's history. Its sanitizer sources are identical to f9ae82f.)

Test files of the task as a whole (all in `server/src/test/kotlin/ch/sonensei/cockpit/mail/sanitize/`):
`XssCorpusTests.kt`, `HtmlMailSanitizerTests.kt`, `HtmlMailSanitizerEdgeCaseTests.kt`, `InlineStyleSanitizerTests.kt`,
`InlineStyleSanitizerParityTests.kt`.

## Tests added or updated

Count: 2 added in this round (both in `HtmlMailSanitizerEdgeCaseTests`), 1 comment corrected. The package now has
390 test invocations: XssCorpusTests 230, InlineStyleSanitizerTests 71, InlineStyleSanitizerParityTests 45,
HtmlMailSanitizerEdgeCaseTests 32, HtmlMailSanitizerTests 12.

Added in this round, both for the f9ae82f behavior through the full HTML pipeline:
- `a style rejected for a newline in a quote is removed only from its own element`: an entity-encoded CRLF
  (`&#13;&#10;`) inside a single-quoted value removes the whole `style` attribute (also its safe `color: red`), keeps the
  element and its text, and leaves the next element's style intact.
- `a style attribute over the length cap is removed but the element and its text are kept`: a style longer than
  `MAX_STYLE_LENGTH` loses the attribute, the element and text stay, and a sibling style is unaffected.

Previously untracked in the report, now reviewed: `InlineStyleSanitizerParityTests` (dd56813). It checks that the
linear `stripComments` and `functionNames` give the same results as the regexes they replaced (24 fixed cases plus
20 000 random values for function names; 11 fixed cases plus 20 000 random styles for comments), that a newline in a
single- or double-quoted value rejects the whole style, that a newline inside parentheses outside quotes is kept, the
cap +1 boundary, and, with an independent simplified CSS Syntax 3 reader, that no random style (50 000 generic and
50 000 smuggling-shaped inputs) yields a `position` declaration as a browser reads the output, and that output is
idempotent. A self-test proves that this reader does see the round-1 TW-1 bypass. I reviewed these tests for
soundness: the oracles are independent of the production code, the seeds are fixed, and the run is deterministic
(about 2 s for the class).

Assessment of the fixed code (f9ae82f) against existing tests, no further gaps found: LF, CR, FF and CRLF inside double
and single quotes; a newline outside quotes and inside parentheses; a newline inside a comment inside a quote; an
unclosed `/*` near the cap (linear-time `@Timeout`); a 1 MB attribute; cap and cap+1 boundaries; forbidden names hidden
behind other name characters (`-1url(`, `9_url (`, `--url\t(`, `-image-set(`).

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: removes scripts, event handlers, forms, iframes | `XssCorpusTests` (corpus x 2 policies + concatenated document); `HtmlMailSanitizerTests.text content of removed elements does not leak`; `HtmlMailSanitizerEdgeCaseTests.noscript mutation vector ...`, `unknown wrapper elements are unwrapped ...` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sanitize.*' ktlintCheck --rerun` | Pass |
| AC1: `javascript:`/`data:` URLs removed except inline images | `XssCorpusTests`; `HtmlMailSanitizerTests.inline raster data images and cid images are kept, svg ...`; `HtmlMailSanitizerEdgeCaseTests.images without an allowed source are removed and not counted` (10 cases), `upper-case and line-wrapped inline raster images are kept`, `mailto and tel links are kept, ...` | same | Pass |
| AC1: external CSS removed | `XssCorpusTests` (`<link>`, `<style>`, `@import`, `META Link`); `HtmlMailSanitizerTests.fictitious newsletter keeps its content and layout`; `InlineStyleSanitizerTests` (`@` in values) | same | Pass |
| AC1: remote images rewritten to the proxy or blocked per policy | `HtmlMailSanitizerTests` (block default, proxy rewrite, proxy without builder rejected); `HtmlMailSanitizerEdgeCaseTests` (default options, upper-case schemes, proxy without remote images, proxy input once/trimmed/order, blocked images keep size and alt, forged blocked marker, remote table `background`) | same | Pass |
| AC1: layout tables preserved | `HtmlMailSanitizerTests.layout tables and their presentational attributes survive`, newsletter tests; `HtmlMailSanitizerEdgeCaseTests.sanitizing is idempotent ...` | same | Pass |
| AC1: inline styles kept minus `position`/`url()` | `HtmlMailSanitizerTests.inline styles keep safe declarations ...`; `InlineStyleSanitizerTests` (all); `InlineStyleSanitizerParityTests` (all); `HtmlMailSanitizerEdgeCaseTests.entity-encoded url in a style attribute is still dropped` | same | Pass |
| AC1 (fix CR-1/TW-1): a newline in a quoted value cannot smuggle `position` | `InlineStyleSanitizerTests.a newline inside a quoted string cannot smuggle a position declaration` (LF, CR, FF), `a newline outside quotes is plain whitespace`; `InlineStyleSanitizerParityTests.a newline in a quoted value rejects the whole style ...`, `... single-quoted string ...`, `no random style yields a position declaration ...`, `no smuggling-shaped random style ...`; `HtmlMailSanitizerEdgeCaseTests.newline in a quoted style value cannot smuggle position through the html sanitizer`, `a style rejected for a newline in a quote is removed only from its own element` (new) | same | Pass |
| AC1 (fix CR-2): style parsing is linear; styles over 16 KiB dropped whole | `InlineStyleSanitizerTests.many unclosed comments just below the length cap ...`, `a huge hostile style is dropped quickly`, `a style at the length cap is still sanitized` (all `@Timeout` 2 s), `a forbidden function name is found also behind other name characters`; `InlineStyleSanitizerParityTests` (regex parity, cap + 1); `HtmlMailSanitizerEdgeCaseTests.a style attribute over the length cap is removed ...` (new) | same | Pass |
| AC2: corpus of OWASP XSS vectors and fictitious newsletters | `XssCorpusTests` (117 vectors x 2 policies + concatenated document), `HtmlMailSanitizerTests` newsletter and notification tests, edge-case vectors above | same | Pass |
| AC3: `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sanitize.*'` passes | whole package, 390 tests | same | Pass: 390 tests, 0 failures, 0 errors, 0 skipped. No persistence is involved, so Testcontainers is not needed |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sanitize.*' ktlintCheck --rerun`

Result:

Pass. BUILD SUCCESSFUL. Test XML results (2026-10-20 09:28 UTC): 390 tests, 0 failures, 0 errors, 0 skipped
(HtmlMailSanitizerEdgeCaseTests 32, HtmlMailSanitizerTests 12, InlineStyleSanitizerParityTests 45,
InlineStyleSanitizerTests 71, XssCorpusTests 230). ktlint passed on main and test source sets.

## Full test execution

Command:

`cd server && ./gradlew check`

Result:

Pass. BUILD SUCCESSFUL: 491 server tests, 0 failures, 0 errors, 0 skipped; ktlint included.

## .taskcheck

Result:

Pass. `sh scripts/check.sh` (the `.taskcheck` command, run from `server/` as `sh ../scripts/check.sh`; the script
changes to the repo root itself) printed every step through `== all checks passed`: TFP completion-gate tests, server
`check`, web eslint + tests (7 passed) + build, android ktlint + unit tests + lint. Run on the working tree including
my two uncommitted test edits.

## Bugs found

None in this round. TW-1 from round 1 (newline in a quoted value lets `position` through) is fixed by f9ae82f. Its
regression tests pass unchanged, and the new tests above confirm the fix through the HTML pipeline.

## Coverage

Status: verified. Fresh focused run on the final code (HEAD `eaf98b4` plus my uncommitted test edits), exit status 0.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: equal to whole-file coverage for this scope. Both production files were added by this task
(`git diff --name-status c038ac4 HEAD -- server/src/main` shows `A` for both), so every executable line is a changed
line. It was not computed separately with a diff intersection.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `mail-sanitizer`; base
  `c038ac42de3a5dd415a2b1d1e9abc05b650ad5f0` (`git merge-base HEAD origin/main`); HEAD
  `eaf98b480550c02ce069dee961eded8470ce13fb`; task commits `1b03cfe`, `433691f`, `bf0aef1`, `f9ae82f`, `dd56813` (given
  by the parent, confirmed with `git log --graph` and `git show --stat`), matching the task's `touches`.
- Source/test snapshot identifier (including uncommitted inputs): git blob hashes `HtmlMailSanitizer.kt`
  7c18c0e3, `InlineStyleSanitizer.kt` e8aef3fa (both = HEAD), `HtmlMailSanitizerEdgeCaseTests.kt` b5eaf949
  (modified, uncommitted), `HtmlMailSanitizerTests.kt` 2411fe10, `InlineStyleSanitizerParityTests.kt` fa8dc5ba
  (modified, comment only, uncommitted), `InlineStyleSanitizerTests.kt` e7509cca, `XssCorpusTests.kt` ff190492.
- Exact included files: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/HtmlMailSanitizer.kt`,
  `server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/InlineStyleSanitizer.kt`. No renamed or deleted
  files. Excluded: test files, `reviews/mail-sanitizer/*.md` (reports), `tasks/*.md` (TFP metadata). No scoped file
  is missing from the report.
- Provider/version, command, working directory, and test selection: JaCoCo 0.8.15 (Gradle `jacoco` plugin,
  Gradle 9.8.1, JDK 21.0.12.1 Temurin). In `server/`:
  `./gradlew test --rerun --tests 'ch.sonensei.cockpit.mail.sanitize.*' jacocoTestReport` (only the sanitize package).
- Run start/completion time and exit status: 2026-10-20 09:28:46Z - 09:28:53Z, BUILD SUCCESSFUL (exit 0). JaCoCo
  session `dell26-963e032`, start 1792488528316, dump 1792488533281.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml`
  (sha256 `6713aedf06949e7b9fdb0d6f984a64f2739e4def05126dd1383e620aaa2d0ab9`), `server/build/jacoco/test.exec`
  (sha256 `8a1d890a7a05746a07491c85e77e185cf301d9572ec2698224e915239aca48b3`). Both written at 09:28:53Z by this run.
  A later `.taskcheck` run (`gradlew check`) may have overwritten `test.exec` with full-suite data. The numbers here come
  from the focused run.
- Evidence outputs are fresh and inputs stayed unchanged: `--rerun` forced the test task. The session id and
  timestamps belong to this run. SHA-1 hashes of all 7 sanitize source and test files, taken before the run, still
  matched after it (`sha1sum -c`).
- Final run after latest test additions and production fixes: yes. It ran after my last test edit and on the final
  production code (f9ae82f; no production change after it).

Values below use covered/total (percentage). JaCoCo instructions stand in for statements and methods for functions.

| Changed file | Statements (instructions) | Branches | Functions (methods) | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/HtmlMailSanitizer.kt` | 931/935 (99.6%) | 22/22 (100%) | 15/17 (88.2%) | 136/137 (99.3%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/InlineStyleSanitizer.kt` | 717/717 (100%) | 92/92 (100%) | 8/8 (100%) | 99/99 (100%) |
| **Aggregate (summed counters, 2/2 files measured, none excluded or missing)** | 1648/1652 (99.8%) | 114/114 (100%) | 23/25 (92.0%) | 235/236 (99.6%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `HtmlMailSanitizer.kt:120,125` (`HtmlMailSanitizer$Companion.getINLINE_IMAGE` / `getSafelist`, 2 instructions each; line 125 is the one fully missed line) | None. These are Kotlin-generated accessors of the private companion. The fields themselves are used, and lines 120-214 run in every test | Justified gap. No test added (the accessors are not reachable from outside) |

The fixed `InlineStyleSanitizer.kt` (new `stripComments`, `functionNames`, the newline-in-quote rejection and the length
cap) is fully covered: every line, branch and method.

## Unable to test

- Rendering in a real browser (sandboxed iframe plus CSP, SPEC.md §4.1) is outside this server-only task. The
  `position`-smuggling checks rely on CSS Syntax Level 3 tokenization rules, using the simplified reader in
  `InlineStyleSanitizerParityTests`, not on a browser run. Follow-up: a web/e2e task should render sanitized output in
  the iframe.
- The `@Timeout` 2 s timing tests depend on the machine. They currently finish in milliseconds, so the margin is large.

## Summary

Tests added and all validation passed. Two pipeline-level tests for the f9ae82f behavior were added. The focused
package (390 tests), `./gradlew check` and `.taskcheck` pass. Task-scoped coverage is 100% of branches and lines in
`InlineStyleSanitizer.kt`. In `HtmlMailSanitizer.kt` only two unreachable generated accessors are missed. No production
defects were found.
