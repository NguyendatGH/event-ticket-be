-- Bỏ tính năng bán lại vé. Xóa dữ liệu bán lại: listing, đơn RESALE (kèm payment/refund của nó), lịch sử vé ngoài ISSUED.
-- Vé đã bán lại giữ nguyên chủ hiện tại (người mua). Sổ ghi chép không khóa ngoại (gateway_call_logs, ledger_entries) giữ nguyên.

drop table if exists resale_listings;   -- trước khi xóa đơn: resale_listings.order_id trỏ vào orders

-- Đơn RESALE không có vé riêng (vé vẫn trỏ order_id của đơn gốc), chỉ có payment/refund treo vào
delete from refund_inquiries where refund_id in (select r.id from refunds r join orders o on o.id = r.order_id where o.kind = 'RESALE');
delete from refunds where order_id in (select id from orders where kind = 'RESALE');   -- refund_items xóa theo cascade
delete from payments where order_id in (select id from orders where kind = 'RESALE');
delete from idempotency_records where response_body ->> 'kind' = 'RESALE';          -- response đã lưu của đơn vừa xóa
delete from orders where kind = 'RESALE';                                             -- order_items xóa theo cascade

alter table orders drop column if exists resale_listing_id;
alter table orders drop column if exists kind;   -- ck_orders_kind đi theo cột

delete from ticket_history where type <> 'ISSUED';
alter table ticket_history drop column if exists listing_id;
alter table ticket_history drop constraint if exists ticket_history_type_check;
alter table ticket_history add constraint ck_ticket_history_type check (type in ('ISSUED'));
