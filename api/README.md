# API contract

`openapi.yaml` is the REST contract between the server and both clients (`docs/ARCHITECTURE.md`
"API between clients and server"). It is **generated** from the server by springdoc
(`/v3/api-docs.yaml`). Never edit it by hand.

## Regenerating

After changing a controller, a DTO or the OpenAPI configuration:

```sh
cd server
UPDATE_OPENAPI=1 ./gradlew test --tests '*OpenApiContractTest'   # or: ./gradlew updateOpenApi
```

Commit the updated `api/openapi.yaml` together with the server change, then regenerate the
clients (web: `cd web && npm run api:gen`; Android generates its client on every build, nothing to commit).

`OpenApiContractTest` runs with every `./gradlew test` (and so in `.taskcheck`): without
`UPDATE_OPENAPI=1` it fails when the committed file differs from what the server generates.

## Merge conflicts

Conflicts in `api/openapi.yaml` (and in `web/src/app/api/generated/`) are **never resolved by
hand**. Take either side (`git checkout --theirs api/openapi.yaml`), finish the merge of the
server code, then regenerate the file as above (and the clients) and commit the result.

## Conventions (SPEC.md §4.2)

Implemented in `server/src/main/kotlin/ch/sonensei/cockpit/core/web/`:

- **Errors:** RFC 9457 Problem Details (`application/problem+json`, schema `ProblemDetail`).
  Validation → 400 with `errors: [{field, message}]`; not found → 404; conflict → 409;
  stale `If-Match` → 412; missing `If-Match` → 428; anything unexpected → a generic 500.
  Messages and stack traces of internal exceptions are never sent. Throw `NotFoundException`,
  `ConflictException`, `InvalidRequestException`, ... from `ApiExceptions.kt`.
- **Optimistic locking:** responses carry `ETag: "<version>"` (`ETags.ok`); PATCH and DELETE
  require `If-Match` with that value (`ETags.checkIfMatch`). `*` matches any version.
- **Pagination:** `?cursor=&limit=` (default 50, 1–200, else 400), response
  `{ "items": [...], "nextCursor": "..." | null }` (`CursorRequest.of`, `CursorPage`). Cursors are
  opaque to clients.
