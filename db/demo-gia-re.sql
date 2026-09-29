-- =====================================================================================================
-- Hạ giá vé xuống 1.000–2.000đ để demo thanh toán THẬT (profile payos) mà không tốn tiền.
-- Profile mặc định (dev) dùng cổng mock, không trừ tiền thật nên không cần script này.
--
-- Cách dùng:
--   1. Thêm vào backend/be-view/.env:   CHECKOUT_FEE=0   (phí dịch vụ 0đ)
--   2. cd backend/be-view && set -a; . ./.env; set +a
--      PGPASSWORD="$DB_PASSWORD" psql -h localhost -U "$DB_USERNAME" -d event-application-db -1 -v ON_ERROR_STOP=1 -f db/demo-gia-re.sql
--   3. Khởi động lại BE để đọc .env mới.
-- Muốn về giá thật: tạo lại DB rồi seed lại (xem README), vì script này ghi đè giá.
-- Đơn/doanh thu cũ trên dashboard giữ nguyên số tiền lúc mua (lịch sử không bị sửa).
-- =====================================================================================================

-- Hạng rẻ nhất của mỗi sự kiện 1.000đ, các hạng còn lại 2.000đ; hạng miễn phí (0đ) giữ nguyên.
update ticket_tiers t
set price = case when t.price = 0 then 0 when r.rn = 1 then 1000 else 2000 end
from (select id, row_number() over (partition by event_id order by price, id) as rn from ticket_tiers) r
where r.id = t.id;

-- Kiểm tra nhanh sau khi chạy
select e.name as su_kien, t.name as hang_ve, t.price as gia
from ticket_tiers t join events e on e.id = t.event_id
order by e.starts_at, t.price
limit 12;
