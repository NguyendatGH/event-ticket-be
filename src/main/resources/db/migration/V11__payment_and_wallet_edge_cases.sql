alter table idempotency_records alter column scope type varchar(30);
alter table idempotency_records drop constraint if exists idempotency_records_scope_check;
alter table idempotency_records add constraint idempotency_records_scope_check
    check (scope in ('CHECKOUT', 'REFUND', 'BUYER_WALLET_TOPUP', 'SELLER_WALLET_TOPUP'));

alter table buyer_wallet_transactions add column if not exists idempotency_key varchar(100);
alter table seller_wallet_transactions add column if not exists idempotency_key varchar(100);

create unique index if not exists uk_buyer_wallet_topup_idem
    on buyer_wallet_transactions(user_id, type, idempotency_key)
    where type = 'TOP_UP' and idempotency_key is not null;
create unique index if not exists uk_seller_wallet_topup_idem
    on seller_wallet_transactions(organizer_id, type, idempotency_key)
    where type = 'TOP_UP' and idempotency_key is not null;
