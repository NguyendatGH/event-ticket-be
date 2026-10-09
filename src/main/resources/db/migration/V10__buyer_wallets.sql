create table buyer_wallets (
    user_id uuid primary key references users(id) on delete cascade,
    balance bigint not null default 0,
    total_topups bigint not null default 0,
    total_spent bigint not null default 0,
    total_refunds bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0
);

create table buyer_wallet_transactions (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    type varchar(30) not null check (type in ('TOP_UP', 'ORDER_PAYMENT', 'REFUND_CREDIT')),
    amount bigint not null check (amount > 0),
    balance_after bigint not null,
    ref_type varchar(30),
    ref_id uuid,
    note varchar(500),
    created_at timestamptz not null default now()
);

create unique index uk_buyer_wallet_tx_ref
    on buyer_wallet_transactions(type, ref_id)
    where ref_id is not null;
create index ix_buyer_wallet_tx_user_time
    on buyer_wallet_transactions(user_id, created_at desc);

insert into buyer_wallets(user_id)
select id from users
on conflict (user_id) do nothing;
