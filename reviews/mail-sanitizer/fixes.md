# Fix Report

Date: 2026-10-20

Task: mail-sanitizer - HTML mail sanitizer

Scope: current task. Findings CR-1, CR-2 and CR-3 from `reviews/mail-sanitizer/review.md`, and TW-1 from `reviews/mail-sanitizer/tests.md`, as supplied by the parent worker.

## Fixed findings

### CR-1 / TW-1 — `position` bypass through a newline inside a quoted CSS string

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/InlineStyleSanitizer.kt`

Problem:
`splitDeclarations` kept a quoted string open across a raw LF, CR or FF. Browsers end a CSS string there (bad-string, CSS Syntax 3 §4.3.5) and parse what follows as new declarations. So `font-family: "a<LF>; position: fixed; ... x: "` was kept as one harmless-looking declaration, and the browser applied `position: fixed`.

Fix:
In `splitDeclarations`, a LF, CR or FF inside a quote now rejects the whole style attribute (returns no declarations). Keeping only part of the style is not safe here, because once the browser and the sanitizer disagree on where the string ends, they also disagree on every later declaration. A newline outside quotes is still plain whitespace. The 4 failing regression tests from the test-writer were left unchanged and now pass: `InlineStyleSanitizerTests` with `\n`, `\r` and `\f`, and `HtmlMailSanitizerEdgeCaseTests.newline in a quoted style value cannot smuggle position through the html sanitizer`. I added one test, `a newline outside quotes is plain whitespace`.

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sanitize.*' ktlintCheck` — Pass

### CR-2 — Quadratic-time comment stripping on untrusted style attributes

Files changed:
- `server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/InlineStyleSanitizer.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/sanitize/InlineStyleSanitizerTests.kt`

Problem:
The regex `/\*.*?\*/` with DOT_MATCHES_ALL runs in O(n^2) on many unclosed `/*`. While I was fixing this, a timing test exposed a second quadratic path of the same kind: `functionCall = (-?[a-z_][a-z0-9_-]*)\s*\(` with `findAll` takes about 2.1 s on one 16 KiB identifier that has no `(`.

Fix:
- `stripComments` is now a linear `indexOf` loop. It keeps the old behavior: each closed comment becomes a space, and an unclosed `/*` is left in place so that `forbiddenInValue` drops its declaration.
- The `functionCall` regex is replaced by `functionNames`, a linear scan. For every `(`, it skips the regex whitespace before it, takes the run of name characters, and returns the name from the leftmost valid start in that run. This gives the same names as the regex `findAll`, including names hidden behind other name characters, such as `-1url(` giving `url`.
- Style attributes longer than `MAX_STYLE_LENGTH` (16 KiB) are dropped whole. Real inline styles, including newsletter ones, are far shorter, and the cap bounds the remaining per-declaration work.
- New tests:
  - `many unclosed comments just below the length cap are handled in linear time` (@Timeout 2 s, runs in about 4 ms);
  - `a huge hostile style is dropped quickly` (`"/*"` x 100 000 and `"a"` x 1 000 000, @Timeout 2 s);
  - `a style at the length cap is still sanitized` (@Timeout 2 s; about 2.1 s before the `functionNames` change, about 5 ms after);
  - `a forbidden function name is found also behind other name characters` (4 cases, parity with the old regex).

Validation:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sanitize.*' ktlintCheck` — Pass

## Not fixed

### CR-3 — Removing embedded `<style>` blocks is an unrecorded product decision

Status:
Out of scope

Reason:
No code change is needed. This is a TFP assumption (`tasks.py assume`) that the parent worker records. The fixer may not change task state.

## Validation summary

Focused tests:
- `cd server && ./gradlew ktlintFormat` — Pass (no remaining changes)
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sanitize.*' ktlintCheck` — Pass: 343 tests, 0 failures, 0 skipped (HtmlMailSanitizerTests 12, HtmlMailSanitizerEdgeCaseTests 30, InlineStyleSanitizerTests 71, XssCorpusTests 230)

Full test suite:
- `cd server && ./gradlew check` — Pass (BUILD SUCCESSFUL)

Lint/type-check/build:
- `cd server && ./gradlew ktlintCheck` — Pass (also part of `./gradlew check`)

`.taskcheck`:
- Not run: the sandbox denied permission to run `sh scripts/check.sh`. The parent worker must run it.

Remaining unresolved findings:
0 (CR-3 needs no code change and goes to the parent as an assumption)

## Final status

All validated findings fixed and validation passed. `.taskcheck` is still to be run by the parent worker.
