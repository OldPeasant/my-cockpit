-- OAuth connections (SPEC.md §7.1): one row per provider account (Google, Microsoft), reused by calendar, mail and
-- contacts. refresh_token is SecretCipher ciphertext (AES-256-GCM, SPEC.md §4.1); it is null when the connection
-- must be reconnected (e.g. restored from a backup without secrets). Access tokens are only kept in memory.
create table oauth_connection (
    id                uuid primary key,
    created_at        timestamptz not null,
    updated_at        timestamptz not null,
    version           bigint      not null,
    provider          text        not null,
    subject           text        not null,
    email             text,
    display_name      text,
    scopes            text        not null,
    refresh_token     text,
    status            text        not null,
    last_error        text,
    last_refreshed_at timestamptz
);

create unique index oauth_connection_provider_subject on oauth_connection (provider, subject);

-- Pending authorization-code flows: started by /oauth/{provider}/start, consumed once by /callback.
-- state_hash is the SHA-256 of the state parameter, session_binding a hash of the browser's session token, and
-- code_verifier (PKCE) is SecretCipher ciphertext. Rows expire after a few minutes.
create table oauth_authorization (
    id              uuid primary key,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    version         bigint      not null,
    provider        text        not null,
    purpose         text        not null,
    state_hash      text        not null,
    session_binding text        not null,
    code_verifier   text        not null,
    expires_at      timestamptz not null
);

create unique index oauth_authorization_state_hash on oauth_authorization (state_hash);
create index oauth_authorization_expires_at on oauth_authorization (expires_at);
