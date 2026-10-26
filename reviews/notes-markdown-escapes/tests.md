# Test Report

Date: 2026-10-26

Task: notes-markdown-escapes - Robust plain text of note Markdown with backslash escapes

Scope: base `817b3fc44f637e82d3005d33d9bcb79a35ff91e9` .. HEAD `93d118e171daa7d4d6095fd0049d95e130d8a653` (branch `main`, task commits `6a4456c36` (assumption, tasks/ only) and `93d118e17` (code)), as given by the parent worker and confirmed with `git log`/`git diff 817b3fc44..HEAD`. Production files changed: `server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteMarkdown.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt`. Plus the uncommitted test changes of this report (see Coverage evidence).

Requirements:
- `tasks/notes-markdown-escapes.md` acceptance criteria and notes (entity references next to bold/autolinks/hard breaks)
- `AGENTS.md`, `docs/ARCHITECTURE.md` "Coverage"
- Parent worker's list of edge cases

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/notes/NoteMarkdownTests.kt`
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdWebTests.kt`

## Tests added or updated

Count: 16 (15 in `NoteMarkdownTests`, 1 in `MailGtdWebTests`); no existing test changed.

`NoteMarkdownTests`:
- `nested emphasis, strikethrough and escapes inside undefined references keep the brackets`
- `brackets nested in undefined references and links inside brackets keep their text`
- `defined references, links and images keep only their text`
- `code spans keep escapes and entities verbatim next to bold text, links and hard breaks`
- `strikethrough keeps its text with escapes and entities`
- `task list items keep their text without the checkbox, with escapes, entities and autolinks`
- `nested lists and block quotes put every block on its own line`
- `code blocks inside block quotes and lists keep their lines verbatim, without the quote markers`
- `a backslash before a line end breaks the line, an escaped or final backslash stays`
- `the title of a line ending in a backslash hard break has no stray backslash` (FAILS, TW-1)
- `entities at the start and end of lines, blocks and the body resolve, unknown ones stay`
- `inline links are removed next to bold text, escapes, entities and autolinks`
- `the title of a header line with escapes, entities, an autolink and an inline link is its plain text`
- `the title length counts resolved escapes`
- `HTML comments are left out, inline and as blocks`

