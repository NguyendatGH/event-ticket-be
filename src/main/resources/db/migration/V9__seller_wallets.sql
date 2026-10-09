create table seller_wallets (
    organizer_id uuid primary key references organizers(id) on delete cascade,
    balance bigint not null default 0,
    total_topups bigint not null default 0,
    total_sales bigint not null default 0,
    total_refunds bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0
);

create table seller_wallet_transactions (
    id uuid primary key,
    organizer_id uuid not null references organizers(id) on delete cascade,
    type varchar(30) not null check (type in ('TOP_UP', 'PAYMENT_EARNED', 'REFUND_DEBIT')),
    amount bigint not null check (amount > 0),
    balance_after bigint not null,
    ref_type varchar(30),
    ref_id uuid,
    note varchar(500),
    created_at timestamptz not null default now()
);

create unique index uk_seller_wallet_tx_ref
    on seller_wallet_transactions(type, ref_id)
    where ref_id is not null;
create index ix_seller_wallet_tx_org_time
    on seller_wallet_transactions(organizer_id, created_at desc);

insert into seller_wallets(organizer_id)
select id from organizers
on conflict (organizer_id) do nothing;
