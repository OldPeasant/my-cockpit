# My Cockpit

A personal, self-hosted "cockpit for almost everything": tasks (GTD), calendar, email, notes, files,
contacts, bookmarks, journal and habits in one place, for a single owner. It runs on your own Linux
server (Docker Compose: Caddy, a Kotlin/Spring Boot server with the Angular web app, Postgres) and has a
native Android app.

## Quick start (development)

Needs JDK, Node and Docker or Podman ([Install](docs/ARCHITECTURE.md#install)).

```sh
podman run -d -p 5432:5432 -e POSTGRES_DB=cockpit -e POSTGRES_USER=cockpit -e POSTGRES_PASSWORD=cockpit postgres:16-alpine
export COCKPIT_MASTER_KEY=$(openssl rand -base64 32)    # keep it: stored credentials need the same key
(cd server && ./gradlew bootRun --args='--spring.profiles.active=dev') &
cd web && npm ci && npm start                           # http://localhost:4200, first visit runs the setup
sh scripts/check.sh                                     # from the repo root: lint + unit tests of all parts
```

More commands (Android, e2e tests, coverage): [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) "Run" and "Testing".

## Deploy

On your server: generate `.env` with `sh deploy/gen-env.sh <your-domain>`, then
`docker compose -f deploy/docker-compose.yml up -d --build`. Details, backups and updates:
[deploy/README.md](deploy/README.md).

## Documents

- [SPEC.md](SPEC.md): product and technical specification, the source of truth
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): stack, install, run, test, conventions
- [TEAM.md](TEAM.md): how to run the AI agents that build this project
- [AGENTS.md](AGENTS.md): the task protocol those agents follow
