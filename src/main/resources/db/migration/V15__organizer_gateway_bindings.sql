-- V14 dựng 2 bảng (merchant + N terminal phía Encore). Spec yêu cầu Encore chỉ giữ MỘT binding
-- cho mỗi (organizer, provider); việc quản lý nhiều terminal thuộc Gateway Admin Portal.
-- V14 chưa có dòng nào nên drop an toàn.
drop table if exists organizer_gateway_terminals;
drop table if exists organizer_gateway_merchants;

create table organizer_gateway_bindings (
    id uuid primary key,
    organizer_id uuid not null references organizers(id) on delete cascade,
    provider varchar(30) not null,
    gateway_merchant_no varchar(32),
    gateway_terminal_id varchar(32),
    -- Không bao giờ plaintext: secret này cho phép tạo lệnh thu tiền nhân danh BTC.
    encrypted_merchant_secret text,
    status varchar(16) not null default 'PENDING',
    external_reference varchar(128),
    last_sync_at timestamptz,
    provisioning_error text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_ogb_provider check (provider in ('PAYOS', 'BANKSIM')),
    constraint ck_ogb_status check (status in ('PENDING', 'ACTIVE', 'SUSPENDED', 'FAILED')),
    constraint uk_ogb_organizer_provider unique (organizer_id, provider)
);

create index ix_ogb_provider_status on organizer_gateway_bindings(provider, status);
