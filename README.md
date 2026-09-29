# Event Ticket API (be)

Backend bán vé sự kiện: Spring Boot 4.1.1, Java 21, PostgreSQL, Flyway, JWT.

Sơ đồ ERD + state machine + luồng xử lý: [`docs/ERD.md`](docs/ERD.md). Dựng database: [`db/README.md`](db/README.md).

## Chạy

```bash
cp .env.example .env            # điền DB_*, JWT_SECRET (openssl rand -hex 32), PAYOS_*
docker compose up -d            # Postgres 16 ở 5432, bỏ qua nếu máy đã có Postgres

set -a; . ./.env; set +a
./mvnw flyway:migrate           # tạo schema, không cần boot app nên không cần key PayOS
PGPASSWORD="$DB_PASSWORD" psql -h localhost -U "$DB_USERNAME" -d event-application-db \
  -1 -v ON_ERROR_STOP=1 -f db/seed-dev.sql    # dữ liệu mẫu, idempotent

./mvnw spring-boot:run          # profile mặc định: dev. CẦN đủ PAYOS_* trong .env mới khởi động được
```

- Swagger: http://localhost:8080/swagger-ui.html (không có dấu `/` cuối)
- Profile: `spring.profiles.default=dev` nên không truyền gì là `dev` (bật `/dev/**`).
- Seed và các bước dựng DB: xem [`db/README.md`](db/README.md). `DevDataSeeder` mặc định tắt (`app.seed.on-startup=false`); bật `true` để seed tự động lúc boot khi bảng trống.
- Chạy prod: `./mvnw spring-boot:run -Dspring-boot.run.profiles=prod`.
- Schema do Flyway tạo từ `src/main/resources/db/migration/`, Hibernate chỉ `validate`.

Tài khoản seed, mật khẩu chung `password123`: `a@example.com`, `b@example.com` (CUSTOMER), `admin@example.com` (ADMIN), `organizer@example.com` (ORGANIZER).

## API

Base `/api/v1`, JSON, tiền là số nguyên VND, thời gian ISO-8601. Hợp đồng chi tiết cho FE: `../context/docs/spec-plan/ui-api-contract.md`.

| Nhóm | Endpoint | Ghi chú |
|---|---|---|
| Auth | `POST /auth/register`, `POST /auth/login`, `POST /auth/google`, `GET /auth/me` | trả `accessToken` HS256, hạn 15 phút, gửi lại qua `Authorization: Bearer`; `/auth/google` xem mục dưới |
| Users | `GET /users` | chỉ ADMIN |
| Events | `GET /events`, `/events/featured`, `/events/{idOrSlug}`, `/events/{idOrSlug}/related` | public |
| Orders | `POST /orders` (header `Idempotency-Key`), `GET /orders/{id}`, `POST /orders/{id}/cancel` | **cần đăng nhập, chỉ chủ đơn** (sai chủ → 404); tạo đơn `PENDING_PAYMENT` + link thanh toán (`payment.checkoutUrl`), giữ kho, hết hạn sau `app.checkout.order-ttl`; provider không tạo được link → 502 `PAYMENT_LINK_FAILED`, đơn hủy, kho trả |
| Webhooks | `POST /webhooks/payos/payment` | PayOS gọi; verify chữ ký (sai → 401, vẫn ghi `webhook_events`), chống trùng bằng unique `(provider, event_id)`, order `PAID` đúng một lần và cấp vé; luôn 200 kể cả `orderCode` lạ |
| Refunds | `POST /orders/{id}/refunds` (header `Idempotency-Key`), `GET /orders/{id}/refunds`, `GET /refunds/{id}` | **cần đăng nhập, chỉ chủ đơn** (sai chủ → 404); hoàn theo vé, trả **202** vì tiền đi bất đồng bộ |
| Admin | `GET /admin/orders/{id}/audit`, `GET /admin/refunds?status=`, `POST /admin/refunds/{id}/resolve`, `GET /admin/refunds/wallet` | chỉ ADMIN: audit đơn, hàng chờ duyệt, chốt refund `MANUAL_REVIEW`, ba con số của ví chi |
| Dev | `POST /dev/seed` | chỉ profile dev; xóa event/order và seed lại từ JSON (tương đương `db/seed-dev.sql` sau khi truncate) |

Trạng thái đơn sau thanh toán: `PAID` (đủ tiền khi còn hạn), `MANUAL_REVIEW` (tiền vào sau khi `EXPIRED`/`CANCELLED`, kho đã trả nên không cấp vé), còn `UNDERPAID`/`FAILED` nằm ở `payment.status` và đơn vẫn `PENDING_PAYMENT` tới khi hết hạn. `OrderExpiryJob` (60s) hỏi provider một lần trước khi hết hạn, `PaymentReconcileJob` (5 phút) đối chiếu đơn sắp hết hạn phòng webhook rớt.

