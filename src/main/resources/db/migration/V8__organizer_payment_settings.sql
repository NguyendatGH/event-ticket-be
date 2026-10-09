create table if not exists organizer_payment_settings (
    organizer_id uuid primary key references organizers(id) on delete cascade,
    gateway varchar(30) not null check (gateway in ('PAYOS', 'BANKSIM')),
    payment_methods jsonb not null default '["CARD"]'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
