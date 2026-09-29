-- Schema gốc theo be/database-schema.md (ERD mục 1) + phần mở rộng cho FE (mục "Thay đổi khi triển khai").
-- Tiền: bigint VND. Thời gian: timestamptz. Trạng thái: varchar + check. Payload thô: jsonb.

create table users (
    id            uuid primary key,
    full_name     varchar(200) not null,
    email         varchar(200) not null,
    password_hash varchar(100) not null,
    role          varchar(20)  not null check (role in ('CUSTOMER', 'ADMIN')),
    created_at    timestamptz  not null default now(),
    constraint uk_users_email unique (email)
);

create table events (
    id                    uuid primary key,
    slug                  varchar(200) not null,
    name                  varchar(200) not null,
    category              varchar(50)  not null,
    starts_at             timestamptz  not null,
    ends_at               timestamptz,
    refund_deadline_hours int          not null default 48,
    status                varchar(20)  not null check (status in ('DRAFT', 'PUBLISHED', 'UPCOMING', 'CANCELLED')),
    venue                 jsonb,
    cover_image_url       text,
    cover_image_alt       text,
    tagline               text,
    description           jsonb,
    schedule              jsonb,
    organizer             jsonb,
    featured              boolean      not null default false,
    created_at            timestamptz  not null default now(),
    updated_at            timestamptz  not null default now(),
    constraint uk_events_slug unique (slug)
);
create index ix_events_status_starts_at on events (status, starts_at);

create table ticket_tiers (
    id             uuid primary key,
    event_id       uuid         not null references events (id),
    name           varchar(200) not null,
    description    text,
    price          bigint       not null check (price >= 0),
    total_quantity int          not null check (total_quantity >= 0),
    max_per_order  int          not null default 6 check (max_per_order > 0),
    created_at     timestamptz  not null default now()
);
create index ix_ticket_tiers_event on ticket_tiers (event_id);

create table inventory (
    ticket_tier_id uuid primary key references ticket_tiers (id),
    available      int         not null check (available >= 0),
    version        bigint      not null default 0,
    updated_at     timestamptz not null default now()
);

create table orders (
    id              uuid primary key,
    order_code      bigint       not null,
    event_id        uuid         not null references events (id),
    status          varchar(30)  not null check (status in ('PENDING_PAYMENT', 'PAID', 'REFUND_PROCESSING', 'REFUNDED',
                                                            'PARTIALLY_REFUNDED', 'REFUND_FAILED', 'EXPIRED', 'CANCELLED', 'MANUAL_REVIEW')),
    subtotal_amount bigint       not null,
    fee_amount      bigint       not null default 0,
    total_amount    bigint       not null,
    paid_amount     bigint       not null default 0,
    refunded_amount bigint       not null default 0,
    customer_name   varchar(200) not null,
    customer_email  varchar(200) not null,
    customer_phone  varchar(30),
    expires_at      timestamptz  not null,
    idempotency_key varchar(100),
    version         bigint       not null default 0,
    created_at      timestamptz  not null default now(),
    updated_at      timestamptz  not null default now(),
    constraint uk_orders_order_code unique (order_code),
    constraint uk_orders_idempotency_key unique (idempotency_key)
);
create index ix_orders_status_expires_at on orders (status, expires_at);

create table order_items (
    id             uuid primary key,
    order_id       uuid         not null references orders (id) on delete cascade,
    ticket_tier_id uuid         not null references ticket_tiers (id),
    tier_name      varchar(200) not null,
    quantity       int          not null check (quantity > 0),
    unit_price     bigint       not null
);
create index ix_order_items_order on order_items (order_id);

create table tickets (
    id             uuid primary key,
    order_id       uuid         not null references orders (id),
    ticket_tier_id uuid         not null references ticket_tiers (id),
    price          bigint       not null,
    ticket_code    varchar(100) not null,
    status         varchar(20)  not null check (status in ('ACTIVE', 'REFUND_PENDING', 'REFUNDED')),
    issued_at      timestamptz  not null default now(),
    updated_at     timestamptz  not null default now(),
    constraint uk_tickets_ticket_code unique (ticket_code)
);
create index ix_tickets_order_status on tickets (order_id, status);

create table payments (
    id                       uuid primary key,
    order_id                 uuid        not null references orders (id),
    provider                 varchar(20) not null,
    provider_payment_id      varchar(100),
    payment_link_id          varchar(100),
    checkout_url             text,
    provider_transaction_ref varchar(100),
    amount                   bigint      not null,
    status                   varchar(20) not null check (status in ('CREATED', 'PENDING', 'PAID', 'UNDERPAID', 'PAID_LATE', 'FAILED', 'EXPIRED')),
    payer_bank_bin           varchar(20),
    payer_account_number     varchar(255),   -- mã hóa AES ở tầng ứng dụng
    paid_at                  timestamptz,
    raw_webhook_payload      jsonb,
    created_at               timestamptz not null default now(),
    updated_at               timestamptz not null default now(),
    constraint uk_payments_provider_payment unique (provider, provider_payment_id)
);
create index ix_payments_order on payments (order_id);

