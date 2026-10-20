# 0001: Web login sessions are stored in the database

Status: accepted (p0-auth-login, 2026-10-20)

## Context

SPEC.md §4.1 asks for a session cookie with a 30-day sliding lifetime and a "remember device" option. Servlet
`HttpSession`s live in the server's memory and are lost on every restart or deploy; Spring Session JDBC would add a
library and its own schema for a single-user application.

## Decision

- A login creates a row in `auth_session` (module `core.auth`). The cookie `COCKPIT_SESSION` carries a random
  256-bit token; only its SHA-256 hash is stored.
- `SessionAuthenticationFilter` authenticates every request from that cookie. Spring Security runs stateless: no
  `HttpSession`, no `JSESSIONID`.
- Expiry slides on use (written at most once per minute): 30 days with "remember device" (persistent cookie, re-issued
  when it slides), 12 hours without use otherwise (browser-session cookie).
- CSRF is Spring Security's SPA double-submit cookie (`XSRF-TOKEN` / `X-XSRF-TOKEN`), independent of the session.

## Consequences

- Sessions survive restarts; logout and later device revocation are a row delete.
- One indexed lookup per authenticated request.
- The Android bearer tokens (p0-auth-tokens) can follow the same pattern (hashed opaque tokens in a table).
