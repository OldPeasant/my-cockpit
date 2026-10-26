# Code Review

Date: 2026-10-26

Task: download-spa-404 - Unknown /download/* paths return 404, not the SPA index.html

Scope: task commits `git diff 05ac0db..bb74d4b` (committed changes only)

Requirements:
- tasks/download-spa-404.md acceptance criteria
- AGENTS.md project notes and conventions
- docs/ARCHITECTURE.md conventions (lint, test layout)
- Consistency with `core/auth/infra/SecurityConfiguration.kt` and `core/download/api/AndroidDownloadController.kt`

Git range:
- Branch: main
- HEAD: bb74d4b7ee2233add5e977708440441ee4e66827
- Base/start: 05ac0db06be2a6c87cae50660041d3c8e276df23
- Commits reviewed: 1

Summary: 0 P0, 0 P1, 0 P2, 0 P3

## P0 Critical

None.

## P1 High

None.

## P2 Medium

None.

## P3 Low

None.

## Verification notes

- Criterion 1: `SpaWebConfiguration.kt:54` now has `SERVER_PREFIXES = listOf("api", "actuator", "download")`, and the
  class KDoc (lines 15-17) names `/download/` as a server prefix. `isClientRoute` compares the first path segment
  exactly, so `download`, `download/` and `download/ios` are server paths, while a lookalike such as `downloads`
  is still a client route. The new test checks both cases.
- The prefix list now matches `SecurityConfiguration.kt:73`, which already requires a login for `/download/**`
  (KDoc line 31). `AndroidDownloadController` maps `/download/android` and `/download/android/version`. Controller
  mappings are checked before the `/**` resource handler, so known download endpoints are not affected. No Angular
  route uses a `download` path, so no client route is hidden by this change.
- Web test: `SpaFallbackTests.unknown download path is 404, not index html` runs MockMvc as `@WithCockpitUser`
  against `/download/ios`, `/download/` and `/download` and expects 404. This is the logged-in case the criterion
  names. `SpaFallbackResourceResolverTests` covers the resolver directly.
- Criterion 2: I could not run Gradle in this review. The parent reported
  `./gradlew ktlintCheck test --tests 'ch.sonensei.cockpit.core.spa.*'` -> BUILD SUCCESSFUL (19 tests, 0 failures),
  and that 3 of those tests fail when the fix is reverted. The added assertions fit that claim: each one
  exercises the new prefix.
- No secrets, no dependency changes, no changes outside the task's `touches`.

## Review conclusion

No material issues found.
