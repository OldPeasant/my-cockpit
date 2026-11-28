# Test Report

Date: 2026-11-28

Task: mail-apple-inline-images - Mail: show unreferenced inline images and text after images (Apple Mail)

Scope: round 2. `git diff 10e5329f19cb29feec8f8ee78e81143e63dc8569..0e007fe02c3b1287d79b3cf763a062dea78b35f6 -- server` (task commits on main: 55161954c implementation, 368bc5e0a tests, fb0603fe1 test-writer round 1, 70f2f5745 TW-1 fix; the other commits since the base touch only `tasks/` and `reviews/`). Production files: `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt`, `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt`, `server/src/main/resources/db/migration/mail/V202611280930__mail_reparse_bodies.sql`. The only production change since round 1 is 70f2f5745 (`MimeContent.cidReferences` also matches the HTML-unescaped form of each `cid:` reference). Uncommitted at measurement: only the two test files listed below.

Requirements:
- `tasks/mail-apple-inline-images.md` acceptance criteria, Notes and the recorded assumptions
- `AGENTS.md`, `docs/ARCHITECTURE.md` ("Coverage")
- parent instructions: round 1 (forged part references, cid in related not duplicated, percent-encoded cid, size limit per text part); round 2 (check the TW-1 fix, named entities such as `&amp;` in a cid, mixed entity and percent encoding)

## Test files changed

- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MimeContentTests.kt` (round 1: 11 tests added, one parameterized with 4 cases; round 2: 1 test added)
- `server/src/test/kotlin/ch/sonensei/cockpit/mail/api/MailReadServiceInlineImagesTests.kt` (round 1: new, 4 tests; round 2: 1 test added)

## Tests added or updated

Count: 17 in total (round 1: 15, round 2: 2). No existing test changed or weakened.

Round 2:
- MimeContentTests `cid references with named, hex or bracket character references, also mixed with percent escapes, stay inline`: `cid:a&amp;b@example.test` (named entity, Content-ID `a&b@example.test`), `cid:hex&#x40;example.test` (hex reference), `cid:mixed%40example&#46;test` (percent escape plus decimal reference in one cid), `cid:&lt;angle@example.test&gt;` (escaped angle brackets). All four parts stay `inline = true` and the HTML body is not changed (no appended image).
- MailReadServiceInlineImagesTests `a cid reference written with character references shows its image once, through the inline download`: end to end from a parsed MIME message through `MailReadService.read` with the real sanitizer; the body has exactly one img element, pointing at the inline download of part 2, and the part is listed `inline = true`. This checks that MimeContent (regex over the raw HTML) and MailReadService (Jsoup-decoded attribute) agree on the same reference.

Round 1 (MimeContentTests):
- `cid references that are percent-encoded, bracketed or in another case keep their images inline, shown once` (also `+` and a malformed `%` escape)
- `a cid reference written with HTML character references keeps its image inline, shown once` (TW-1; failed in round 1, passes now)
- `a cid image in the related HTML variant of an alternative stays inline and is not appended`
- `an alternative without an HTML variant is shown as its escaped plain text next to HTML parts`
- `alternatives with only one text variant still frame a positioned image`
- `the size limit applies per text part, so joined text may exceed it`
- `unreferenced inline raster images of every shown type are put into the body` (png, gif, webp, upper-case JPEG)
- `SVG and raster parts with attachment disposition are listed but not put into the body`
- `the file name of a positioned image is escaped in its alt text`
- `an image before the first text and text with CRLF line ends keep their order and breaks`
- `partImage references round-trip and only well-formed part ids are accepted`

Round 1 (MailReadServiceInlineImagesTests, MailReadService.read with mocked locator/loader and the real HtmlMailSanitizer):
- `the reported Apple Mail message shows its image through the inline download, after the text`
- `a forged part reference only resolves to a raster part no cid references`
- `a mail forging part references to its own PDF or cid image shows neither`
- `a plain-text-only mail with an image gets an escaped HTML body with the image below the text`

