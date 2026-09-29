-- Sổ bút toán kép bắt đầu được ghi từ LedgerService. Đơn đã PAID trước đó chưa có dòng nào, mà
-- RefundResultHandler lấy trần hoàn tiền từ sổ -> không backfill là mọi đơn cũ hoàn tiền đều bị chặn oan.
-- Dựng lại bút toán từ chính các cột tiền của đơn, đúng bộ ba mà LedgerService.recordOrderPaid ghi.

insert into ledger_entries (id, account, direction, amount, ref_type, ref_id, occurred_at)
select gen_random_uuid(), 'BANK_COLLECTION', 'DEBIT', o.paid_amount, 'ORDER', o.id, coalesce(o.paid_at, o.created_at)
from orders o
where o.paid_amount > 0
union all
select gen_random_uuid(), 'CUSTOMER_LIABILITY', 'CREDIT', o.paid_amount - least(o.fee_amount, o.paid_amount), 'ORDER', o.id, coalesce(o.paid_at, o.created_at)
from orders o
where o.paid_amount - least(o.fee_amount, o.paid_amount) > 0
union all
select gen_random_uuid(), 'FEES', 'CREDIT', least(o.fee_amount, o.paid_amount), 'ORDER', o.id, coalesce(o.paid_at, o.created_at)
from orders o
where o.paid_amount > 0 and least(o.fee_amount, o.paid_amount) > 0;

-- Refund đã SUCCEEDED trước đó: tiền đã rời ví, phải có mặt trong sổ nếu không trần hoàn tiền sẽ bị nới ra.
insert into ledger_entries (id, account, direction, amount, ref_type, ref_id, occurred_at)
select gen_random_uuid(), 'CUSTOMER_LIABILITY', 'DEBIT', r.amount, 'REFUND', r.id, coalesce(r.updated_at, r.created_at)
from refunds r
where r.status = 'SUCCEEDED' and r.amount > 0
union all
select gen_random_uuid(), 'PAYOUT_WALLET', 'CREDIT', r.amount, 'REFUND', r.id, coalesce(r.updated_at, r.created_at)
from refunds r
where r.status = 'SUCCEEDED' and r.amount > 0;

create index if not exists ix_ledger_ref on ledger_entries (ref_type, ref_id);