create table refunds (
    id                              uuid primary key,
    order_id                        uuid        not null references orders (id),
    payment_id                      uuid        references payments (id),
    original_payment_transaction_id varchar(100),
    amount                          bigint      not null,
    currency                        char(3)     not null default 'VND',
    status                          varchar(20) not null check (status in ('REQUESTED', 'AWAITING_FUNDS', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'MANUAL_REVIEW')),
    execution_method                varchar(20) check (execution_method in ('PAYOUT', 'NATIVE_REFUND', 'MANUAL_TRANSFER')),
    initiator                       varchar(20) not null check (initiator in ('USER', 'SYSTEM', 'ADMIN')),
    provider                        varchar(20),
    provider_refund_id              varchar(100),
    idempotency_key                 varchar(100),
    attempt                         int         not null default 1,
    failure_code                    varchar(50),
    failure_reason                  text,
    destination_bin                 varchar(20),
    destination_account             varchar(255),   -- mã hóa AES ở tầng ứng dụng
    destination_is_payer            boolean,
    reason                          text,
    submitted_at                    timestamptz,
    last_polled_at                  timestamptz,
    queued_since                    timestamptz,
    created_at                      timestamptz not null default now(),
    updated_at                      timestamptz not null default now(),
    constraint uk_refunds_idempotency_key unique (idempotency_key),
    constraint uk_refunds_provider_refund unique (provider, provider_refund_id)
);
create index ix_refunds_status_created_at on refunds (status, created_at);

create table refund_items (
    id        uuid primary key,
    refund_id uuid   not null references refunds (id) on delete cascade,
    ticket_id uuid   not null references tickets (id),
    amount    bigint not null,
    constraint uk_refund_items_refund_ticket unique (refund_id, ticket_id)
);

create table refund_inquiries (
    id          uuid primary key,
    refund_id   uuid        not null references refunds (id),
    message     text        not null,
    created_at  timestamptz not null default now(),
    resolved_at timestamptz
);

-- Bốn bảng sổ ghi chép dưới đây cố ý không có khóa ngoại: phải ghi được cả khi bản ghi nghiệp vụ bị từ chối.
create table webhook_events (
    id                uuid primary key,
    provider          varchar(20)  not null,
    event_id          varchar(200) not null,
    event_type        varchar(50),
    raw_payload       jsonb,
    signature_valid   boolean      not null,
    processing_result varchar(30)  check (processing_result in ('PROCESSED', 'DUPLICATE', 'IGNORED', 'REJECTED_SIGNATURE', 'FAILED')),
    received_at       timestamptz  not null default now(),
    processed_at      timestamptz,
    constraint uk_webhook_events_provider_event unique (provider, event_id)
);

create table idempotency_records (
    id              uuid primary key,
    scope           varchar(20)  not null check (scope in ('CHECKOUT', 'REFUND')),
    idem_key        varchar(100) not null,
    request_hash    varchar(64)  not null,
    response_status int          not null,
    response_body   jsonb,
    created_at      timestamptz  not null default now(),
    constraint uk_idempotency_scope_key unique (scope, idem_key)
);

create table gateway_call_logs (
    id             uuid primary key,
    ref_type       varchar(20) check (ref_type in ('ORDER', 'PAYMENT', 'REFUND')),
    ref_id         uuid,
    direction      varchar(10) not null check (direction in ('OUTBOUND', 'INBOUND')),
    endpoint       text        not null,
    request_masked jsonb,
    response_raw   jsonb,
    http_status    int,
    duration_ms    int,
    trace_id       varchar(64),
    created_at     timestamptz not null default now()
);
create index ix_gateway_call_logs_ref on gateway_call_logs (ref_type, ref_id);
create index ix_gateway_call_logs_trace on gateway_call_logs (trace_id);

create table wallet_snapshots (
    id        uuid primary key,
    taken_at  timestamptz   not null default now(),
    balance   bigint        not null,
    committed bigint        not null,
    available bigint        not null,
    liability bigint        not null,
    coverage  numeric(6, 2)
);

create table ledger_entries (
    id          uuid primary key,
    account     varchar(30) not null check (account in ('BANK_COLLECTION', 'PAYOUT_WALLET', 'CUSTOMER_LIABILITY', 'FEES')),
    direction   varchar(10) not null check (direction in ('DEBIT', 'CREDIT')),
    amount      bigint      not null,
    ref_type    varchar(20),
    ref_id      uuid,
    occurred_at timestamptz not null default now()
);
create index ix_ledger_entries_account_time on ledger_entries (account, occurred_at);
