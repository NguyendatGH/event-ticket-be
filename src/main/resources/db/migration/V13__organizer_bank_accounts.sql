create table organizer_bank_accounts (
    id uuid primary key,
    organizer_id uuid not null references organizers(id) on delete cascade,
    bank_name varchar(120) not null,
    bank_bin varchar(6),
    account_name varchar(120) not null,
    account_number varchar(30) not null,
    is_default boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_organizer_bank_account_number check (account_number ~ '^[0-9]{6,30}$'),
    constraint ck_organizer_bank_bin check (bank_bin is null or bank_bin ~ '^[0-9]{6}$')
);

create index ix_organizer_bank_accounts_owner on organizer_bank_accounts(organizer_id, created_at desc);
create unique index uk_organizer_bank_account_default
    on organizer_bank_accounts(organizer_id) where is_default;