`MailGtdWebTests`:
- `to-note with escapes and entities next to the bold header, autolinks and hard breaks indexes ungarbled search text` (reads the note's `search_document` title and body and asserts the exact plain text)

All tests run under Gradle's default `enableAssertions = true`, so a flexmark length `AssertionError` would fail them.

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1: plainText/titleOf with escapes mixed with bold, autolinks and hard breaks neither throws (-ea) nor drops/garbles text | existing repro tests plus all new `NoteMarkdownTests` above except TW-1 test; `to-note with escapes and entities ... indexes ungarbled search text` | `cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests 'ch.sonensei.cockpit.mail.gtd.*'` | Pass for plainText; Fail for titleOf with a backslash hard break (TW-1) |
| AC1 (notes): entity/numeric references next to bold/autolinks/hard breaks | `entity and numeric references ...` (existing), `entities at the start and end of lines, blocks and the body resolve, unknown ones stay` | same | Pass |
| AC2: `./gradlew test --tests 'ch.sonensei.cockpit.notes.*'` passes | whole `notes` package | same | Fail: 1 failing test (TW-1); every other notes test passes |
| AC3: mail-gtd keeps escapes verbatim (no U+2216), tests expect the original text, `mail.gtd.*` passes | existing updated escape tests in `MailGtdWebTests`, new search-text test | same | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests 'ch.sonensei.cockpit.mail.gtd.*'`

Result:

Fail: 168 tests, 1 failed (`NoteMarkdownTests.the title of a line ending in a backslash hard break has no stray backslash`, TW-1). All `mail.gtd.*` tests pass.

`cd server && ./gradlew ktlintCheck`: Pass.

## Full test execution

Command:

`cd server && ./gradlew check --continue`

Result:

FULL_SUITE_RESULT

## .taskcheck

Result:

Not run: `sh scripts/check.sh` runs the same server unit tests and fails on the known TW-1 test; the web and Android parts are untouched by this task. Re-run after the fix.

## Bugs found

### titleOf keeps the backslash of a hard line break as a stray character

Finding ID:

`TW-1`

Test:

`NoteMarkdownTests.the title of a line ending in a backslash hard break has no stray backslash`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteMarkdown.kt:136-141` (`titleOf`)

Requirement:

AC1: titleOf of Markdown with escapes mixed with bold, autolinks and hard breaks neither throws nor drops/garbles text. `titleOf` doc: "its first line with text, as plain text".

Expected behavior:

`titleOf("**F:** \\[x <c@d.e>\\\n**T:** \\[y <c@d.e>")` (Kotlin literal; Markdown `**F:** \[x <c@d.e>\` + newline + `**T:** ...`) is `F: [x c@d.e`, the same as the first line of `plainText` of that body.

Actual behavior:

`F: [x c@d.e\` - a trailing backslash. `titleOf` splits the raw Markdown into lines before parsing each one alone, so the backslash that makes a hard line break (CommonMark: `\` before a newline) ends up at the end of a one-line document, where it is a literal backslash. `plainText` of the whole body is correct (`F: [x c@d.e` / `T: [y c@d.e`). The two-space hard break of the original repro is unaffected (trailing spaces are trimmed). Probably pre-existing (line-by-line splitting predates this task), but it falls under AC1's hard-break case.

Severity:

Low (cosmetic stray character in derived titles of notes whose first text line ends in a backslash hard break; mail-gtd sets an explicit title, so its notes are not affected).

Fix required:

`Yes — production code must be fixed by the parent worker / fixer.`

## Coverage

Status: partial-failed - fresh JaCoCo data from the final focused run, whose test command exited 1 only because of the known TW-1 test (all other 167 tests passed). The report was generated from that run's exec file with `./gradlew jacocoTestReport -x test`, because `jacocoTestReport` depends on `test` and is skipped when a test fails. Numbers are diagnostic until TW-1 is fixed and coverage re-run.

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured by intersecting the diff; since every executable line of both files is covered (LINE 62/62 and 148/148), every executable changed line is covered too. Changed-branch coverage not measured.

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: `notes-markdown-escapes`, base `817b3fc44f637e82d3005d33d9bcb79a35ff91e9` (from parent), HEAD `93d118e171daa7d4d6095fd0049d95e130d8a653`; task commits `6a4456c36`, `93d118e17`; file scope from `git diff --name-only 817b3fc44..HEAD -- server/src/main`.
- Source/test snapshot identifier (including uncommitted inputs): HEAD `93d118e17` plus uncommitted test files, git blob hashes: `NoteMarkdownTests.kt` `cdb390d3a791f16fd2b0144b8bf7ffe557ee3329`, `MailGtdWebTests.kt` `4e68dab8a178281446abffa2c986a440b6f95b49`; production blobs unchanged: `NoteMarkdown.kt` `a350dce2c9b57401be0f27eeedafa1aaed07e1a9`, `MailGtdService.kt` `e076861b02be19e8dd061493feda99ba473de2cd`.
- Exact included files; renamed/deleted/excluded files and reasons: included `server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteMarkdown.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt`. Excluded: `tasks/notes-markdown-escapes.md` (task metadata), test files. No renamed or deleted files.
- Provider/version, command, working directory, and test selection: JaCoCo via Gradle `jacoco` plugin (project default version); `./gradlew test --tests 'ch.sonensei.cockpit.notes.*' --tests 'ch.sonensei.cockpit.mail.gtd.*'` then `./gradlew jacocoTestReport -x test`, in `server/`; selection: packages `notes` and `mail.gtd` (168 tests).
- Run start/completion time and exit status: started 2026-10-26T04:17:57+01:00, test exec written 04:18:41, report 04:18:47; test exit 1 (TW-1 only), report exit 0.
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 `758f52b038eaf4aaf0b09142d138f9137c95f4ed93ba4f190fdc6a2aed2359b9`; `server/build/jacoco/test.exec` sha256 `e18f7364885634023e5fe741d61689460541293a7da71fcc2c0abd7ddf2e64ab`.
- Evidence outputs are fresh and inputs stayed unchanged: yes, exec and report timestamps are from this run; source/test blob hashes taken after the run match the measured snapshot (the last test edit was before the run).
- Final run after latest test additions and production fixes: yes for the latest test additions; no production fix exists yet (TW-1 open), so coverage must be re-run after the fix.

Values below use covered/total (percentage); zero-denominator or unsupported metrics are N/A. Statements = JaCoCo INSTRUCTION.

| Changed file | Statements | Branches | Functions | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/notes/domain/NoteMarkdown.kt` | 510/524 (97.3%) | 64/70 (91.4%) | 7/7 (100.0%) | 62/62 (100.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/gtd/MailGtdService.kt` | 1350/1373 (98.3%) | 115/126 (91.3%) | 40/41 (97.6%) | 148/148 (100.0%) |
| Aggregate (summed counters, both files, none missing) | 1860/1897 (98.0%) | 179/196 (91.3%) | 47/48 (97.9%) | 210/210 (100.0%) |

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `NoteMarkdown.kt:98` (1 branch) | `first == null \|\| last == null` of an undefined reference: the combination "first child present, last child null" cannot occur | Justified gap (unreachable); the childless case (`![]`, `![][nope]`) and children case are tested |
| `NoteMarkdown.kt:124` (2 branches) | `node is CodeBlock` / `FencedCodeBlock` short-circuit variants: flexmark's code text sits under `FencedCodeBlock`/`Code`; `IndentedCodeBlock` is handled in its own arm | Justified gap; verbatim code in fenced, indented and inline code is tested, also inside quotes and lists |
| `NoteMarkdown.kt:66` (12 instr, 2 branches), `:140` (1 branch) | `inlineLinks` invalid-UUID fallback and `titleOf` null line: pre-existing code, not changed by this task | Outside task diff; no test added |
| `MailGtdService.kt:106,122,123,126,159,164,233,234,278,280` | Pre-existing null-safety branches (Kotlin `?.`/`?:`), not changed by this task; the changed `neutralizeInlineLinks` (line 264) is fully covered | Outside task diff; no test added |

Earlier run in this session (before the HTML comment test was added, report sha256 `dd1898cd...`): `NoteMarkdown.kt` branches 62/70; the HTML comment test covered the two missed `HtmlInlineComment`/`HtmlCommentBlock` arms at line 114.

## Unable to test

- Nothing required by the acceptance criteria. Whitespace inside padded code spans (``` `` a`b `` ```) is kept with its padding spaces (double space in plain text); this is non-material for search text and was not asserted.

## Summary

Tests added; production defects were discovered and must be passed to the `fixer` before task completion (TW-1, Low: `titleOf` keeps the backslash of a hard line break).
