-- Kênh nhận tiền của BTC trên gateway BankSim: mỗi kênh = MỘT terminal của merchant BTC đó, gắn một ngân hàng
-- (acquirer của gateway) và tài khoản nhận tiền tại chính ngân hàng đó. Phương thức của kênh nằm trên terminal
-- (gateway là nguồn sự thật), Encore chỉ nhớ terminal nào là kênh nào để chọn terminal theo phương thức khách bấm.
-- Một ngân hàng chỉ một dòng mỗi BTC: xóa rồi thêm lại thì mở lại đúng dòng cũ (và terminal cũ), không đẻ terminal mới.
-- REMOVED = Encore thôi dùng, terminal trên gateway VẪN ACTIVE để đơn cũ của kênh đó còn tra trạng thái / hoàn tiền được.
create table organizer_payment_channels (
    id                  uuid primary key,
    organizer_id        uuid not null references organizers (id),
    gateway_terminal_id varchar(32) not null,
    bank_code           varchar(64) not null,
    bank_bin            varchar(6) not null,
    bank_name           varchar(120) not null,
    account_name        varchar(120) not null,
    account_number      varchar(30) not null,
    status              varchar(16) not null default 'ACTIVE',
    -- Lúc mở (hoặc mở lại) kênh. Kênh ACTIVE mở sớm nhất = kênh chính: tài khoản của nó cũng là settlement của merchant.
    opened_at           timestamptz not null default now(),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    constraint ck_payment_channels_status check (status in ('ACTIVE', 'REMOVED')),
    constraint ck_payment_channels_bin check (bank_bin ~ '^[0-9]{6}$'),
    constraint ck_payment_channels_account check (account_number ~ '^[0-9]{6,30}$'),
    constraint uk_payment_channels_terminal unique (gateway_terminal_id),
    constraint uk_payment_channels_bank unique (organizer_id, bank_code)
);

create index ix_payment_channels_active on organizer_payment_channels (organizer_id, opened_at) where status = 'ACTIVE';