Thanh toán đi qua port `PaymentGatewayPort` trong domain, adapter duy nhất là PayOS thật (`infrastructure/gateway/payos`, mục dưới).
Test dùng test double `MockPaymentGateway` ở `src/test/java/com/example/demo/support` (`@Profile("test")`).
Refund **đã làm** (2026-09-29): hoàn theo vé bằng lệnh chi qua kênh payout PayOS, mục "Hoàn tiền" dưới.

## Đăng nhập Google

Luồng chọn ở đây là **FE lấy `id_token`, BE verify** (không dùng redirect `/login/oauth2/code/google`), vì API này stateless
và FE là SPA: không cần session, không cần client secret, token không phải đi qua URL.

```
FE (:3000)                         BE (:8080)                    Google
  | bấm nút Google  ------------------------------------------->  |
  | <----------------------------------------  id_token (JWT)     |
  | POST /api/v1/auth/google {idToken} -->  |
  |                          verify chữ ký bằng public key  -->    |  (JWKS, cache sẵn)
  |                          kiểm tra aud = client id, iss, exp
  |                          tìm user theo email, chưa có thì tạo
  | <-- {accessToken, refreshToken, user} --|
```

Cấu hình: `GOOGLE_CLIENT_ID` trong `.env` (OAuth Client ID loại *Web application*, thêm
`http://localhost:3000` vào **Authorized JavaScript origins**). Bỏ trống = tắt tính năng, `/auth/google`
trả 400 `GOOGLE_LOGIN_DISABLED` và FE tự ẩn nút. FE đọc client id qua `GET /api/v1/config` nên không cần env riêng.

Điểm cần nhớ:

- `GoogleIdTokenVerifier` **bắt buộc** verify chữ ký + `aud` + `iss` + hạn; decode suông là ai cũng giả được email người khác.
- Khớp tài khoản theo **email**: đã đăng ký bằng mật khẩu rồi thì login Google vào đúng tài khoản cũ, không tạo trùng.
- User tạo từ Google có `password_hash` là hash của chuỗi ngẫu nhiên (cột NOT NULL, và login mật khẩu phải trượt);
  muốn có mật khẩu thì dùng "Quên mật khẩu".
- Lỗi trả về: 401 `GOOGLE_TOKEN_INVALID`, 401 `GOOGLE_EMAIL_NOT_VERIFIED`, 400 `GOOGLE_LOGIN_DISABLED`.

## Thử luồng thanh toán không cần PayOS

Cổng giả lập **không còn trong app**. Nó là test double ở `src/test/java/com/example/demo/support`
(`MockPaymentGateway` + `MockGatewayController`, cả hai `@Profile("test")`), nên app chạy là luôn đi PayOS thật;
gọi `/mock-gateway/payments/...` vào app đang chạy sẽ trả 404.

Kịch bản `context-handoff.md` §9 bước 1–4 giờ là test tự động, chạy offline, không cần PayOS lẫn tunnel:

```bash
./mvnw test -Dtest=CheckoutFlowTests
```

17 test đó phủ đúng những nhánh mà đoạn curl cũ bấm tay: tạo đơn + giữ kho, idempotency, webhook trùng
(`duplicate=true`), ký sai (401 nhưng vẫn ghi `webhook_events`), trả thiếu (`payment.status = UNDERPAID`),
tiền vào sau khi đơn đã đóng (`MANUAL_REVIEW`), hết hạn (`expire`), và provider từ chối tạo link
(tổng tiền có 3 số cuối là `013`).

Refund có bộ test riêng: `./mvnw test -Dtest=RefundFlowTests` (15 test).

## Cổng thanh toán PayOS

Adapter `infrastructure/gateway/payos/PayOsPaymentGateway` (`@Profile("!test")`, tức luôn bật khi chạy app) dùng SDK chính thức `vn.payos:payos-java` 2.0.1: tạo link (`paymentRequests().create`), đọc trạng thái (`get`), hủy link (`cancel`), verify chữ ký webhook (`webhooks().verify`) và đăng ký webhook URL (`webhooks().confirm`).

Không cần profile riêng nữa — PayOS là cổng duy nhất. Thiếu key trong `.env` là app **không khởi động được**
(`app.payos.*` trong `application.yaml` khai `${PAYOS_CLIENT_ID}` không có default): đó là chủ ý, vì không còn cổng nào chạy thay.

```bash
# .env cần: PAYOS_CLIENT_ID, PAYOS_API_KEY, PAYOS_CHECKSUM_KEY (lấy ở https://my.payos.vn)
./mvnw spring-boot:run
```

Nhận webhook khi chạy local: mở tunnel rồi đặt URL public vào `PAYOS_WEBHOOK_URL`, app sẽ gọi `confirm-webhook` một lần lúc khởi động (bỏ trống thì không gọi).

```bash
cloudflared tunnel --url http://localhost:8080        # hoặc: ngrok http 8080
PAYOS_WEBHOOK_URL=https://<host>/webhooks/payos/payment ./mvnw spring-boot:run
```

PayOS gửi một webhook thử với `orderCode` giả ngay sau `confirm-webhook`; endpoint webhook bỏ qua orderCode không tồn tại. Kiểm tra adapter với PayOS thật mà không phát sinh tiền (chỉ tạo, đọc, hủy link 2.000đ):

