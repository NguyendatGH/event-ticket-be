-- Refund (spec-plan/refund-code-plan.md mục 5.3, đổi V3 -> V5 vì V3/V4 đã dùng).
-- Bảng refunds/refund_items đã có từ V1, đây chỉ là index cho job + sửa kiểu cột.

-- V1 khai char(3); Hibernate validate so với String sẽ kêu lệch kiểu.
alter table refunds alter column currency type varchar(3);

create index if not exists ix_refunds_order         on refunds (order_id);
create index if not exists ix_refunds_status_queued on refunds (status, queued_since);
create index if not exists ix_refunds_status_submit on refunds (status, submitted_at);
create index if not exists ix_refund_items_ticket   on refund_items (ticket_id);
