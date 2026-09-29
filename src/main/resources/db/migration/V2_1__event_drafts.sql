-- Lưu nháp sự kiện chỉ bắt buộc name (ui-api-contract §4.4): category và starts_at được trống khi DRAFT.
-- Publish mới kiểm tra đủ (EVENT_INCOMPLETE), nên sự kiện đang liệt kê luôn có hai cột này.
alter table events alter column category drop not null;
alter table events alter column starts_at drop not null;
