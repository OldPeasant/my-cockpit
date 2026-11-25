---
title: Accept proxy content-coding suffix in If-Match (Caddy gzip breaks saves)
status: in_progress
owner: dell26/agent-1
deps: []
touches: [server/src/main/kotlin/ch/sonensei/cockpit/core/web/ETags.kt,server/src/test/kotlin/ch/sonensei/cockpit/core/web,SPEC.md]
prio: 1
lease_until: 2026-11-25T07:29:17Z
---
## Goal
Saves, sends and deletes work behind the production Caddy proxy. Caddy's `encode zstd gzip` (`deploy/Caddyfile`)
rewrites a strong `ETag` of a compressed response (>= 512 bytes) from `"42"` to `"42-gzip"` / `"42-zstd"` (`"-br"`
for brotli), and passes a client's `If-Match: "42-gzip"` to the server unchanged. `ETags.checkIfMatch` compares it
strictly with `"42"`, so every write that sends back such an ETag gets 412. Seen in production: replying to a mail in
the Android app fails with "draft could not be saved" (PUT /mail/drafts/{id} 412, the retry after re-reading 412 again),
and the draft cannot be discarded either. The same hits every client that takes the version from the `ETag` header
(Android mail compose, files, journal, notes, tasks, ...; web mail compose). Reproduced with `docker.io/library/caddy:2`
v2.11.7: `Etag: "42"` -> `"42-gzip"` (Accept-Encoding: gzip) / `"42-zstd"` (browser); `If-Match` reaches the backend as sent.

## Acceptance criteria
- [ ] `ETags.checkIfMatch` treats a tag that is a strong ETag of the version plus a content-coding suffix added by a
      proxy (`"<version>-gzip"`, `"<version>-zstd"`, `"<version>-br"`) as matching that version; weak tags (`W/...`),
      other versions, other suffixes and garbage still give 412, a missing header still gives 428, `*` still matches
- [ ] Unit tests in `ETagsTests` cover the suffixed tags (match and stale version), a list of tags with a suffixed one,
      and the cases that must still fail
- [ ] A web test through a real controller (e.g. the draft update or a simpler entity's PATCH/DELETE) shows that
      `If-Match: "<version>-gzip"` is accepted
- [ ] Verified against Caddy: the server (`./gradlew bootRun`, or the e2e image) behind `caddy:2` with the production
      `encode zstd gzip` + `reverse_proxy` config; a write of an entity whose response is >= 512 bytes, using the ETag
      Caddy returned (`curl --compressed`), succeeds instead of 412. Record the commands and output with `verify`
- [ ] SPEC.md §4.2 / the `ETags` KDoc note that a proxy's content-coding suffix is accepted, and why

## Notes
- Server fix only: clients keep sending back the ETag exactly as received, so they need no change.
- Stripping the suffix in Caddy is not possible (it passes `If-Match` unchanged); excluding `/api/*` from `encode`
  would also work but loses API compression, so it is not part of this task.
- The e2e stack has no Caddy (`deploy/docker-compose.e2e.yml`), which is why no test caught this.
