-- Web login sessions (SPEC.md §4.1). The cookie carries a random token; only its SHA-256 hash is stored.
-- expires_at slides forward on use; remember_device sessions live 30 days, others 12 hours without use.
create table auth_session (
    id              uuid primary key,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    version         bigint      not null,
    user_id         uuid        not null references app_user (id) on delete cascade,
    token_hash      text        not null,
    remember_device boolean     not null,
    last_seen_at    timestamptz not null,
    expires_at      timestamptz not null
);

create unique index auth_session_token_hash on auth_session (token_hash);
create index auth_session_user_id on auth_session (user_id);
create index auth_session_expires_at on auth_session (expires_at);
