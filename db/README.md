# Dựng database

Ba file trong thư mục này **không phải migration**, không chạy nối tiếp theo tên. Mỗi file một việc:

| File | Vai trò | Khi nào chạy |
|---|---|---|
| `seed-dev.sql` | Dữ liệu mẫu (5 user, 15 sự kiện, 62 đơn, 117 vé). **File sinh tự động — đừng sửa tay.** | Mỗi lần muốn có dữ liệu để chạy thử |
| `gen-seed-sql.py` | Sinh `seed-dev.sql` từ `src/main/resources/seed/events.json` | Chỉ khi sửa `events.json` |
| `demo-gia-re.sql` | Hạ giá vé xuống 1.000–2.000đ | Tùy chọn, chỉ khi demo thanh toán PayOS **thật** |

Schema **không** nằm ở đây mà ở `src/main/resources/db/migration/` (Flyway, **một file duy nhất** `V1__init.sql`, chia mục theo nghiệp vụ).

## Clone về lần đầu

```bash
cd be
cp .env.example .env              # điền DB_USERNAME, DB_PASSWORD
docker compose up -d              # Postgres 16 ở cổng 5432; bỏ qua nếu máy đã có Postgres

set -a; . ./.env; set +a          # nạp DB_* vào môi trường cho 2 lệnh dưới

# 1. Tạo schema — KHÔNG cần boot app, KHÔNG cần key PayOS
./mvnw flyway:migrate

# 2. Đổ dữ liệu mẫu (chạy lại bao nhiêu lần cũng được)
PGPASSWORD="$DB_PASSWORD" psql -h localhost -U "$DB_USERNAME" -d event-application-db \
  -1 -v ON_ERROR_STOP=1 -f db/seed-dev.sql
```

Xong hai bước là DB dùng được. Kiểm tra nhanh:

```bash
PGPASSWORD="$DB_PASSWORD" psql -h localhost -U "$DB_USERNAME" -d event-application-db -c \
  "select (select count(*) from users) as users, (select count(*) from events) as events,
          (select count(*) from orders) as orders, (select count(*) from tickets) as tickets;"
```

Phải ra `users=5 events=15 orders=62 tickets=117`.

Tài khoản seed, mật khẩu chung `password123`:

| Email | Vai trò |
|---|---|
| `a@example.com`, `b@example.com` | CUSTOMER |
| `admin@example.com` | ADMIN |
| `organizer@example.com`, `organizer2@example.com` | ORGANIZER |

## Vì sao có bước `flyway:migrate` riêng

App cũng tự chạy Flyway lúc khởi động, nhưng `PAYOS_CLIENT_ID` / `PAYOS_API_KEY` / `PAYOS_CHECKSUM_KEY`
**không có giá trị mặc định** — thiếu là app không khởi động nổi, nên cũng không tạo được schema.
`./mvnw flyway:migrate` tách việc dựng DB ra khỏi việc chạy app, để người mới clone về là làm được ngay
dù chưa xin được key PayOS.

## Sinh lại dữ liệu mẫu

Sửa `src/main/resources/seed/events.json` rồi:

```bash
python3 db/gen-seed-sql.py        # ghi đè db/seed-dev.sql
```

Generator chạy tất định: cùng `events.json` luôn cho ra file giống hệt (id là UUID v5 cố định,
random có seed cố định). Sửa tay `seed-dev.sql` sẽ mất khi ai đó chạy lại lệnh trên.

Seed dùng `ON CONFLICT DO NOTHING` nên chạy lại không nhân đôi dữ liệu. `POST /dev/seed`
(chỉ profile dev) xóa event/đơn rồi chạy đúng file này từ classpath, giữ nguyên users và organizers.

## Demo thanh toán thật giá rẻ

Chỉ dùng với profile `payos` (cổng thật, trừ tiền thật). Profile mặc định `dev` dùng cổng mock nên không cần.

```bash
# .env: CHECKOUT_FEE=0
set -a; . ./.env; set +a
PGPASSWORD="$DB_PASSWORD" psql -h localhost -U "$DB_USERNAME" -d event-application-db \
  -1 -v ON_ERROR_STOP=1 -f db/demo-gia-re.sql
```

Script ghi đè giá hiện tại. Muốn về giá gốc thì tạo lại DB rồi seed lại. Đơn cũ trên dashboard
giữ nguyên số tiền lúc mua, lịch sử không bị sửa.

## Làm lại từ đầu

```bash
set -a; . ./.env; set +a
PGPASSWORD="$DB_PASSWORD" psql -h localhost -U "$DB_USERNAME" -d postgres \
  -c "drop database if exists \"event-application-db\";" -c "create database \"event-application-db\";"
./mvnw flyway:migrate
PGPASSWORD="$DB_PASSWORD" psql -h localhost -U "$DB_USERNAME" -d event-application-db \
  -1 -v ON_ERROR_STOP=1 -f db/seed-dev.sql
```

DB có bảng cũ nhưng thiếu `flyway_schema_history` (từ thời còn `ddl-auto`) thì Flyway từ chối chạy —
làm lại từ đầu như trên, hoặc trỏ `DB_URL` sang DB khác.

Sơ đồ quan hệ các bảng: [`../docs/ERD.md`](../docs/ERD.md).
