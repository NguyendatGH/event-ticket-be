-- Email liên hệ của khách cho MỘT yêu cầu hoàn vé cụ thể. Hỏi ngay lúc khách bấm "Hoàn vé" vì khách có
-- thể muốn nhận thông báo ở email khác email lúc mua (orders.customer_email) — ví dụ mua bằng mail công ty
-- nhưng muốn nhận thông báo vào mail cá nhân. Dùng để gửi mail khi BTC hủy yêu cầu hoàn vé của khách.

-- TẠI SAO NULLABLE (không phải "not null"):
-- những row refunds đã có trong DB trước migration này không có email nào để điền, và KHÔNG backfill được.
-- Lấy orders.customer_email gán vào đây là tự bịa: cột này mang nghĩa "email KHÁCH ĐÃ NHẬP cho yêu cầu này",
-- mà khách chưa từng nhập gì cả. Thà để null rồi log.warn khi cần gửi mail, còn hơn có dữ liệu sai.
-- Bắt buộc phải có thì ép ở TẦNG API: CreateRefundRequest.contactEmail mang @NotBlank @Email @Size(max = 200),
-- nên mọi yêu cầu MỚI luôn có email; code đọc cột này vẫn phải chịu được null (xem RefundNotifier).

-- TẠI SAO LƯU PLAINTEXT (không mã hóa AES như destination_account, xem comment cột đó ở V1__init.sql):
-- 1) email không phải số tài khoản — lộ email thì bị spam, lộ số tài khoản thì liên quan trực tiếp tới tiền;
-- 2) orders.customer_email trong cùng DB cũng đang plaintext. Mã hóa cột này mà bỏ cột kia chỉ tạo ảo giác
--    an toàn (kẻ đọc được DB vẫn có email của khách ở bảng orders), lại phải thêm AttributeConverter và mất
--    khả năng query/so khớp bằng SQL. Giữ nhất quán với cột email đã có; khi nào mã hóa thì mã hóa cả họ.

alter table refunds add column contact_email varchar(200);
