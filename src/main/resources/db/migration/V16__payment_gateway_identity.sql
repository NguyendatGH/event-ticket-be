-- Giữ lại danh tính gateway ĐÃ DÙNG lúc tạo payment. Binding có thể đổi terminal hoặc rotate
-- credential về sau; reconcile và refund vẫn phải tra đúng merchant/terminal gốc.
alter table payments add column gateway_merchant_no varchar(32);
alter table payments add column gateway_terminal_id varchar(32);

create index ix_payments_gateway_merchant on payments(gateway_merchant_no)
    where gateway_merchant_no is not null;
