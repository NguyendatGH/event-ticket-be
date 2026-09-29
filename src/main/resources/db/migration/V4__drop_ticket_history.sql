-- Bỏ lịch sử vé: sau khi bỏ bán lại chỉ còn dòng ISSUED, trùng hoàn toàn với tickets (giá, chủ vé, đơn, issued_at).
drop table if exists ticket_history;
