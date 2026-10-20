# Code Review

Date: 2026-10-20

Task: mail-sanitizer - HTML mail sanitizer

Scope: task commits of `mail-sanitizer` on top of base b10a8de (supplied as `git diff b10a8de..e9882d7`). The commits were since rebased onto main as d2528ab and 5cadcca; `git diff e9882d7 HEAD -- server/src` is empty, so the reviewed source is identical. Committed changes only; uncommitted test files from the concurrent test-writer were ignored.

Requirements:
- tasks/mail-sanitizer.md (acceptance criteria and recorded assumptions)
- SPEC.md §4.1 (HTML email is untrusted: jsoup Safelist + custom rules, sandboxed iframe, remote images blocked by default, proxied)
- docs/ARCHITECTURE.md (code conventions, server packages, coverage)
- AGENTS.md (project notes and conventions)

Git range:
- Branch: main
- HEAD: 5cadcca1644861f8946d30ab5cd6ba763723f980
- Base/start: b10a8deef7c9ef1a1b7cd60260a9d620d5ea0565
- Commits reviewed: 2 (d8fea4b/d2528ab "WIP sanitizer and XSS corpus tests", e9882d7/5cadcca "functional and newsletter tests, ktlint format")

Files reviewed in full: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/HtmlMailSanitizer.kt`, `.../InlineStyleSanitizer.kt`, `server/src/test/kotlin/ch/sonensei/cockpit/mail/sanitize/HtmlMailSanitizerTests.kt`, `.../XssCorpusTests.kt`.

Verification note: the review environment allowed only file reads and a few git commands, so the findings below were not executed. They are derived from the current code and the CSS Syntax Level 3 tokenizer/parser rules and Java regex semantics cited in each finding.

Summary: 0 P0, 1 P1, 1 P2, 1 P3

## P0 Critical

None.

## P1 High

- [ ] **CR-1 | `position` filter bypass: a newline inside a quoted CSS string hides later declarations**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/InlineStyleSanitizer.kt:85` (also 66-73)
    - Category: Security
    - Problem: `splitDeclarations` treats everything after an opening `"` or `'` as quoted until the matching quote char, including newlines. In CSS a string cannot contain a raw newline. When the browser's tokenizer reaches LF (CR and FF are turned into LF before tokenizing), it ends the string as a `<bad-string-token>` and reprocesses the newline (CSS Syntax 3 §4.3.5). Take this input: `style="font-family: &quot;x&#10;; position: fixed; top: 0; left: 0; width: 100%; height: 100%; b: &quot;"`. The sanitizer sees a single declaration, `font-family` with a quoted value. That value contains no `\`, `<`, `>`, `/*`, `@`, `javascript:`, `expression` and no function call, so it is kept verbatim (line 74). The browser drops the invalid `font-family` declaration at the first `;` and then applies `position: fixed; top: 0; ...` as normal declarations. The property deny-list (line 11, checked at 71) only applies to the first property name of each sanitizer-side declaration. `url()` cannot be smuggled this way because `functionCall` also scans quoted text, but `position` (the other explicitly forbidden construct) can.
    - Why it matters: Acceptance criterion 1 ("inline styles minus `position`") is not met for hostile input. A mail can still put fixed or absolutely positioned overlays over the rendered mail view, for example fake buttons or links over the real content. This is the exact threat the rule exists for. The corpus test oracle (`XssCorpusTests.dangerousInStyle`) would catch this, but no vector exercises it.
    - Suggested fix: Make the splitter match CSS tokenization. Either drop any declaration whose quoted string contains `\n`, `\r` or `\f`, or simply reject values containing those characters. Normalizing CR/FF to LF first is an option too. Then add corpus vectors with LF, CR, CRLF and FF inside single- and double-quoted strings, followed by `position: fixed`.

## P2 Medium

- [ ] **CR-2 | Quadratic-time comment stripping lets a crafted style attribute stall sanitization**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/InlineStyleSanitizer.kt:54` (used at 58)
    - Category: Security
    - Problem: `comment = Regex("""/\*.*?\*/""", DOT_MATCHES_ALL)` is applied with `replace` to the whole untrusted style value. When a value has many `/*` openers and no `*/` (e.g. `/* ` repeated), `java.util.regex` tries the lazy `.*?` to the end of the string from every opener before it gives up and moves on. That is O(n^2) in the attribute length. A style attribute of a few hundred KB (easily carried by a mail, and possibly repeated across several elements) means on the order of 10^10 or more character steps on the thread that renders or syncs the message.
    - Why it matters: Anyone who can send the user a mail can make the server burn CPU for a long time whenever that mail is sanitized (message view, and any sync or preview path that reuses the sanitizer). This is a resilience and DoS weakness in a component built specifically to process hostile input.
    - Suggested fix: Strip comments with a linear single-pass scan, i.e. find `/*` with `indexOf` and then `*/` with `indexOf` from there; if it is unclosed, drop the rest. Alternatively, reject any declaration containing `/*` without stripping, since `forbiddenInValue` already drops leftover `/*`. Optionally cap the style attribute length, dropping the attribute if it is too long. Add a test with a large unclosed-comment payload and a timeout (e.g. `assertTimeoutPreemptively`).

## P3 Low

- [ ] **CR-3 | Removing embedded `<style>` blocks is an unrecorded product decision**
    - File: `server/src/main/kotlin/ch/sonensei/cockpit/mail/sanitize/HtmlMailSanitizer.kt:114-117`
    - Category: Correctness
    - Problem: The criterion asks only for *external* CSS to be removed and for inline styles to be kept. The implementation also drops every embedded `<style>` element, and `HtmlMailSanitizerTests` asserts that `@media` rules disappear. This is a reasonable, safe choice (embedded sheets would need a full CSS sanitizer and could restyle the iframe). But it goes beyond the criterion and changes how responsive newsletters render, and it is not among the two assumptions recorded on the task.
    - Why it matters: AGENTS.md requires that decisions SPEC.md leaves open be recorded with `tasks.py assume`, so a human can revisit them. Without that, a later task may "fix" the newsletter layout by re-allowing `<style>` without a CSS sanitizer.
    - Suggested fix: Record an assumption, e.g. "embedded `<style>` blocks are removed together with external CSS; only sanitized inline styles are kept". No code change is needed.

## Review conclusion

Material issues found; fix before completing the task.

Notes on what was checked and found sound: the jsoup `Cleaner` with a tag/attribute safelist (no `id`/`name`, no `background`/`srcset`/`poster`/`lowsrc`/`dynsrc`), protocol checks for `a[href]` (http/https/mailto/tel) and `img[src]`, and the post-clean handling of `img`. Remote images are blocked or proxied, `cid:` is kept, only base64 raster `data:` images are kept, and relative or protocol-relative sources are removed. Script-bearing and remote-loading elements are removed together with their content. Comments are dropped. mXSS through `noscript`/raw-text elements is not possible because none of them survive in the output. `url()`, `image-set()`, `var()`, `attr()` and other non-computing CSS functions, CSS escapes and at-rules are rejected. The `PROXY`-without-builder invariant is enforced. The package `mail.sanitize` deviates from `<module>.{api,domain,infra}`, but the task's `touches` prescribes it, so it is not reported. No secrets or personal data were found in the diff, and the test data is fictitious. Criterion 3 involves no persistence, so Testcontainers does not apply.
