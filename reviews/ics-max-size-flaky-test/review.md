# Code Review

Date: 2026-10-26

Task: ics-max-size-flaky-test - IcsFeedClient: refusing an oversized Content-Length sometimes surfaces as IOException ('could not be reached') instead of 'larger than'

Scope: `git diff 1c1b4a592..HEAD` (committed changes only), files under `server/src/main/kotlin/ch/sonensei/cockpit/calendar/infra/ics` and `server/src/test/kotlin/ch/sonensei/cockpit/calendar/infra/ics`

Requirements:
- tasks/ics-max-size-flaky-test.md (acceptance criteria)
- AGENTS.md project notes and conventions
- Parent instructions (Gradle not run by the reviewer; parent reported `cd server && ./gradlew test --tests '*IcsFeedClientTests'` -> passed on 32cfd157e)

Git range:
- Branch: main
- HEAD: 32cfd157e481140be41ef33009c55de2b340194c
- Base/start: 1c1b4a592bc7fd87df935362becd5062b9709efb
- Commits reviewed: 2 (e071cc836, 32cfd157e)

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Review notes

What I checked and why there is no finding:

- **Criterion 1 (the refusal always wins).** `IcsFeedClient.send` makes a new `AtomicReference` for each request.
  The `tooLarge` lambda that `body()` passes to `LimitedBody` stores the exception in it. `LimitedBody.refuse()` calls
  that lambda (so the volatile `set` happens) before `subscription.cancel()`. Only that cancel can make the HttpClient
  fail the exchange with its own IOException. Completing the future and returning from `future.get` creates a
  happens-before edge, so the `ExecutionException` handler always sees the stored refusal, whichever failure completed
  the future first. Both refusal paths go through `refuse()`: refused at once from the Content-Length, and refused
  mid-stream in `onNext`. Both are covered.
- **Stale refusal masking another error.** This can't happen. The reference belongs to one `send` call, so each
  redirect hop gets a new one. It is only set once the body has gone over the limit. In that case "larger than" is the
  correct message, and any later transport error comes from the cancel. The timeout and interrupt paths don't read the
  reference. A refused exchange completes its future right away, so a timeout can't realistically hide a refusal.
- **Thread-safety.** `AtomicReference` is set on the client's executor thread and read on the caller thread after
  `future.get`, so it is safe. `LimitedBody` itself did not change.
- **Gzip path.** With `Content-Encoding: gzip`, the code still never refuses at once. The encoded-size limit in
  `onNext` now also records the refusal. The limit on the decompressed size (`read()` -> `tooLarge()`) runs outside
  `send` and throws directly, as before. Behavior there is unchanged.
- **Non-2xx bodies.** These still use `BodySubscribers.replacing` and never touch the reference.
- **Tests.** The new tests repeat the refused Content-Length 50 times and the mid-stream refusal 20 times, using a
  1 MiB chunked body that dribbles in 4 chunks over 4 ms against a 16 KiB limit. Each fetch asserts the "larger than"
  message. They test the behavior the user sees, not internals. The fix is deterministic and doesn't depend on timing,
  so these tests are regression guards, not timing-dependent assertions. Existing tests were not changed or weakened.
  Runtime cost is small.
- **Conventions.** The change is small and stays inside the task's `touches`. Messages contain no URL or body content,
  and nothing new is logged.

Evidence status (for the parent, not a code finding): when I reviewed, `tasks.py verify ics-max-size-flaky-test`
showed both criteria as unverified. Criterion 2 asks for several consecutive runs of the full
`server/gradlew -p server check`. The only run reported to me is the class run on its own. That is not evidence for
criterion 2. It has to be run as named, or skipped with a reason.

## Review conclusion

No material issues found.
