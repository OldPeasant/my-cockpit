-- Web Push (SPEC.md §4.6). Neither table is in the backup: a restore empties both, the server then generates new VAPID
-- keys and browsers subscribe again (subscriptions of the old keys would be rejected by the push services anyway).

-- The server's VAPID key pair (RFC 8292), generated at first start; at most one row.
-- private_key is the PKCS#8 key, encrypted with the master key (EncryptedSecret).
create table push_vapid_key (
    id          uuid primary key,
    created_at  timestamptz not null,
    updated_at  timestamptz not null,
    version     bigint      not null,
    singleton   boolean     not null default true,
    public_key  text        not null,
    private_key text        not null,
    constraint push_vapid_key_singleton unique (singleton),
    constraint push_vapid_key_singleton_true check (singleton)
);

-- Browsers subscribed to Web Push (PushSubscription of the Push API). The endpoint is a capability URL of the browser's
-- push service: whoever knows it (with the keys) can send notifications to that browser.
create table push_subscription (
    id          uuid primary key,
    created_at  timestamptz not null,
    updated_at  timestamptz not null,
    version     bigint      not null,
    endpoint    text        not null,
    p256dh      text        not null,
    auth        text        not null,
    constraint push_subscription_endpoint unique (endpoint)
);
