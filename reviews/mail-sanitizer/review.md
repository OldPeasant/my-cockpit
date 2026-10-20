# Code Review

Date: 2026-10-20

Task: mail-sanitizer - HTML mail sanitizer

Scope: final task scope `git diff e6e35a9..be9864d -- server/` (merge-base with origin/main to HEAD), committed changes only. This covers `server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/{HtmlMailSanitizer,InlineStyleSanitizer}.kt` and the five test classes in `server/src/test/kotlin/ch/sonensei/cockpit/mail/sanitize/`. This replaces the round-2 review of the pre-rebase revision 3b9d491.

Reviewed revision: be9864d73144e2fda2c9576f093eec0423dbacac

Requirements:
- tasks/mail-sanitizer.md (three acceptance criteria and the three recorded assumptions)
- SPEC.md §4.1 (HTML email is untrusted: jsoup Safelist plus custom rules, sandboxed iframe, remote images blocked by default or proxied)
- docs/ARCHITECTURE.md (stack table lists jsoup 1.21.2), AGENTS.md project notes (conventions, lint, fictitious test data)
- reviews/mail-sanitizer/fixes.md (dispositions of round-1 findings CR-1..CR-3)

Git range:
- Branch: main
- HEAD: be9864d73144e2fda2c9576f093eec0423dbacac
- Base/start: e6e35a90bb83fcf841a0cbe04f666b06a0337414
- Commits reviewed: 6 (6dec2a5, 1474b10, c2c7d0a, e19b4fb, 4f298e3, be9864d)
- Uncommitted changes: none

Relation to the earlier review: `git diff 3b9d491 HEAD` over the sanitize source and test folders shows no change to production code. Only tests were added: `InlineStyleSanitizerParityTests.kt` (new, 45 tests) and two tests in `HtmlMailSanitizerEdgeCaseTests.kt`. In this history the fix commit is e19b4fb. The pre-rebase copies 3b9d491 and f9ae82f exist only outside main (f9ae82f is on `origin/wip/mail-sanitizer`).

Verification run by this review:
- `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.mail.sanitize.*' ktlintCheck` -> BUILD SUCCESSFUL. The test task actually ran (not up to date). JUnit XML: HtmlMailSanitizerTests 12, HtmlMailSanitizerEdgeCaseTests 32, InlineStyleSanitizerTests 71, InlineStyleSanitizerParityTests 45, XssCorpusTests 230; 390 tests, 0 failures, 0 errors, 0 skipped. ktlint was clean.
- `.taskcheck` was not run by this review; the parent worker runs it.

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## Acceptance criteria

1. Sanitizer behavior: met by the code.
   - `DROPPED_WITH_CONTENT` (`HtmlMailSanitizer.kt:114-117`) removes scripts, forms and form controls, iframes/frames, object/embed, style/link/meta/base, svg/math and media elements together with their content, and the jsoup `Cleaner` with the explicit safelist (`:125-214`) drops every other tag and every `on*` attribute.
   - Link protocols are limited to http/https/mailto/tel, so `javascript:` and `data:` hrefs are removed. Images: http/https are blocked (`src` removed, `data-cockpit-blocked-image`) or proxied through `ImageProxyUrlBuilder`, `cid:` is kept, `data:` is kept only for base64 raster types (`INLINE_IMAGE`), and anything else is removed (`:86-108`).
   - Layout tables keep their presentational attributes. Inline styles go through `InlineStyleSanitizer`, which drops `position`, `behavior`/`-moz-binding`, and any declaration with a function other than an allowlisted value function (so `url()`, `image-set()`, `var()` and `expression()` are dropped). It also drops declarations containing escapes, comments, `@`, `<`/`>` or `javascript:`.
2. Test corpus: met. `XssCorpusTests` runs OWASP cheat-sheet vectors under both policies and concatenated, and `HtmlMailSanitizerTests` covers fictitious newsletters (`example` domains, invented names).
3. Focused Gradle tests: pass (run above). No persistence is involved, so Testcontainers does not apply.

Evidence note (not a finding): `python tasks.py verify mail-sanitizer` currently shows all three criteria unrecorded. The parent worker must record evidence before `done`.

## Round-1 finding dispositions (re-checked at be9864d)

- [x] **CR-1 | `position` bypass through a newline inside a quoted CSS string: still resolved.** `splitDeclarations` returns no declarations when LF, CR or FF appears inside an open quote (`InlineStyleSanitizer.kt:145`). It works on the comment-stripped string that is emitted. New tests strengthen this: a single-quoted CR variant, the HTML-level `&#13;&#10;` (CRLF) variant that rejects the style of only its own element, and two randomized property checks (50 000 cases each). Those checks run an independent simplified CSS Syntax 3 declaration reader on the output and confirm no `position` property appears. A self-test shows the reader detects the round-1 bug output.
- [x] **CR-2 | Quadratic comment stripping: still resolved.** `stripComments` (`:71-85`) and `functionNames` (`:107-125`) are linear scans, and the 16 KiB cap (`:58`, `:62`) bounds the rest. The new parity tests compare both scans against the replaced regexes as oracles: 24 fixed function-name cases plus 20 000 random values, and 11 fixed comment cases plus 20 000 random styles. This covers the non-obvious inputs (`-9rgb(`, `--(`, `urlé(`, vertical tab before `(`, `/*/`). One test also checks the cap boundary (cap + 1 is dropped) at both levels, and the element and its text are kept.
- [x] **CR-3 | Removing `<style>` blocks was an unrecorded decision: still resolved.** It is the third ASSUMPTION in tasks/mail-sanitizer.md.

## Additional checks (no findings)

- Since production code is byte-identical to 3b9d491, the bypass analysis of the round-2 review still applies: function-name extraction vs. browser tokenization, whitespace before `(`, case folding with Locale.ROOT, CSS escapes rejected, and string and block nesting agreement on the emitted output.
- Output idempotence of the style sanitizer is asserted in the random property test (`sanitize(output) == output`).
- `SanitizeOptions` rejects PROXY without a builder (`HtmlMailSanitizer.kt:28-32`), so the `!!` at `:99` cannot fail.
- No logging and no secrets in the changed code. The test data uses fictitious `example` domains.
- The new tests assert behavior, not implementation details. The regex oracles are used only as references. Random tests use fixed seeds, so they are reproducible.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-4 | Test comment references a commit that is not in the task history**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/mail/sanitize/InlineStyleSanitizerParityTests.kt:17`
    - Category: Quality
    - Problem: The comment says "The regexes used before commit f9ae82f". f9ae82f is a pre-rebase copy of the fix that exists only on `origin/wip/mail-sanitizer`. In the history that will reach main, the fix is e19b4fb.
    - Why it matters: Once the wip branch is deleted, the reference points nowhere, and a reader who wants to see the replaced code cannot find it. The test itself is correct.
    - Suggested fix: Reference e19b4fb, or describe it without a hash (for example "the regexes replaced by the CR-2 fix").

## Review conclusion

No material issues found.

At be9864d73144e2fda2c9576f093eec0423dbacac, all three acceptance criteria are met by the code and tests, and the focused test suite passes (390 tests). The round-1 fixes for CR-1, CR-2 and CR-3 still hold. The only finding is a P3 comment cleanup that does not block completion. The parent worker still has to record the `tasks.py verify` evidence and run `.taskcheck`. If any code changes after this revision, this review is stale.
