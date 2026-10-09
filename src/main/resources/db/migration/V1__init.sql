-- =====================================================================================================
-- Schema Encore (be/), MỘT migration duy nhất.
--
-- Gộp từ V1..V19 cũ (kiểm chứng bằng cách so `pg_dump --schema-only` của DB dựng bằng 20 migration cũ với DB dựng bằng
-- file này: giống hệt). Các bước chỉ có ý nghĩa với DB cũ đã bỏ: backfill ledger / organizers / ví, xóa dữ liệu bán lại,
-- đổi tên bảng kênh. Muốn thêm thay đổi schema thì thêm V2__..., KHÔNG sửa file này khi đã có DB chạy bằng nó.
--
-- Quy ước: tiền là bigint VND; thời gian là timestamptz; trạng thái là varchar + CHECK (nên thêm giá trị enum
-- phải có migration). Mỗi bảng đi kèm khóa chính, unique và index; khóa ngoại để cuối file.
--
-- Chạy: `set -a; . ./.env; set +a; ./mvnw flyway:migrate` (xem db/README.md). Dữ liệu mẫu: db/seed-dev.sql.
-- =====================================================================================================


-- ====================================================================================================
-- NGƯỜI DÙNG & XÁC THỰC
-- ====================================================================================================

-- users: Tài khoản. role: CUSTOMER | ORGANIZER | ADMIN.
CREATE TABLE users (
    id uuid NOT NULL,
    full_name character varying(200) NOT NULL,
    email character varying(200) NOT NULL,
    password_hash character varying(100) NOT NULL,
    role character varying(20) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    phone character varying(30),
    avatar_url text,
    bio text,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT users_role_check CHECK (role IN ('CUSTOMER', 'ORGANIZER', 'ADMIN'))
);

ALTER TABLE ONLY users
    ADD CONSTRAINT uk_users_email UNIQUE (email);

ALTER TABLE ONLY users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


-- refresh_tokens: Refresh token đăng nhập (lưu hash, thu hồi được).
CREATE TABLE refresh_tokens (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash);

CREATE INDEX ix_refresh_tokens_user ON refresh_tokens USING btree (user_id);