## Requirement coverage

| Requirement | Tests | Command | Result |
|---|---|---|---|
| AC1 reported Apple structure: image listed `inline = false`, img element to inline download | MimeContentTests `the reported Apple Mail structure ...` (existing); MailReadServiceInlineImagesTests `the reported Apple Mail message shows its image ...`; MailReadGreenMailTests `an Apple Mail inline image without Content-ID ...` (existing) | `cd server && ./gradlew check jacocoTestReport --continue`; `cd server && ./gradlew integrationTest --tests '*MailReadGreenMailTests'` | Pass |
| AC2 cid image in related unchanged: inline, at cid position, not listed, not appended | MimeContentTests `mixed, related and alternative parts ...` (existing), `cid references that are percent-encoded, bracketed or in another case ...`, `a cid image in the related HTML variant ...`, `a cid reference written with HTML character references ...` (TW-1), `cid references with named, hex or bracket character references ...`; MailReadServiceInlineImagesTests `a cid reference written with character references shows its image once ...` | `cd server && ./gradlew test --tests '*MimeContentTests' --tests '*MailReadServiceInlineImagesTests' --tests '*MailReadGreenMailTests'` | Pass |
| AC3 text after an image, plain-only and alternatives, `textPlain` with both parts | MimeContentTests `text after an image ... plain only`, `... as alternatives` (existing), `an alternative without an HTML variant ...`, `alternatives with only one text variant ...`, `an image before the first text ...` | same focused command | Pass |
| AC4 plain-only mail + image: escaped text, image below, no injection | MimeContentTests `text after an image ... plain only` (existing), `the file name of a positioned image is escaped ...`; MailReadServiceInlineImagesTests `a plain-text-only mail with an image ...` | same focused command | Pass |
| AC5 cached bodies re-parsed via migration | MailReadGreenMailTests `a body cached without its attachment row ...` (existing; migration applied by Flyway in every Postgres test context) | `cd server && ./gradlew integrationTest --tests '*MailReadGreenMailTests'` | Pass (9/9) |
| AC6 non-raster/unknown inline parts listed, never img element | MimeContentTests `a plain-only mail with a non-raster inline part ...`, `a non-raster inline part next to HTML ...` (existing), `SVG and raster parts with attachment disposition ...`; MailReadServiceInlineImagesTests `a forged part reference only resolves ...`, `a mail forging part references ...` | same focused command | Pass |
| AC7 unit tests + GreenMail test; `./gradlew check` green; OpenAPI unchanged | all of the above; OpenApiContractTest in the full suite | `cd server && ./gradlew check jacocoTestReport --continue` | Pass: 5321 tests, 0 failures, 0 errors, 0 skipped; ktlint Pass |
| Note: maxTextSize per text part, joined text may exceed it | MimeContentTests `the size limit applies per text part ...` | same focused command | Pass |
| Assumption: synthetic references resolve only to raster, non-cid-referenced parts of the same message | MailReadServiceInlineImagesTests `a forged part reference only resolves ...`, `a mail forging part references ...`; MimeContentTests `partImage references round-trip ...` | same focused command | Pass |

## Focused test execution

Command:

`cd server && ./gradlew test --tests '*MimeContentTests' --tests '*MailReadServiceInlineImagesTests' --tests '*MailReadGreenMailTests'`, then `cd server && ./gradlew integrationTest --tests '*MailReadGreenMailTests'` (the GreenMail class runs in the `integrationTest` task, not in `test`, so the first command selects no GreenMail tests)

Result:

Pass: MimeContentTests 60/60 (including TW-1), MailReadServiceInlineImagesTests 5/5, MailReadGreenMailTests 9/9.

## Full test execution

Command:

`cd server && ./gradlew check jacocoTestReport --continue`

Result:

