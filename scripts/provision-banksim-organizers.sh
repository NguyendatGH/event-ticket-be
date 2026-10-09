#!/usr/bin/env bash
# Cấp phát merchant + terminal BankSim cho các BTC đã chọn BANKSIM nhưng chưa có binding.
# Spec §33 option B: không reset DB, không fallback dùng chung.
#
# Dùng: BE_URL=http://localhost:8080 ADMIN_EMAIL=... ADMIN_PASSWORD=... bash scripts/provision-banksim-organizers.sh
set -euo pipefail

BE_URL=${BE_URL:-http://localhost:8080}
: "${DB_USERNAME:?cần DB_USERNAME}"; : "${DB_PASSWORD:?cần DB_PASSWORD}"
DB_NAME=${DB_NAME:-event-application-db}
DB_HOST=${DB_HOST:-localhost}

echo "== BTC chọn BANKSIM nhưng chưa có binding ACTIVE =="
PENDING=$(PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -U "$DB_USERNAME" -d "$DB_NAME" -At -c "
  SELECT s.organizer_id FROM organizer_payment_settings s
  LEFT JOIN organizer_gateway_bindings b
         ON b.organizer_id = s.organizer_id AND b.provider = 'BANKSIM' AND b.status = 'ACTIVE'
  WHERE s.gateway = 'BANKSIM' AND b.organizer_id IS NULL")

if [ -z "$PENDING" ]; then echo "  không có BTC nào cần cấp phát"; exit 0; fi
echo "$PENDING" | sed 's/^/  /'

echo "== gọi provision =="
for ORG in $PENDING; do
  # Provision chạy trong luồng PUT payment-settings; gọi lại chính setting hiện tại là đủ kích hoạt.
  METHODS=$(PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -U "$DB_USERNAME" -d "$DB_NAME" -At -c \
    "SELECT payment_methods FROM organizer_payment_settings WHERE organizer_id='$ORG'")
  EMAIL=$(PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -U "$DB_USERNAME" -d "$DB_NAME" -At -c \
    "SELECT u.email FROM organizers o JOIN users u ON u.id=o.user_id WHERE o.id='$ORG'")
  echo "  organizer=$ORG ($EMAIL) methods=$METHODS"
  echo "     -> đăng nhập bằng tài khoản này rồi PUT /api/v1/organizer/payment-settings {gateway:BANKSIM, paymentMethods:$METHODS}"
done

cat <<'NOTE'

Cách chạy thực tế (một trong hai):
  A. Mỗi BTC tự vào màn "Cổng thanh toán" bấm Lưu — provision tự chạy.
  B. Admin dùng Gateway Admin Portal tạo merchant + terminal, rồi gán binding bằng SQL.

Script này CỐ Ý không tự đăng nhập hộ BTC: không giữ mật khẩu của họ, và
provisioning phải đi qua đúng luồng nghiệp vụ đã có kiểm quyền.
NOTE