```bash
set -a; . ./.env; set +a; PAYOS_LIVE=true ./mvnw -q test -Dtest=PayOsLiveSmokeTest
```

Kênh chi (payout) dùng cho refund nằm ở `PayOsPayoutClient`, credential riêng `app.payos.payout.*`, dựng lazy nên thiếu key vẫn boot được.

## Hoàn tiền

Refund = **một lệnh chi mới** qua kênh payout PayOS, không phải đảo giao dịch thu (PayOS thu account-to-account nên không có API hoàn).
Hoàn **theo vé**: `refund_items` giữ từng vé và số tiền của nó; một đơn chỉ có một refund chạy tại một thời điểm.

```
REQUESTED ──provider nhận──▶ PROCESSING ──▶ SUCCEEDED (vé REFUNDED, kho cộng lại)
    │                            │──────────▶ FAILED    (vé về ACTIVE, kho giữ nguyên)
    │                            └──quá hạn──▶ MANUAL_REVIEW
    ├──ví chi thiếu──▶ AWAITING_FUNDS ──job, ví đủ──▶ REQUESTED
    └──đích ≠ tài khoản đã trả──▶ MANUAL_REVIEW (chưa gọi provider)
```

Kho chỉ được cộng lại khi `SUCCEEDED`, và đúng một lần — `RefundResultHandler` là nơi duy nhất làm việc đó.
Ba job trong `RefundJobs`: poll kết quả (10s, với PayOS đây là đường **duy nhất** vì không có webhook lệnh chi),
gửi tiếp hàng chờ ví (60s), cứu lệnh `REQUESTED` kẹt (60s).

Muốn chi tiền thật cần thêm trên dashboard PayOS: bật kênh chi hộ, liên kết ví Bao Kim, **whitelist IP server**
(localhost bị từ chối), nạp tiền ví. Thiếu key chi thì app vẫn boot, chỉ lần tạo refund đầu báo `PAYOUT_NOT_CONFIGURED`.

Config: `app.refund.*` trong `application.yaml` (`fee-percent` phí hủy, `payout-enabled` kill switch,
`processing-timeout`, `awaiting-funds-timeout`, `merchant-bin`/`merchant-account` chặn hoàn vòng về tài khoản thu).

**Hiểu và tự sửa được: `../context/docs/spec-plan/huong-dan-refund.md`** — mô hình tư duy, thứ tự đọc code,
7 bước xây lại, và 5 cái bẫy làm mất tiền kèm chỗ code chặn nó.

## Lỗi

Mọi lỗi là `application/problem+json`:

```json
{ "type": "about:blank", "title": "Conflict", "status": 409,
  "detail": "Hạng vé VIP không đủ số lượng", "instance": "/api/v1/orders",
  "code": "TIER_SOLD_OUT", "traceId": "3f1c..." }
```

- `code`: mã máy đọc cho FE. Lỗi validation: `code = VALIDATION` và thêm `errors: [{ "field", "message" }]`.
- `traceId`: id của request để tra log.
- 401/403 từ Spring Security cũng theo dạng này (`UNAUTHORIZED`, `FORBIDDEN`), kèm header `WWW-Authenticate: Bearer ...`.

## trace_id và masking log

- Mỗi request có `trace_id`: lấy từ header `X-Request-Id` nếu client gửi (tối đa 64 ký tự `[A-Za-z0-9._-]`), không thì sinh UUID. Server luôn trả lại `X-Request-Id`. Mọi dòng log trong request in `[trace_id=...]` (`TraceIdFilter`, MDC). Job nền tự sinh trace_id riêng.
- Message log được che tự động (`logback-spring.xml`, `MaskingMessageConverter`): dãy 10–19 chữ số giữ 4 số cuối, email giữ 2 ký tự đầu, giá trị sau `api_key`, `checksum_key`, `client_id`, `password`, `secret` và `Bearer <token>` thành `***`.

```bash
curl -si http://localhost:8080/api/v1/users -H 'X-Request-Id: demo-1' | grep -iE 'HTTP|x-request-id|^\{'
# HTTP/1.1 401 ... X-Request-Id: demo-1 ... {"...","code":"UNAUTHORIZED","traceId":"demo-1"}
```

## CORS và FE

FE (`../fe`, React + Vite) ở dev proxy `/api` sang `http://localhost:8080` (`vite.config.js`) nên cùng origin, không cần CORS. Khi FE chạy khác origin, đặt `app.cors.allowed-origins` (nhiều origin cách nhau bằng dấu phẩy) cho `/api/**`; header cho phép: `Authorization`, `Content-Type`, `Idempotency-Key`, `X-Request-Id`.

## Test

```bash
./mvnw test                                    # unit + integration (Testcontainers, cần Docker)
./mvnw test -Dtest='TraceIdFilterTest,MaskingMessageConverterTest,GlobalExceptionHandlerTest,LogbackConfigTest'
```