-- password_reset_tokens: Token đặt lại mật khẩu (dùng một lần, có hạn).
CREATE TABLE password_reset_tokens (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    used_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_pkey PRIMARY KEY (id);

ALTER TABLE ONLY password_reset_tokens
    ADD CONSTRAINT uk_password_reset_tokens_hash UNIQUE (token_hash);


-- contact_messages: Tin nhắn từ trang Liên hệ.
CREATE TABLE contact_messages (
    id uuid NOT NULL,
    name character varying(200) NOT NULL,
    email character varying(200) NOT NULL,
    subject character varying(200),
    message text NOT NULL,
    user_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY contact_messages
    ADD CONSTRAINT contact_messages_pkey PRIMARY KEY (id);



-- ====================================================================================================
-- BAN TỔ CHỨC & CỔNG THANH TOÁN
-- ====================================================================================================

-- organizers: Ban tổ chức (BTC). Một BTC là một merchant trên cổng thanh toán.
CREATE TABLE organizers (
    id uuid NOT NULL,
    user_id uuid,
    slug character varying(200) NOT NULL,
    name character varying(200) NOT NULL,
    description text,
    logo_url text,
    cover_url text,
    website text,
    city character varying(100),
    contact_email character varying(200),
    contact_phone character varying(30),
    verified boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE ONLY organizers
    ADD CONSTRAINT organizers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY organizers
    ADD CONSTRAINT uk_organizers_slug UNIQUE (slug);

ALTER TABLE ONLY organizers
    ADD CONSTRAINT uk_organizers_user UNIQUE (user_id);


-- organizer_bank_accounts: Tài khoản nhận tiền một-tài-khoản (chế độ PayOS). Chế độ BankSim: tài khoản nằm ở kênh trên gateway.
CREATE TABLE organizer_bank_accounts (
    id uuid NOT NULL,
    organizer_id uuid NOT NULL,
    bank_name character varying(120) NOT NULL,
    bank_bin character varying(6),
    account_name character varying(120) NOT NULL,
    account_number character varying(30) NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    gateway_synced_at timestamp with time zone,
    CONSTRAINT ck_organizer_bank_account_number CHECK (((account_number)::text ~ '^[0-9]{6,30}$'::text)),
    CONSTRAINT ck_organizer_bank_bin CHECK (((bank_bin IS NULL) OR ((bank_bin)::text ~ '^[0-9]{6}$'::text)))
);

ALTER TABLE ONLY organizer_bank_accounts
    ADD CONSTRAINT organizer_bank_accounts_pkey PRIMARY KEY (id);

CREATE INDEX ix_organizer_bank_accounts_owner ON organizer_bank_accounts USING btree (organizer_id, created_at DESC);
CREATE INDEX ix_organizer_bank_accounts_unsynced ON organizer_bank_accounts USING btree (organizer_id) WHERE (is_default AND (gateway_synced_at IS NULL));
CREATE UNIQUE INDEX uk_organizer_bank_account_default ON organizer_bank_accounts USING btree (organizer_id) WHERE is_default;

-- organizer_gateway_bindings: Liên kết BTC <-> merchant trên gateway: CHỈ merchant no + secret (mã hóa AES-GCM). Terminal, kênh và routing do GATEWAY sở hữu, Encore không lưu.
CREATE TABLE organizer_gateway_bindings (
    id uuid NOT NULL,
    organizer_id uuid NOT NULL,
    provider character varying(30) NOT NULL,
    gateway_merchant_no character varying(32),
    encrypted_merchant_secret text,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    external_reference character varying(128),
    last_sync_at timestamp with time zone,
    provisioning_error text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_ogb_provider CHECK (provider IN ('PAYOS', 'BANKSIM')),
    CONSTRAINT ck_ogb_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'FAILED'))
);

ALTER TABLE ONLY organizer_gateway_bindings
    ADD CONSTRAINT organizer_gateway_bindings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY organizer_gateway_bindings
    ADD CONSTRAINT uk_ogb_organizer_provider UNIQUE (organizer_id, provider);

CREATE INDEX ix_ogb_provider_status ON organizer_gateway_bindings USING btree (provider, status);

-- organizer_payment_settings: [DI SẢN] Thời BTC tự chọn cổng + phương thức. KHÔNG còn code nào dùng, đừng dựng lại.
CREATE TABLE organizer_payment_settings (
    organizer_id uuid NOT NULL,
    gateway character varying(30) NOT NULL,
    payment_methods jsonb DEFAULT '["CARD"]'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT organizer_payment_settings_gateway_check CHECK (gateway IN ('PAYOS', 'BANKSIM'))
);

ALTER TABLE ONLY organizer_payment_settings
    ADD CONSTRAINT organizer_payment_settings_pkey PRIMARY KEY (organizer_id);



-- ====================================================================================================
-- SỰ KIỆN & VÉ
-- ====================================================================================================

-- events: Sự kiện. status: DRAFT | PUBLISHED | UPCOMING | CANCELLED. Nháp chỉ cần name (category, starts_at cho phép NULL).
CREATE TABLE events (
    id uuid NOT NULL,
    slug character varying(200) NOT NULL,
    name character varying(200) NOT NULL,
    category character varying(50),
    starts_at timestamp with time zone,
    ends_at timestamp with time zone,
    refund_deadline_hours integer DEFAULT 48 NOT NULL,
    status character varying(20) NOT NULL,
    venue jsonb,
    cover_image_url text,
    cover_image_alt text,
    tagline text,
    description jsonb,
    schedule jsonb,
    featured boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    organizer_id uuid,
    published_at timestamp with time zone,
    CONSTRAINT events_status_check CHECK (status IN ('DRAFT', 'PUBLISHED', 'UPCOMING', 'CANCELLED'))
);

ALTER TABLE ONLY events
    ADD CONSTRAINT events_pkey PRIMARY KEY (id);

ALTER TABLE ONLY events
    ADD CONSTRAINT uk_events_slug UNIQUE (slug);

CREATE INDEX ix_events_organizer ON events USING btree (organizer_id);
CREATE INDEX ix_events_status_starts_at ON events USING btree (status, starts_at);

-- ticket_tiers: Hạng vé của một sự kiện.
CREATE TABLE ticket_tiers (
    id uuid NOT NULL,
    event_id uuid NOT NULL,
    name character varying(200) NOT NULL,
    description text,
    price bigint NOT NULL,
    total_quantity integer NOT NULL,
    max_per_order integer DEFAULT 6 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ticket_tiers_max_per_order_check CHECK ((max_per_order > 0)),
    CONSTRAINT ticket_tiers_price_check CHECK ((price >= 0)),
    CONSTRAINT ticket_tiers_total_quantity_check CHECK ((total_quantity >= 0))
);

ALTER TABLE ONLY ticket_tiers
    ADD CONSTRAINT ticket_tiers_pkey PRIMARY KEY (id);

CREATE INDEX ix_ticket_tiers_event ON ticket_tiers USING btree (event_id);

-- inventory: Tồn kho theo hạng vé. LUÔN khóa theo ticket_tier_id TĂNG DẦN để tránh deadlock.
CREATE TABLE inventory (
    ticket_tier_id uuid NOT NULL,
    available integer NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT inventory_available_check CHECK ((available >= 0))
);

ALTER TABLE ONLY inventory
    ADD CONSTRAINT inventory_pkey PRIMARY KEY (ticket_tier_id);



-- ====================================================================================================
-- ĐƠN HÀNG
-- ====================================================================================================

-- orders: Đơn hàng. PENDING_PAYMENT -> PAID -> REFUND_PROCESSING -> REFUNDED | PARTIALLY_REFUNDED | REFUND_FAILED; nhánh chết EXPIRED | CANCELLED -> MANUAL_REVIEW.
CREATE TABLE orders (
    id uuid NOT NULL,
    order_code bigint NOT NULL,
    event_id uuid NOT NULL,
    status character varying(30) NOT NULL,
    subtotal_amount bigint NOT NULL,
    fee_amount bigint DEFAULT 0 NOT NULL,
    total_amount bigint NOT NULL,
    paid_amount bigint DEFAULT 0 NOT NULL,
    refunded_amount bigint DEFAULT 0 NOT NULL,
    customer_name character varying(200) NOT NULL,
    customer_email character varying(200) NOT NULL,
    customer_phone character varying(30),
    expires_at timestamp with time zone NOT NULL,
    idempotency_key character varying(100),
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    user_id uuid,
    paid_at timestamp with time zone,
    CONSTRAINT orders_status_check CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'REFUND_PROCESSING', 'REFUNDED', 'PARTIALLY_REFUNDED', 'REFUND_FAILED', 'EXPIRED', 'CANCELLED', 'MANUAL_REVIEW'))
);

