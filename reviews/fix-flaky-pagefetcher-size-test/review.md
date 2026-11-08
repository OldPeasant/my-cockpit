# Code Review

Date: 2026-11-08

Task: fix-flaky-pagefetcher-size-test - Flaky test: PageFetcherSizeLimitTests HTTP/2 reuse case gets TIMEOUT for some of 50 fetches

Scope: final task code, `git diff 382ad9732ddb5dcdb02926378b2f3adbbb984cda..aa1cb2417043a4ee897cc6a59c49b90cd9dee706` restricted to code (task commits b16342e64 and aa1cb2417; `tasks/` and `reviews/` changes excluded). HEAD d5eafb0d0 is a merge that adds only `tasks/` changes on top of aa1cb2417. Reviewed by reading only: Gradle was not run, as the parent worker instructed (it runs Gradle in parallel).

Requirements:
- tasks/fix-flaky-pagefetcher-size-test.md (acceptance criteria, recorded evidence, assumption, release note)
- AGENTS.md project notes (conventions, lint, assumptions, evidence rules)
- SPEC.md, docs/ and server sources searched for any HTTP/2 requirement on page fetching (`git grep` for HTTP/2, HTTP_2, h2c): none found
- Previous review of this task (CR-1, written for 85d491a81)
- Parent-supplied fact: mutation check. With `.version(HttpClient.Version.HTTP_1_1)` commented out, the protocol test fails with `expected:["HTTP/1.1", "HTTP/1.1"] but was:["HTTP/2.0", "HTTP/2.0"]`. With the line restored, it passes.

Git range:
- Branch: main
- HEAD: d5eafb0d0c7345e9ce501d824ff6a1ab695c72c1 (reviewed code revision: aa1cb2417043a4ee897cc6a59c49b90cd9dee706)
- Base/start: 382ad9732ddb5dcdb02926378b2f3adbbb984cda
- Commits reviewed: 2 (b16342e64, aa1cb2417)

Summary: 0 P0, 0 P1, 0 P2, 1 P3

## What was reviewed

- `server/src/main/kotlin/ch/sonensei/cockpit/bookmarks/infra/PageFetcher.kt` (read in full). The shared `HttpClient` is now built with `.version(HttpClient.Version.HTTP_1_1)`, and a comment explains why. Two comments that said "(seen with HTTP/2)" are now protocol-neutral. Cancellation still happens in `send`'s `finally`, after the response has been handed out. This still matters over HTTP/1.1. No change to SSRF checks, redirects, timeouts, size limits or error mapping.
- `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/PageFetcherSizeLimitTests.kt` (read in full):
  - The repeated-fetch test was renamed to describe behavior instead of protocol. REPEATS went from 50 to 200, and the 1 s connect timeout is unchanged.
  - The new protocol test fetches twice and asserts that both WireMock serve events are `HTTP/1.1`. `@BeforeEach resetAll()` clears the request journal, so only this test's two requests are counted. Both expected values are the same, so the newest-first order of `allServeEvents` does not matter.

Acceptance criteria assessment:
- Root cause found and fixed, not just retried: met. The fix avoids the failing path, which is HTTP/2 stream cancels on a connection shared with later fetches. It adds no retry, no longer timeout and no looser assertion. The assumption honestly records that the exact JDK-vs-Jetty mechanism was not pinned down. That is acceptable because the fix removes the mechanism instead of masking it. Production impact: plain-http hosts no longer get `Upgrade: h2c`, and HTTPS ALPN offers only `http/1.1`, which public web servers support. Each truncated page costs one new connection, which does not matter for background metadata and snapshot fetches.
- 10 focused runs in a row: the recorded evidence is for 85d491a81, before aa1cb2417 added the two-fetch protocol test. The release note says the parent plans to re-run the loop on the final code. It should do so before `done`, so the evidence matches the final test file. This is a note for the parent, not a finding: the added test is deterministic and small.
- `sh scripts/check.sh` passes: not yet verified. Still open for the parent worker.

CR-1 (previous review, P3, "Protocol regression test may not catch HTTP/2 on a fresh connection"): resolved. The test now fetches twice and asserts both requests are `HTTP/1.1`. The parent's mutation check shows the test fails without `.version(HTTP_1_1)` and passes with it. The test now really guards against removing the setting.

No security, integration, architecture or lint concerns in the diff.

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

- [ ] **CR-2 | Protocol test KDoc states an unverified, and apparently wrong, reason for the second fetch**
    - File: `server/src/test/kotlin/ch/sonensei/cockpit/bookmarks/PageFetcherSizeLimitTests.kt:108`
    - Category: Quality
    - Problem: The KDoc says WireMock records the first request of an h2c upgrade as HTTP/1.1, so only the second request shows HTTP/2.0, and "one fetch alone would pass with an HTTP/2 client too". The parent's mutation run contradicts this: without the fix, both serve events were `HTTP/2.0`. There are two possible explanations. Jetty may report the upgraded request as HTTP/2.0 after all. Or, because the `fetcher` is shared (`PER_CLASS`), an earlier test in JUnit's method order may already have opened an HTTP/2 connection that this test reuses. Either way, the comment's stated mechanism was not observed, and its claim about a single fetch is unproven.
    - Why it matters: The assertion is still correct and catches the regression, so behavior is not affected. But the comment teaches a wrong fact about WireMock/Jetty h2c handling. A later maintainer could trust it, for example by dropping to one fetch when the order changes, or by copying the reasoning elsewhere. That is why this is only P3.
    - Suggested fix: Rewrite the KDoc so it does not claim a specific server behavior. For example: "Two fetches, so the second one would reuse an HTTP/2 connection if the client negotiated one (h2c upgrade); whether the server reports the upgrade request itself as HTTP/1.1 or HTTP/2.0 does not matter then." The code can stay as it is.

## Review conclusion

No material issues found. CR-1 from the previous review is resolved and confirmed by the parent's mutation check. One optional P3 comment-accuracy fix remains (CR-2). The parent worker still needs to re-run the 10-run loop on the final code and verify `sh scripts/check.sh`.
