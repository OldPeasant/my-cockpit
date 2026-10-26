-- CardDAV contacts sync (SPEC.md §8.3, source CARDDAV). Kept apart from contact so a sync run does not change a
-- contact's version (ETag) unless its data changed.

-- One CardDAV account: the URL it was configured with (an address book, or the server/principal to discover the first
-- address book from), the login (password encrypted with the master key, never returned), the address book found,
-- the RFC 6578 sync token of the last complete run (null: the next run lists everything), and the outcome of the last
-- attempt (the error is safe to show: no secrets, no contact data).
create table contacts_carddav_account (
    id              uuid primary key,
    name            text not null,
    url             text not null,
    username        text,
    password        text,
    addressbook_url text,
    sync_token      text,
    last_attempt_at timestamptz,
    last_success_at timestamptz,
    last_error      text
);

-- Which card a contact is: href is the card's path at the server, etag the card's ETag as last seen (null: the card
-- does not exist at the server (any more), the next push creates it with If-None-Match). synced_version is the
-- contact version that matches the card; a higher version is a local change to push.
-- Purging the contact sets contact_id to null: the row stays as a tombstone until the next push has deleted the card,
-- and keeps a pull from importing it again meanwhile.
create table contacts_carddav_link (
    id             uuid primary key,
    contact_id     uuid unique references contact (id) on delete set null,
    account_id     uuid   not null references contacts_carddav_account (id) on delete cascade,
    href           text   not null,
    etag           text,
    synced_version bigint not null
);

create unique index contacts_carddav_link_href on contacts_carddav_link (account_id, href);