ALTER TABLE ONLY orders
    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);

ALTER TABLE ONLY orders
    ADD CONSTRAINT uk_orders_idempotency_key UNIQUE (idempotency_key);

ALTER TABLE ONLY orders
    ADD CONSTRAINT uk_orders_order_code UNIQUE (order_code);

CREATE INDEX ix_orders_paid_at ON orders USING btree (paid_at);
CREATE INDEX ix_orders_status_expires_at ON orders USING btree (status, expires_at);
CREATE INDEX ix_orders_user ON orders USING btree (user_id);

-- order_items: Dòng vé của đơn (chụp tên hạng vé và đơn giá lúc mua).
CREATE TABLE order_items (
    id uuid NOT NULL,
    order_id uuid NOT NULL,
    ticket_tier_id uuid NOT NULL,
    tier_name character varying(200) NOT NULL,
    quantity integer NOT NULL,
    unit_price bigint NOT NULL,
    CONSTRAINT order_items_quantity_check CHECK ((quantity > 0))
);

ALTER TABLE ONLY order_items
    ADD CONSTRAINT order_items_pkey PRIMARY KEY (id);

CREATE INDEX ix_order_items_order ON order_items USING btree (order_id);

-- tickets: Vé đã phát (mã QR). ACTIVE | REFUND_PENDING | REFUNDED.
CREATE TABLE tickets (
    id uuid NOT NULL,
    order_id uuid NOT NULL,
    ticket_tier_id uuid NOT NULL,
    price bigint NOT NULL,
    ticket_code character varying(100) NOT NULL,
    status character varying(20) NOT NULL,
    issued_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    owner_id uuid,
    CONSTRAINT tickets_status_check CHECK (status IN ('ACTIVE', 'REFUND_PENDING', 'REFUNDED'))
);

