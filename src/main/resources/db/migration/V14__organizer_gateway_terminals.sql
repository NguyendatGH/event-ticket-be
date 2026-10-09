-- Mỗi BTC một merchant riêng trên gateway, mỗi merchant nhiều terminal.
-- Trước đây toàn bộ BTC dùng chung MER000001/GT000001 trong .env: một dòng DB hỏng là cả hệ thống
-- mất khả năng thanh toán VÀ hoàn tiền, và gateway không phân biệt được doanh thu của BTC nào.

create table organizer_gateway_merchants (
    organizer_id uuid primary key references organizers(id) on delete cascade,
    provider varchar(30) not null,
    mer_no varchar(32) not null,
    -- Gateway chỉ trả secret plaintext đúng một lần lúc tạo, nên bắt buộc phải giữ lại.
    secret_ciphertext text not null,
    status varchar(16) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_ogm_status check (status in ('ACTIVE', 'INACTIVE')),
    constraint uk_ogm_provider_mer unique (provider, mer_no)
);

create table organizer_gateway_terminals (
    id uuid primary key,
    organizer_id uuid not null references organizer_gateway_merchants(organizer_id) on delete cascade,
    terminal_id varchar(32) not null,
    name varchar(120) not null,
    channel varchar(16) not null,
    three_ds_policy varchar(16),
    routing_profile_code varchar(64),
    is_default boolean not null default false,
    status varchar(16) not null default 'ACTIVE',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_ogt_channel check (channel in ('WEB', 'MOBILE_APP', 'API', 'POS')),
    constraint ck_ogt_3ds check (three_ds_policy is null or three_ds_policy in ('REQUIRED', 'OPTIONAL', 'DISABLED')),
    constraint ck_ogt_status check (status in ('ACTIVE', 'INACTIVE')),
    constraint uk_ogt_terminal unique (terminal_id)
);

create index ix_ogt_owner on organizer_gateway_terminals(organizer_id, created_at desc);

-- Mỗi BTC đúng một terminal mặc định — đây là cái dùng khi tạo link thanh toán.
create unique index uk_ogt_default on organizer_gateway_terminals(organizer_id) where is_default;