Pass: BUILD SUCCESSFUL, `test` 5321 tests completed, 0 failures, 0 errors, 0 skipped (round 1: 5319 with 1 failure; +2 round-2 tests); ktlintMainSourceSetCheck and ktlintTestSourceSetCheck Pass. Web and Android were not run: the task changes the server only.

## .taskcheck

Result:

Not run by the test-writer: `scripts/check.sh` lints and tests all three parts; the server part (`./gradlew check`) was run above and passes. The parent runs `.taskcheck` before `done`.

## Bugs found

### A cid reference written with HTML character references is treated as unreferenced: image shown twice and listed as attachment (RESOLVED)

Finding ID:

`TW-1`

Test:

`MimeContentTests` > `a cid reference written with HTML character references keeps its image inline, shown once`

Production source:

`server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt` `cidReferences` (round 1 line 241, now lines 245-260)

Requirement:

AC2: a `cid:`-referenced image in `multipart/related` stays `inline = true`, is shown at the `cid:` position, is not listed and not appended a second time.

Expected behavior:

`<img src="cid:pic&#64;example.test">` references Content-ID `pic@example.test`, so the part stays `inline = true` and the HTML body is unchanged.

Actual behavior:

Round 1: the part was listed `inline = false` and a second image was appended, so the image showed twice. Round 2: fixed by 70f2f5745 (`cidReferences` adds `Parser.unescapeEntities(raw, true)` of each match before the bracket stripping and percent decoding). The TW-1 test passes, and the round-2 tests confirm named (`&amp;`), hex (`&#x40;`), escaped angle brackets (`&lt;`/`&gt;`) and mixed entity plus percent encoding, plus the end-to-end single image through MailReadService.

Severity:

Low

Fix required:

No longer required: resolved in 70f2f5745 and verified by the tests above.

No other production defects found in round 2.

## Coverage

Status: verified (fresh run, exit 0, on HEAD 0e007fe02 plus the round-2 test files).

Metric: whole-file coverage of task-changed production files.
Changed-line coverage: not measured with tooling (none configured). Manual check against the JaCoCo line data: every executable line of the TW-1 fix (`MimeContent.kt` 245-260) has covered instructions and no missed instructions or branches; all lines added in `MailReadService.kt` are covered (143/143 lines).

Evidence:
- Task id, immutable base, measured HEAD, and task-owned commits/file-scope source: mail-apple-inline-images; base 10e5329f19cb29feec8f8ee78e81143e63dc8569 (given by the parent); HEAD 0e007fe02c3b1287d79b3cf763a062dea78b35f6; task server commits 55161954c, 368bc5e0a, fb0603fe1, 70f2f5745 (`git log 10e5329f1..HEAD -- server`).
- Source/test snapshot identifier (including uncommitted inputs): sha256 MimeContent.kt 46f5815b545d1c6a56d5a55a37165d4b70874fa76faaf8791a4d5d3cf1df2427, MailReadService.kt be5830a29e5f2e05114208275d9beaae90165913c6f290830090ac30ac68db24, MimeContentTests.kt 52b7eb428c3ddda0e9b74791590844a0f2739b5c23d6f0db8d328389ba69d5e9, MailReadServiceInlineImagesTests.kt 74b38ee84b2070efbc5745894054500d888687b578f27b5b3365acb56655d922; identical before (11:08:01) and after the run.
- Exact included files; renamed/deleted/excluded files and reasons: included `MimeContent.kt`, `MailReadService.kt`. Excluded `V202611280930__mail_reparse_bodies.sql` (SQL migration, not instrumentable by JaCoCo; covered behaviorally by the GreenMail re-read test, which passes). No renames or deletions.
- Provider/version, command, working directory, and test selection: JaCoCo via the Gradle `jacoco` plugin (project default version), Gradle 9.8.1; `./gradlew check jacocoTestReport --continue`; working directory `server/`; all `test` task tests (the `integrationTest` GreenMail tests do not contribute).
- Run start/completion time and exit status: started after 2026-11-28T11:08:01+01:00, exec written 11:11:36, report written 11:11:38; exit 0 (BUILD SUCCESSFUL in 3m 32s).
- Generated report paths and report hashes/run identifier: `server/build/reports/jacoco/test/jacocoTestReport.xml` sha256 d353335019a6c1b78de2116e204d8266b1c6996e49110878ddf4c3dd06cf0794; exec `server/build/jacoco/test.exec` sha256 1bbb4cb43a93d1636ea3d8ce2f63cb8c603b81924868aa4cc98ece89d0e8c064.
- Evidence outputs are fresh and inputs stayed unchanged: yes (the test task ran for the changed test sources, exec and report written during this run, input hashes re-checked after it).
- Final run after latest test additions and production fixes: yes (after the TW-1 fix and both round-2 tests).