ALTER TABLE ONLY tickets
    ADD CONSTRAINT tickets_pkey PRIMARY KEY (id);

ALTER TABLE ONLY tickets
    ADD CONSTRAINT uk_tickets_ticket_code UNIQUE (ticket_code);

CREATE INDEX ix_tickets_order_status ON tickets USING btree (order_id, status);
CREATE INDEX ix_tickets_owner ON tickets USING btree (owner_id);


-- ====================================================================================================
-- THANH TOÁN
-- ====================================================================================================

-- payments: Một lần thử thanh toán của đơn. provider_payment_id = mã giao dịch của cổng (TradeNo...); gateway_merchant_no = merchant ĐÃ THU (để tra, hủy, hoàn tiền về sau).
CREATE TABLE payments (
    id uuid NOT NULL,
    order_id uuid NOT NULL,
    provider character varying(20) NOT NULL,
    provider_payment_id character varying(100),
    payment_link_id character varying(100),
    checkout_url text,
    provider_transaction_ref character varying(100),
    amount bigint NOT NULL,
    status character varying(20) NOT NULL,
    payer_bank_bin character varying(20),
    payer_account_number character varying(255),
    paid_at timestamp with time zone,
    raw_webhook_payload jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    qr_code text,
    gateway_merchant_no character varying(32),
    CONSTRAINT payments_status_check CHECK (status IN ('CREATED', 'PENDING', 'PAID', 'UNDERPAID', 'PAID_LATE', 'FAILED', 'EXPIRED'))
);

ALTER TABLE ONLY payments
    ADD CONSTRAINT payments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY payments
    ADD CONSTRAINT uk_payments_provider_payment UNIQUE (provider, provider_payment_id);

CREATE INDEX ix_payments_gateway_merchant ON payments USING btree (gateway_merchant_no) WHERE (gateway_merchant_no IS NOT NULL);
CREATE INDEX ix_payments_order ON payments USING btree (order_id);

-- webhook_events: Webhook nhận về, unique (provider, event_id) để chống xử lý trùng. Cố ý KHÔNG có FK.
CREATE TABLE webhook_events (
    id uuid NOT NULL,
    provider character varying(20) NOT NULL,
    event_id character varying(200) NOT NULL,
    event_type character varying(50),
    raw_payload jsonb,
    signature_valid boolean NOT NULL,
    processing_result character varying(30),
    received_at timestamp with time zone DEFAULT now() NOT NULL,
    processed_at timestamp with time zone,
    CONSTRAINT webhook_events_processing_result_check CHECK (processing_result IN ('PROCESSED', 'DUPLICATE', 'IGNORED', 'REJECTED_SIGNATURE', 'FAILED'))
);

ALTER TABLE ONLY webhook_events
    ADD CONSTRAINT uk_webhook_events_provider_event UNIQUE (provider, event_id);

