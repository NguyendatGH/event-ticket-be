-- Tính năng app (spec-plan/ui-api-contract.md §2): organizer, hồ sơ user, refresh/reset token, resale, lịch sử vé, liên hệ.
-- Viết idempotent (if not exists / drop ... if exists) vì DB dev có thể đã baseline ở V1 với bảng có sẵn.

-- ---------- users: hồ sơ + role ORGANIZER ----------
alter table users add column if not exists phone      varchar(30);
alter table users add column if not exists avatar_url text;
alter table users add column if not exists bio        text;
alter table users add column if not exists updated_at timestamptz not null default now();
alter table users drop constraint if exists users_role_check;
alter table users add constraint users_role_check check (role in ('CUSTOMER', 'ORGANIZER', 'ADMIN'));

-- ---------- organizers: một user tối đa một hồ sơ; user_id null = BTC chưa có tài khoản (backfill từ dữ liệu cũ) ----------
create table if not exists organizers (
    id            uuid primary key,
    user_id       uuid         references users (id),
    slug          varchar(200) not null,
    name          varchar(200) not null,
    description   text,
    logo_url      text,
    cover_url     text,
    website       text,
    city          varchar(100),
    contact_email varchar(200),
    contact_phone varchar(30),
    verified      boolean      not null default false,
    created_at    timestamptz  not null default now(),
    updated_at    timestamptz  not null default now(),
    constraint uk_organizers_slug unique (slug),
    constraint uk_organizers_user unique (user_id)
);

-- ---------- events: organizer_id thay cột jsonb organizer ----------
alter table events add column if not exists organizer_id uuid references organizers (id);
alter table events add column if not exists published_at timestamptz;
create index if not exists ix_events_organizer on events (organizer_id);
update events set published_at = created_at where published_at is null and status in ('PUBLISHED', 'UPCOMING');

-- Backfill: mỗi tên BTC khác nhau trong events.organizer (jsonb) → một dòng organizers. Chỉ chạy khi cột cũ còn.
-- Slug giống db/gen-seed-sql.py: bỏ dấu tiếng Việt (đ → d), chữ thường, ký tự khác [a-z0-9] thành '-';
-- trùng slug (hai tên khác nhau ra cùng slug) thì thêm hậu tố md5 ngắn.
do $$
begin
    if exists (select 1 from information_schema.columns
               where table_schema = current_schema() and table_name = 'events' and column_name = 'organizer') then
        with src as (
            select distinct on (e.organizer ->> 'name')
                   e.organizer ->> 'name'                                   as name,
                   e.organizer ->> 'description'                            as description,
                   coalesce((e.organizer ->> 'verified')::boolean, false)   as verified
            from events e
            where coalesce(e.organizer ->> 'name', '') <> ''
            order by e.organizer ->> 'name', e.created_at
        ), slugged as (
            select s.*,
                   trim(both '-' from regexp_replace(
                       translate(lower(s.name),
                                 'àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđĐ',
                                 'aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyydd'),
                       '[^a-z0-9]+', '-', 'g')) as base
            from src s
        ), ranked as (
            select sl.*, row_number() over (partition by sl.base order by sl.name) as rn from slugged sl
        )
        insert into organizers (id, slug, name, description, verified)
        select gen_random_uuid(),
               case when r.rn = 1 and r.base <> '' then r.base
                    else coalesce(nullif(r.base, ''), 'organizer') || '-' || left(md5(r.name), 6) end,
               r.name, r.description, r.verified
        from ranked r
        where not exists (select 1 from organizers o where o.name = r.name)
        on conflict (slug) do nothing;

        update events e set organizer_id = o.id
        from organizers o
        where e.organizer_id is null and o.name = e.organizer ->> 'name';
    end if;
end $$;
alter table events drop column if exists organizer;

-- ---------- orders: chủ đơn, loại đơn (vé thường / vé bán lại), thời điểm trả tiền ----------
alter table orders add column if not exists user_id uuid references users (id);
alter table orders add column if not exists kind varchar(10) not null default 'PRIMARY'
    constraint ck_orders_kind check (kind in ('PRIMARY', 'RESALE'));
alter table orders add column if not exists resale_listing_id uuid;   -- không FK: resale_listings.order_id đã trỏ ngược lại
alter table orders add column if not exists paid_at timestamptz;
create index if not exists ix_orders_user on orders (user_id);
create index if not exists ix_orders_paid_at on orders (paid_at);
update orders o set paid_at = p.paid_at
from (select order_id, max(paid_at) as paid_at from payments where paid_at is not null group by order_id) p
where o.id = p.order_id and o.paid_at is null;

-- ---------- tickets: chủ vé (chỉ vé có owner mới vào "Vé của tôi") ----------
alter table tickets add column if not exists owner_id uuid references users (id);
create index if not exists ix_tickets_owner on tickets (owner_id);

-- ---------- token: chỉ lưu sha256 hex của token ----------
create table if not exists refresh_tokens (
    id         uuid primary key,
    user_id    uuid        not null references users (id),
    token_hash varchar(64) not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    created_at timestamptz not null default now(),
    constraint uk_refresh_tokens_hash unique (token_hash)
);
create index if not exists ix_refresh_tokens_user on refresh_tokens (user_id);

create table if not exists password_reset_tokens (
    id         uuid primary key,
    user_id    uuid        not null references users (id),
    token_hash varchar(64) not null,
    expires_at timestamptz not null,
    used_at    timestamptz,
    created_at timestamptz not null default now(),
    constraint uk_password_reset_tokens_hash unique (token_hash)
);

-- ---------- resale ----------
create table if not exists resale_listings (
    id             uuid primary key,
    ticket_id      uuid        not null references tickets (id),
    seller_id      uuid        not null references users (id),
    event_id       uuid        not null references events (id),
    ticket_tier_id uuid        not null references ticket_tiers (id),
    price          bigint      not null check (price > 0),
    original_price bigint      not null,
    status         varchar(20) not null check (status in ('ACTIVE', 'RESERVED', 'SOLD', 'CANCELLED')),
    buyer_id       uuid        references users (id),
    order_id       uuid        references orders (id),
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    sold_at        timestamptz,
    cancelled_at   timestamptz
);
-- Một vé chỉ có một listing đang mở (ACTIVE/RESERVED)
create unique index if not exists uk_resale_listings_open_ticket on resale_listings (ticket_id) where status in ('ACTIVE', 'RESERVED');
create index if not exists ix_resale_listings_status_created on resale_listings (status, created_at);
create index if not exists ix_resale_listings_event_status on resale_listings (event_id, status);
create index if not exists ix_resale_listings_seller on resale_listings (seller_id);

create table if not exists ticket_history (
    id           uuid primary key,
    ticket_id    uuid        not null references tickets (id),
    type         varchar(20) not null check (type in ('ISSUED', 'LISTED', 'PRICE_CHANGED', 'DELISTED', 'RESOLD')),
    price        bigint,
    from_user_id uuid,
    to_user_id   uuid,
    listing_id   uuid,
    order_id     uuid,
    created_at   timestamptz not null default now()
);
create index if not exists ix_ticket_history_ticket on ticket_history (ticket_id);

-- ---------- liên hệ ----------
create table if not exists contact_messages (
    id         uuid primary key,
    name       varchar(200) not null,
    email      varchar(200) not null,
    subject    varchar(200),
    message    text         not null,
    user_id    uuid,
    created_at timestamptz  not null default now()
);
