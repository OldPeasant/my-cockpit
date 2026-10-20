# Code Review

Date: 2026-10-20

Task: mail-sanitizer - HTML mail sanitizer

Scope: round 2, the full final task scope `git diff b79be70..3b9d491 -- server/` (merge-base with origin/main to HEAD). Committed changes only; uncommitted test edits from a concurrent test-writer were ignored. Round 1 reviewed the same sources before fix commit 3b9d491. This round re-reviews `HtmlMailSanitizer.kt` (unchanged since round 1) and `InlineStyleSanitizer.kt` (changed by the fix), and checks the committed tests.

Reviewed revision: 3b9d49155a97e31595d9db8cac39850c8f5f4416

Requirements:
- tasks/mail-sanitizer.md (acceptance criteria and the three recorded assumptions)
- SPEC.md §4.1 (HTML email is untrusted: jsoup Safelist plus custom rules, sandboxed iframe, remote images blocked by default or proxied)
- docs/ARCHITECTURE.md, AGENTS.md (conventions)
- reviews/mail-sanitizer/fixes.md (the fixer's description of 3b9d491)

Git range:
- Branch: main
- HEAD: 3b9d49155a97e31595d9db8cac39850c8f5f4416
- Base/start: b79be7059f9630ad0eda71a0d41da61780473812
- Commits reviewed: 4 (7bd8e46, bd76b1a, 5397533, 3b9d491)

Files reviewed in full: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/HtmlMailSanitizer.kt`, `.../InlineStyleSanitizer.kt`, and the committed `server/src/test/kotlin/ch/sonensei/cockpit/mail/sanitize/InlineStyleSanitizerTests.kt` (`git show HEAD:`).

Verification note: this environment allowed only file reads and simple git commands, so I did not run the tests myself. The conclusions below come from the code at the reviewed revision, CSS Syntax Level 3 tokenization rules and Java regex semantics. The fixer reports `./gradlew test --tests 'ch.sonensei.cockpit.mail.sanitize.*' ktlintCheck` and `./gradlew check` passing (343 tests) in fixes.md.

Summary: 0 P0, 0 P1, 0 P2, 0 P3 (open findings at the reviewed revision)

## Round 1 finding dispositions

- [x] **CR-1 | `position` filter bypass through a newline inside a quoted CSS string (P1, Security): Resolved.**
  `splitDeclarations` (`InlineStyleSanitizer.kt:145`) now returns no declarations when LF, CR or FF appears inside an open quote, so the whole style attribute is dropped. This matches the CSS rule that ends a string at a newline (CR, CRLF and FF become LF during preprocessing). The check is keyed on `quote != null`, so it covers both single and double quotes. CRLF hits the `\r` first. The splitter runs on the comment-stripped string that is actually analyzed and emitted, so a comment cannot hide a newline from it. Regression tests: `a newline inside a quoted string cannot smuggle a position declaration` (LF, CR, FF) and the HtmlMailSanitizer-level edge-case test listed in fixes.md. A newline outside quotes stays plain whitespace (tested).
- [x] **CR-2 | Quadratic-time comment stripping (P2, Security): Resolved.**
  `stripComments` (`:71-85`) is a single forward `indexOf` loop, and each character is visited a constant number of times. It matches the old regex on edge cases: `/*/` stays unclosed and is kept, so `forbiddenInValue` drops its declaration, while `/**/` is stripped. The fixer also found a second quadratic path (`functionCall` regex with `findAll`) and replaced it with `functionNames` (`:107-125`). That scan is linear because each backward scan stops at the previous `(`, which is neither whitespace nor a name character. `MAX_STYLE_LENGTH` (16 KiB, `:58`, `:62`) bounds the rest of the per-declaration work. Timing tests with `@Timeout` 2 s cover unclosed comments near the cap, a 1 MB attribute and a cap-length identifier.
- [x] **CR-3 | Removing embedded `<style>` blocks was an unrecorded product decision (P3, Correctness): Resolved.**
  The third assumption in tasks/mail-sanitizer.md now records it. No code change was needed.

## Bypass analysis of the new code (no findings)

- **`functionNames` vs. the old regex `(-?[a-z_][a-z0-9_-]*)\s*\(`:** for each `(`, the scan skips the same whitespace class as Java's default `\s` (`regexWhitespace` = space, `\t`, `\n`, `\u000b`, `\f`, `\r`). It then takes the maximal run of ASCII name characters and returns the suffix from the leftmost position where `-?[a-z_]` can start. That is the same name `findAll` yields, because matches cannot overlap a previous `(`. Inputs checked: `-1url(` → `url`, `1-a(` → `-a`, `--foo(` → `-foo` (dropped, not allowlisted), `x (` with whitespace, a run with no valid start (no name, same as the regex). I found no input where the regex returns a name and the scan does not.
- **Can the sanitizer pick an allowlisted name while the browser sees a dangerous function?** The browser's function name is the maximal ident before `(` with no whitespace. Its ASCII part is always a suffix of, or equal to, the name the sanitizer extracts. No allowlisted name has a resource-loading function (`url`, `image`, `image-set`, `src`, `element`, `paint`, `var`, `env`, `attr`, `expression`, ...) as a proper suffix. Also, a token boundary inside an ASCII name run (after digits) produces a dimension token, not a function token.
- **Whitespace variants before `(`:** in CSS, `url (`, `url\t(` or `url\u000b(` is not a function, so skipping that whitespace only makes the check stricter. Unicode spaces (U+00A0, U+2000-U+200A, U+3000) are non-ASCII ident code points in CSS. So `url (` is a function named `url `, which is unknown and invalid, not the special `url(` token. The sanitizer drops it anyway: no ASCII name run touches `(`, so it yields no name. Neither drops a safe-looking dangerous call.
- **Case folding:** checks use `lowercase()` (Locale.ROOT). The only non-ASCII characters that fold to ASCII are KELVIN SIGN → `k` and `İ` → `i` + U+0307. In the browser these stay non-ASCII ident characters, so they can only turn a name into an unknown one, never into a dangerous ASCII name. The emitted property is the lowercased string that was checked.
- **CSS escapes:** `\` in a value is rejected (`forbiddenInValue`), and `propertyName` is ASCII-only. HTML entities are decoded by jsoup before the check. NUL becomes U+FFFD in both HTML and CSS, which only produces unknown names.
- **Do the browser and the sanitizer agree on structure in the emitted string?** Only approved declarations are emitted. Each one has balanced quotes, no backslash, no newline inside quotes and no comments, so string boundaries match the browser's. The sanitizer closes a paren on any `)`. The browser does not close it while a `[`/`{` block is open, so the browser's nesting is never shallower than the sanitizer's. Text the sanitizer kept as nested (for example `;position:fixed` inside `rgb(...)`) therefore cannot become a top-level declaration in the browser. A mismatch can only make the browser swallow later approved declarations, which is safe.
- **`HtmlMailSanitizer.kt`:** unchanged since round 1. The round-1 observations still hold: safelist, link/image protocols, removal together with content, `cid:` and raster `data:` handling, and the PROXY invariant.

Non-blocking observation (not a finding): the CR-1 regression tests use only double quotes and lone `\n`/`\r`/`\f`. Single quotes and CRLF take the same code path (`quote != null`, first char `\r`), so adding them would only add regression breadth.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review conclusion

No material issues found.

At revision 3b9d49155a97e31595d9db8cac39850c8f5f4416, CR-1, CR-2 and CR-3 are resolved, and the new linear parsing code introduces no bypass of the `position`/`url()`/resource-function rules. The parent worker still has to confirm the test run and `.taskcheck` itself; this review did not run them. If any code changes after this revision, this review is stale.