ALTER TABLE ONLY webhook_events
    ADD CONSTRAINT webhook_events_pkey PRIMARY KEY (id);


-- gateway_call_logs: Nhật ký gọi cổng thanh toán (vào/ra) để truy vết. Cố ý KHÔNG có FK.
CREATE TABLE gateway_call_logs (
    id uuid NOT NULL,
    ref_type character varying(20),
    ref_id uuid,
    direction character varying(10) NOT NULL,
    endpoint text NOT NULL,
    request_masked jsonb,
    response_raw jsonb,
    http_status integer,
    duration_ms integer,
    trace_id character varying(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT gateway_call_logs_direction_check CHECK (direction IN ('OUTBOUND', 'INBOUND')),
    CONSTRAINT gateway_call_logs_ref_type_check CHECK (ref_type IN ('ORDER', 'PAYMENT', 'REFUND'))
);

ALTER TABLE ONLY gateway_call_logs
    ADD CONSTRAINT gateway_call_logs_pkey PRIMARY KEY (id);

CREATE INDEX ix_gateway_call_logs_ref ON gateway_call_logs USING btree (ref_type, ref_id);
CREATE INDEX ix_gateway_call_logs_trace ON gateway_call_logs USING btree (trace_id);

-- idempotency_records: Kết quả đã lưu theo Idempotency-Key (bấm thanh toán hai lần chỉ ra một đơn). Cố ý KHÔNG có FK.
CREATE TABLE idempotency_records (
    id uuid NOT NULL,
    scope character varying(30) NOT NULL,
    idem_key character varying(100) NOT NULL,
    request_hash character varying(64) NOT NULL,
    response_status integer NOT NULL,
    response_body jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT idempotency_records_scope_check CHECK (scope IN ('CHECKOUT', 'REFUND', 'BUYER_WALLET_TOPUP', 'SELLER_WALLET_TOPUP'))
);

ALTER TABLE ONLY idempotency_records
    ADD CONSTRAINT idempotency_records_pkey PRIMARY KEY (id);

ALTER TABLE ONLY idempotency_records
    ADD CONSTRAINT uk_idempotency_scope_key UNIQUE (scope, idem_key);



-- ====================================================================================================
-- HOÀN TIỀN
-- ====================================================================================================

-- refunds: Yêu cầu hoàn tiền. REQUESTED -> PROCESSING -> SUCCEEDED | FAILED; phụ AWAITING_FUNDS, MANUAL_REVIEW. Bị hủy vẫn mang FAILED + failure_code CANCELLED_BY_ORGANIZER.
CREATE TABLE refunds (
    id uuid NOT NULL,
    order_id uuid NOT NULL,
    payment_id uuid,
    original_payment_transaction_id character varying(100),
    amount bigint NOT NULL,
    currency character varying(3) DEFAULT 'VND'::bpchar NOT NULL,
    status character varying(20) NOT NULL,
    execution_method character varying(20),
    initiator character varying(20) NOT NULL,
    provider character varying(20),
    provider_refund_id character varying(100),
    idempotency_key character varying(100),
    attempt integer DEFAULT 1 NOT NULL,
    failure_code character varying(50),
    failure_reason text,
    destination_bin character varying(20),
    destination_account character varying(255),
    destination_is_payer boolean,
    reason text,
    submitted_at timestamp with time zone,
    last_polled_at timestamp with time zone,
    queued_since timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    contact_email character varying(200),
    CONSTRAINT refunds_execution_method_check CHECK (execution_method IN ('PAYOUT', 'NATIVE_REFUND', 'MANUAL_TRANSFER')),
    CONSTRAINT refunds_initiator_check CHECK (initiator IN ('USER', 'SYSTEM', 'ADMIN')),
    CONSTRAINT refunds_status_check CHECK (status IN ('REQUESTED', 'AWAITING_FUNDS', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'MANUAL_REVIEW'))
);

ALTER TABLE ONLY refunds
    ADD CONSTRAINT refunds_pkey PRIMARY KEY (id);

ALTER TABLE ONLY refunds
    ADD CONSTRAINT uk_refunds_idempotency_key UNIQUE (idempotency_key);

ALTER TABLE ONLY refunds
    ADD CONSTRAINT uk_refunds_provider_refund UNIQUE (provider, provider_refund_id);

CREATE INDEX ix_refunds_order ON refunds USING btree (order_id);
CREATE INDEX ix_refunds_status_created_at ON refunds USING btree (status, created_at);
CREATE INDEX ix_refunds_status_queued ON refunds USING btree (status, queued_since);
CREATE INDEX ix_refunds_status_submit ON refunds USING btree (status, submitted_at);

-- refund_items: Vé nằm trong một yêu cầu hoàn tiền.
CREATE TABLE refund_items (
    id uuid NOT NULL,
    refund_id uuid NOT NULL,
    ticket_id uuid NOT NULL,
    amount bigint NOT NULL
);

ALTER TABLE ONLY refund_items
    ADD CONSTRAINT refund_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY refund_items
    ADD CONSTRAINT uk_refund_items_refund_ticket UNIQUE (refund_id, ticket_id);

CREATE INDEX ix_refund_items_ticket ON refund_items USING btree (ticket_id);

-- refund_inquiries: [CHƯA DÙNG] Có từ V1, không entity hay code nào đụng tới.
CREATE TABLE refund_inquiries (
    id uuid NOT NULL,
    refund_id uuid NOT NULL,
    message text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    resolved_at timestamp with time zone
);

ALTER TABLE ONLY refund_inquiries
    ADD CONSTRAINT refund_inquiries_pkey PRIMARY KEY (id);



-- ====================================================================================================
-- SỔ CÁI & VÍ
-- ====================================================================================================

-- ledger_entries: Sổ kép APPEND-ONLY: trần hoàn tiền tính từ đây, không từ cột của đơn. Cố ý KHÔNG có FK.
CREATE TABLE ledger_entries (
    id uuid NOT NULL,
    account character varying(30) NOT NULL,
    direction character varying(10) NOT NULL,
    amount bigint NOT NULL,
    ref_type character varying(20),
    ref_id uuid,
    occurred_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ledger_entries_account_check CHECK (account IN ('BANK_COLLECTION', 'PAYOUT_WALLET', 'CUSTOMER_LIABILITY', 'FEES')),
    CONSTRAINT ledger_entries_direction_check CHECK (direction IN ('DEBIT', 'CREDIT'))
);

ALTER TABLE ONLY ledger_entries
    ADD CONSTRAINT ledger_entries_pkey PRIMARY KEY (id);

CREATE INDEX ix_ledger_entries_account_time ON ledger_entries USING btree (account, occurred_at);
CREATE INDEX ix_ledger_ref ON ledger_entries USING btree (ref_type, ref_id);

-- seller_wallets: Ví của BTC (tiền bán vé).
CREATE TABLE seller_wallets (
    organizer_id uuid NOT NULL,
    balance bigint DEFAULT 0 NOT NULL,
    total_topups bigint DEFAULT 0 NOT NULL,
    total_sales bigint DEFAULT 0 NOT NULL,
    total_refunds bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY seller_wallets
    ADD CONSTRAINT seller_wallets_pkey PRIMARY KEY (organizer_id);


-- seller_wallet_transactions: Giao dịch ví BTC.
CREATE TABLE seller_wallet_transactions (
    id uuid NOT NULL,
    organizer_id uuid NOT NULL,
    type character varying(30) NOT NULL,
    amount bigint NOT NULL,
    balance_after bigint NOT NULL,
    ref_type character varying(30),
    ref_id uuid,
    note character varying(500),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    idempotency_key character varying(100),
    CONSTRAINT seller_wallet_transactions_amount_check CHECK ((amount > 0)),
    CONSTRAINT seller_wallet_transactions_type_check CHECK (type IN ('TOP_UP', 'PAYMENT_EARNED', 'REFUND_DEBIT'))
);

ALTER TABLE ONLY seller_wallet_transactions
    ADD CONSTRAINT seller_wallet_transactions_pkey PRIMARY KEY (id);

CREATE INDEX ix_seller_wallet_tx_org_time ON seller_wallet_transactions USING btree (organizer_id, created_at DESC);
CREATE UNIQUE INDEX uk_seller_wallet_topup_idem ON seller_wallet_transactions USING btree (organizer_id, type, idempotency_key) WHERE (((type)::text = 'TOP_UP'::text) AND (idempotency_key IS NOT NULL));
CREATE UNIQUE INDEX uk_seller_wallet_tx_ref ON seller_wallet_transactions USING btree (type, ref_id) WHERE (ref_id IS NOT NULL);

-- buyer_wallets: Ví của người mua (thanh toán WALLET, nhận hoàn tiền).
CREATE TABLE buyer_wallets (
    user_id uuid NOT NULL,
    balance bigint DEFAULT 0 NOT NULL,
    total_topups bigint DEFAULT 0 NOT NULL,
    total_spent bigint DEFAULT 0 NOT NULL,
    total_refunds bigint DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version bigint DEFAULT 0 NOT NULL
);

ALTER TABLE ONLY buyer_wallets
    ADD CONSTRAINT buyer_wallets_pkey PRIMARY KEY (user_id);


-- buyer_wallet_transactions: Giao dịch ví người mua.
CREATE TABLE buyer_wallet_transactions (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    type character varying(30) NOT NULL,
    amount bigint NOT NULL,
    balance_after bigint NOT NULL,
    ref_type character varying(30),
    ref_id uuid,
    note character varying(500),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    idempotency_key character varying(100),
    CONSTRAINT buyer_wallet_transactions_amount_check CHECK ((amount > 0)),
    CONSTRAINT buyer_wallet_transactions_type_check CHECK (type IN ('TOP_UP', 'ORDER_PAYMENT', 'REFUND_CREDIT'))
);

ALTER TABLE ONLY buyer_wallet_transactions
    ADD CONSTRAINT buyer_wallet_transactions_pkey PRIMARY KEY (id);

CREATE INDEX ix_buyer_wallet_tx_user_time ON buyer_wallet_transactions USING btree (user_id, created_at DESC);
CREATE UNIQUE INDEX uk_buyer_wallet_topup_idem ON buyer_wallet_transactions USING btree (user_id, type, idempotency_key) WHERE (((type)::text = 'TOP_UP'::text) AND (idempotency_key IS NOT NULL));
CREATE UNIQUE INDEX uk_buyer_wallet_tx_ref ON buyer_wallet_transactions USING btree (type, ref_id) WHERE (ref_id IS NOT NULL);

-- wallet_snapshots: [CHƯA DÙNG] Có từ V1, không code nào đụng tới.
CREATE TABLE wallet_snapshots (
    id uuid NOT NULL,
    taken_at timestamp with time zone DEFAULT now() NOT NULL,
    balance bigint NOT NULL,
    committed bigint NOT NULL,
    available bigint NOT NULL,
    liability bigint NOT NULL,
    coverage numeric(6,2)
);

ALTER TABLE ONLY wallet_snapshots
    ADD CONSTRAINT wallet_snapshots_pkey PRIMARY KEY (id);



-- ====================================================================================================
-- KHÓA NGOẠI (để cuối cho khỏi phụ thuộc thứ tự tạo bảng)
-- ====================================================================================================

ALTER TABLE ONLY buyer_wallet_transactions
    ADD CONSTRAINT buyer_wallet_transactions_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE ONLY buyer_wallets
    ADD CONSTRAINT buyer_wallets_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

ALTER TABLE ONLY events
    ADD CONSTRAINT events_organizer_id_fkey FOREIGN KEY (organizer_id) REFERENCES organizers(id);

ALTER TABLE ONLY inventory
    ADD CONSTRAINT inventory_ticket_tier_id_fkey FOREIGN KEY (ticket_tier_id) REFERENCES ticket_tiers(id);

ALTER TABLE ONLY order_items
    ADD CONSTRAINT order_items_order_id_fkey FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE;

ALTER TABLE ONLY order_items
    ADD CONSTRAINT order_items_ticket_tier_id_fkey FOREIGN KEY (ticket_tier_id) REFERENCES ticket_tiers(id);

ALTER TABLE ONLY orders
    ADD CONSTRAINT orders_event_id_fkey FOREIGN KEY (event_id) REFERENCES events(id);

ALTER TABLE ONLY orders
    ADD CONSTRAINT orders_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE ONLY organizer_bank_accounts
    ADD CONSTRAINT organizer_bank_accounts_organizer_id_fkey FOREIGN KEY (organizer_id) REFERENCES organizers(id) ON DELETE CASCADE;

ALTER TABLE ONLY organizer_gateway_bindings
    ADD CONSTRAINT organizer_gateway_bindings_organizer_id_fkey FOREIGN KEY (organizer_id) REFERENCES organizers(id) ON DELETE CASCADE;

ALTER TABLE ONLY organizer_payment_settings
    ADD CONSTRAINT organizer_payment_settings_organizer_id_fkey FOREIGN KEY (organizer_id) REFERENCES organizers(id) ON DELETE CASCADE;

ALTER TABLE ONLY organizers
    ADD CONSTRAINT organizers_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE ONLY password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE ONLY payments
    ADD CONSTRAINT payments_order_id_fkey FOREIGN KEY (order_id) REFERENCES orders(id);

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT refresh_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE ONLY refund_inquiries
    ADD CONSTRAINT refund_inquiries_refund_id_fkey FOREIGN KEY (refund_id) REFERENCES refunds(id);

ALTER TABLE ONLY refund_items
    ADD CONSTRAINT refund_items_refund_id_fkey FOREIGN KEY (refund_id) REFERENCES refunds(id) ON DELETE CASCADE;

ALTER TABLE ONLY refund_items
    ADD CONSTRAINT refund_items_ticket_id_fkey FOREIGN KEY (ticket_id) REFERENCES tickets(id);

ALTER TABLE ONLY refunds
    ADD CONSTRAINT refunds_order_id_fkey FOREIGN KEY (order_id) REFERENCES orders(id);

ALTER TABLE ONLY refunds
    ADD CONSTRAINT refunds_payment_id_fkey FOREIGN KEY (payment_id) REFERENCES payments(id);

ALTER TABLE ONLY seller_wallet_transactions
    ADD CONSTRAINT seller_wallet_transactions_organizer_id_fkey FOREIGN KEY (organizer_id) REFERENCES organizers(id) ON DELETE CASCADE;

ALTER TABLE ONLY seller_wallets
    ADD CONSTRAINT seller_wallets_organizer_id_fkey FOREIGN KEY (organizer_id) REFERENCES organizers(id) ON DELETE CASCADE;

ALTER TABLE ONLY ticket_tiers
    ADD CONSTRAINT ticket_tiers_event_id_fkey FOREIGN KEY (event_id) REFERENCES events(id);

ALTER TABLE ONLY tickets
    ADD CONSTRAINT tickets_order_id_fkey FOREIGN KEY (order_id) REFERENCES orders(id);

ALTER TABLE ONLY tickets
    ADD CONSTRAINT tickets_owner_id_fkey FOREIGN KEY (owner_id) REFERENCES users(id);

ALTER TABLE ONLY tickets
    ADD CONSTRAINT tickets_ticket_tier_id_fkey FOREIGN KEY (ticket_tier_id) REFERENCES ticket_tiers(id);