Values below use covered/total (percentage); JaCoCo has no separate statement counter, instructions are shown instead.

| Changed file | Instructions | Branches | Methods | Lines |
|---|---:|---:|---:|---:|
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MimeContent.kt` | 1478/1506 (98.1%) | 184/194 (94.8%) | 53/53 (100%) | 190/192 (99.0%) |
| `server/src/main/kotlin/ch/sonensei/cockpit/mail/api/MailReadService.kt` | 959/964 (99.5%) | 82/84 (97.6%) | 39/39 (100%) | 143/143 (100%) |
| Aggregate (summed counters, 2 measured files; migration excluded) | 2437/2470 (98.7%) | 266/278 (95.7%) | 92/92 (100%) | 333/335 (99.4%) |

(Round 1, before the TW-1 fix and diagnostic only: MimeContent.kt 1455/1483 instructions, 184/194 branches, 52/52 methods, 187/189 lines. The fix added 23 instructions, 1 method and 3 lines, all covered.)

Uncovered locations use the current line numbers (shifted by the fix compared with round 1).

| File and uncovered lines/branch locations | Required behavior or risk | Action/test added or reason left uncovered |
|---|---|---|
| `MimeContent.kt:136` (multipart whose content is no `Multipart`) | defensive, pre-existing behavior | not reachable with parsed or IMAP messages; left |
| `MimeContent.kt:181` (`plainItems` on an `Html` item) | defensive: plain rendering only happens when no HTML exists | unreachable in the current flow; left |
| `MimeContent.kt:220` (`render` on an `Alternative` item) | defensive: items are flattened before rendering | unreachable; left |
| `MimeContent.kt:93-94` (short-circuit branches in the part lookup by id) | malformed or out-of-range part references | behavior covered by `partImage references round-trip ...` and the forged-reference tests; remaining branches are bytecode null paths |
| `MimeContent.kt:219` (one branch) | alt text of a positioned image | null and non-null file names are both tested; remaining branch is generated by string concatenation |
| `MimeContent.kt:269, 271, 293` | encoding/content-id/text fallbacks in `describe` and `text` | pre-existing code, not changed by this task |
| `MailReadService.kt:85, 99` | list paging | pre-existing code, not changed by this task |

## Unable to test

- The OpenAPI contract is only checked through the existing OpenApiContractTest (passes in the full run); no schema change was expected.
- Client behavior (web/Android listing the now `inline = false` image, forwarding) is out of this task's server-only scope and was not tested.
- Observation, no failing test (unchanged from round 1): `cidReferences` also counts a `cid:` text that is not an `<img src>` (for example a CSS `url(cid:...)` or plain text in the HTML). Such a part stays `inline = true` and, if the sanitizer removes that reference, is neither shown nor listed. This matches the behavior before the task, so it is no regression; left for the parent to decide.

## Summary

Tests added and all validation passed. TW-1 is resolved by 70f2f5745 and covered (round-1 test plus two round-2 tests for named, hex, bracket and mixed entity/percent forms, including end to end through MailReadService). `./gradlew check` passes with 5321 tests and 0 failures; task-scoped coverage is verified on the final code.
