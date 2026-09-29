# Sơ đồ hệ thống ticketing

ERD sinh từ schema thật của Postgres (Flyway `V1`–`V6`); state machine và luồng xử lý đọc từ code.

- [1–6. ERD theo nhóm bảng](#1-toàn-cảnh)
- [7. State machine: Order](#7-state-machine-order)
- [8. State machine: Payment](#8-state-machine-payment)
- [9. Luồng tạo đơn và link thanh toán](#9-luồng-tạo-đơn-và-link-thanh-toán)
- [10. Luồng webhook thanh toán](#10-luồng-webhook-thanh-toán)
- [11. Luồng hết hạn và đối soát](#11-luồng-hết-hạn-và-đối-soát)

> Xem sơ đồ: GitHub, IntelliJ và VS Code (extension Markdown Preview Mermaid) đều render trực tiếp.

---

## 1. Toàn cảnh

Chỉ vẽ quan hệ và khóa; cột đầy đủ ở phần chi tiết bên dưới.

```mermaid
erDiagram
    users ||--o{ refresh_tokens : "phiên"
    users ||--o{ password_reset_tokens : "token quên MK"
    users |o--o| organizers : "hồ sơ BTC"
    users |o--o{ orders : "đặt"
    users |o--o{ tickets : "sở hữu"
    users |o..o{ contact_messages : "gửi (không FK)"

    organizers |o--o{ events : "tổ chức"
    events ||--o{ ticket_tiers : "hạng vé"
    ticket_tiers ||--|| inventory : "tồn kho"

    events ||--o{ orders : "đơn của sự kiện"
    orders ||--o{ order_items : "dòng đơn"
    ticket_tiers ||--o{ order_items : ""
    orders ||--o{ tickets : "vé đã cấp"
    ticket_tiers ||--o{ tickets : ""

    orders ||--o{ payments : "lần thanh toán"
    orders ||--o{ refunds : "lần hoàn tiền"
    payments |o--o{ refunds : "hoàn của payment"
    refunds ||--o{ refund_items : "vé được hoàn"
    tickets ||--o{ refund_items : ""
    refunds ||--o{ refund_inquiries : "khiếu nại"

    webhook_events {
        uuid id PK
    }
    gateway_call_logs {
        uuid id PK
    }
    idempotency_records {
        uuid id PK
    }
    ledger_entries {
        uuid id PK
    }
    wallet_snapshots {
        uuid id PK
    }
```

5 bảng này **không có khóa ngoại** — chúng tham chiếu mềm qua `ref_type` + `ref_id` hoặc đứng độc lập, để ghi sổ/audit không chặn việc xóa dữ liệu nghiệp vụ.

---

## 2. Danh tính & tài khoản

```mermaid
erDiagram
    users ||--o{ refresh_tokens : ""
    users ||--o{ password_reset_tokens : ""
    users |o--o| organizers : ""
    users |o..o{ contact_messages : "không FK"

    users {
        uuid id PK
        varchar full_name
        varchar email UK
        varchar password_hash "tài khoản Google lưu hash chuỗi ngẫu nhiên"
        varchar role "CUSTOMER | ORGANIZER | ADMIN"
        varchar phone
        text avatar_url
        text bio
        timestamptz created_at
        timestamptz updated_at
    }

    organizers {
        uuid id PK
        uuid user_id FK,UK "null = BTC chưa gắn tài khoản"
        varchar slug UK
        varchar name
        text description
        text logo_url
        text cover_url
        text website
        varchar city
        varchar contact_email
        varchar contact_phone
        bool verified
        timestamptz created_at
        timestamptz updated_at
    }

    refresh_tokens {
        uuid id PK
        uuid user_id FK
        varchar token_hash UK "chỉ lưu sha256, không lưu token gốc"
        timestamptz expires_at
        timestamptz revoked_at
        timestamptz created_at
    }

    password_reset_tokens {
        uuid id PK
        uuid user_id FK
        varchar token_hash UK
        timestamptz expires_at
        timestamptz used_at "dùng rồi thì không dùng lại được"
        timestamptz created_at
    }

    contact_messages {
        uuid id PK
        uuid user_id "null khi khách chưa đăng nhập; KHÔNG có ràng buộc FK"
        varchar name
        varchar email
        varchar subject
        text message
        timestamptz created_at
    }
```

---

## 3. Sự kiện & kho vé

```mermaid
erDiagram
    organizers |o--o{ events : ""
    events ||--o{ ticket_tiers : ""
    ticket_tiers ||--|| inventory : ""

    events {
        uuid id PK
        uuid organizer_id FK
        varchar slug UK "FE dùng slug trên URL"
        varchar name
        varchar category
        varchar status "DRAFT | PUBLISHED | UPCOMING | CANCELLED"
        timestamptz starts_at
        timestamptz ends_at
        int refund_deadline_hours "mặc định 48"
        jsonb venue
        jsonb description
        jsonb schedule
        text cover_image_url
        text cover_image_alt
        text tagline
        bool featured
        timestamptz published_at
        timestamptz created_at
        timestamptz updated_at
    }

    ticket_tiers {
        uuid id PK
        uuid event_id FK
        varchar name
        text description
        bigint price "VND, CHECK >= 0"
        int total_quantity "CHECK >= 0"
        int max_per_order "mặc định 6, CHECK > 0"
        timestamptz created_at
    }

    inventory {
        uuid ticket_tier_id PK,FK "PK trùng FK = quan hệ 1-1"
        int available "CHECK >= 0, đây là thứ chặn bán quá số vé"
        bigint version "optimistic lock"
        timestamptz updated_at
    }
```

`inventory` tách khỏi `ticket_tiers` vì đây là dòng bị khóa (`SELECT ... FOR UPDATE`) mỗi lần checkout. Tách ra thì việc BTC sửa thông tin hạng vé không tranh khóa với người đang mua.

---

## 4. Đơn hàng & vé

```mermaid
erDiagram
    events ||--o{ orders : ""
    users |o--o{ orders : ""
    orders ||--o{ order_items : ""
    ticket_tiers ||--o{ order_items : ""
    orders ||--o{ tickets : ""
    ticket_tiers ||--o{ tickets : ""
    users |o--o{ tickets : ""

    orders {
        uuid id PK
        bigint order_code UK "số gửi PayOS, epoch-millis*1000 + random"
        uuid event_id FK
        uuid user_id FK "null = khách mua không đăng nhập"
        varchar status "PENDING_PAYMENT | PAID | REFUND_PROCESSING | REFUNDED | PARTIALLY_REFUNDED | REFUND_FAILED | EXPIRED | CANCELLED | MANUAL_REVIEW"
        bigint subtotal_amount "tiền vé, dashboard tính doanh thu trên cột này"
        bigint fee_amount
        bigint total_amount
        bigint paid_amount
        bigint refunded_amount
        varchar customer_name
        varchar customer_email
        varchar customer_phone
        timestamptz expires_at "quá hạn thì OrderExpiryJob xử lý"
        varchar idempotency_key UK
        bigint version
        timestamptz paid_at
        timestamptz created_at
        timestamptz updated_at
    }

    order_items {
        uuid id PK
        uuid order_id FK "ON DELETE CASCADE"
        uuid ticket_tier_id FK
        varchar tier_name "chụp lại tên lúc mua, BTC đổi tên sau không ảnh hưởng"
        int quantity "CHECK > 0"
        bigint unit_price "chụp lại giá lúc mua"
    }

    tickets {
        uuid id PK
        uuid order_id FK
        uuid ticket_tier_id FK
        uuid owner_id FK "người sở hữu vé"
        bigint price
        varchar ticket_code UK
        varchar status "ACTIVE | REFUND_PENDING | REFUNDED"
        timestamptz issued_at
        timestamptz updated_at
    }
```

`order_items` là **ý định mua** (mua mấy vé hạng nào), `tickets` là **vé thật** chỉ sinh ra khi đơn đã PAID. Cả hai chụp lại `tier_name`/`price` tại thời điểm mua.

---

## 5. Thanh toán & hoàn tiền

```mermaid
erDiagram
    orders ||--o{ payments : ""
    orders ||--o{ refunds : ""
    payments |o--o{ refunds : ""
    refunds ||--o{ refund_items : ""
    tickets ||--o{ refund_items : ""
    refunds ||--o{ refund_inquiries : ""

    payments {
        uuid id PK
        uuid order_id FK "1 đơn có thể nhiều payment khi tạo lại link"
        varchar provider "PAYOS | MOCK"
        varchar provider_payment_id "UNIQUE cùng provider"
        varchar payment_link_id
        text checkout_url
        varchar provider_transaction_ref
        bigint amount
        varchar status "CREATED | PENDING | PAID | UNDERPAID | PAID_LATE | FAILED | EXPIRED"
        varchar payer_bank_bin "nguồn để hoàn về đúng tài khoản đã trả"
        varchar payer_account_number
        jsonb raw_webhook_payload
        timestamptz paid_at
        timestamptz created_at
        timestamptz updated_at
    }

    refunds {
        uuid id PK
        uuid order_id FK
        uuid payment_id FK
        varchar original_payment_transaction_id
        bigint amount
        varchar currency "mặc định VND"
        varchar status "REQUESTED | AWAITING_FUNDS | PROCESSING | SUCCEEDED | FAILED | MANUAL_REVIEW"
        varchar execution_method "PAYOUT | NATIVE_REFUND | MANUAL_TRANSFER"
        varchar initiator "USER | SYSTEM | ADMIN"
        varchar provider
        varchar provider_refund_id "UNIQUE cùng provider"
        varchar idempotency_key UK "RF-refundId-attempt, thứ chặn chi tiền hai lần"
        int attempt
        varchar failure_code
        text failure_reason
        varchar destination_bin "BIN Napas 6 số"
        varchar destination_account
        bool destination_is_payer "false = đích khác người trả, phải admin duyệt"
        text reason
        timestamptz submitted_at
        timestamptz last_polled_at
        timestamptz queued_since "xếp hàng khi ví không đủ tiền"
        timestamptz created_at
        timestamptz updated_at
    }

    refund_items {
        uuid id PK
        uuid refund_id FK "ON DELETE CASCADE"
        uuid ticket_id FK
        bigint amount
    }

    refund_inquiries {
        uuid id PK
        uuid refund_id FK
        text message
        timestamptz created_at
        timestamptz resolved_at
    }
```

`UNIQUE (refund_id, ticket_id)` trên `refund_items` chặn hoàn cùng một vé hai lần trong một refund.

---

## 6. Hạ tầng: audit, idempotency, sổ tiền

Không có khóa ngoại, tham chiếu mềm qua `ref_type` + `ref_id`.

```mermaid
erDiagram
    webhook_events {
        uuid id PK
        varchar provider
        varchar event_id "UNIQUE cùng provider, đây là thứ chặn webhook trùng"
        varchar event_type "payment | refund"
        jsonb raw_payload
        bool signature_valid
        varchar processing_result "PROCESSED | DUPLICATE | IGNORED | REJECTED_SIGNATURE | FAILED"
        timestamptz received_at
        timestamptz processed_at
    }

    gateway_call_logs {
        uuid id PK
        varchar ref_type "ORDER | PAYMENT | REFUND"
        uuid ref_id "không FK: giữ log kể cả khi bản ghi gốc bị xóa"
        varchar direction "OUTBOUND | INBOUND"
        text endpoint
        jsonb request_masked "đã che secret trước khi lưu"
        jsonb response_raw
        int http_status
        int duration_ms
        varchar trace_id
        timestamptz created_at
    }

    idempotency_records {
        uuid id PK
        varchar scope "CHECKOUT | REFUND"
        varchar idem_key "UNIQUE cùng scope"
        varchar request_hash "băm body để phát hiện cùng key khác body"
        int response_status
        jsonb response_body "trả lại nguyên kết quả cũ"
        timestamptz created_at
    }

    ledger_entries {
        uuid id PK
        varchar account "BANK_COLLECTION | PAYOUT_WALLET | CUSTOMER_LIABILITY | FEES"
        varchar direction "DEBIT | CREDIT"
        bigint amount
        varchar ref_type "ORDER | REFUND"
        uuid ref_id "không FK: sổ phải sống lâu hơn bản ghi nghiệp vụ"
        timestamptz occurred_at
    }

    wallet_snapshots {
        uuid id PK
        bigint balance
        bigint committed
        bigint available
        bigint liability
        numeric coverage
        timestamptz taken_at
    }
```

---

## Ghi chú

**Sổ bút toán kép (`ledger_entries`)** — `LedgerService` ghi, mỗi sự kiện một bộ vế cân nhau:

| Sự kiện | Nợ | Có | Ghi ở |
|---|---|---|---|
| Đơn sang PAID | `BANK_COLLECTION` | `CUSTOMER_LIABILITY` (+ `FEES` nếu `fee_amount` > 0) | `PaymentServiceImpl.apply()` |
| Refund SUCCEEDED | `CUSTOMER_LIABILITY` | `PAYOUT_WALLET` | `RefundResultHandler.apply()` |

Chỉ thêm, không sửa không xóa. Lệnh hoàn **đang bay không vào sổ** — chưa chuyển tiền thì chưa phải bút toán;
phần đó `RefundResultHandler.assertNotOverRefunded()` đếm từ bảng `refunds`. Trần hoàn tiền vì vậy tính từ
dữ liệu bất biến (sổ) thay vì từ cột `orders.refunded_amount` sửa được.

`V6__ledger_backfill.sql` dựng lại bút toán cho đơn đã PAID trước khi có sổ; `db/seed-dev.sql` cũng sinh
bút toán cho đơn seed, nếu không thì `paidIn = 0` và invariant tự tắt.

**2 bảng có trong schema nhưng code chưa dùng** — không có entity JPA nào map tới:

| Bảng | Dự định |
|---|---|
| `wallet_snapshots` | Lịch sử số dư ví chi (hiện `WalletService` tính trực tiếp từ provider) |
| `refund_inquiries` | Khiếu nại hoàn tiền |

**Quan hệ nét đứt** (`contact_messages.user_id`) là tham chiếu mềm: có cột nhưng không có ràng buộc FK, nên xóa user không vướng tin nhắn cũ.

**Xóa lan (`ON DELETE CASCADE`)** chỉ có ở 2 chỗ: `order_items → orders` và `refund_items → refunds`. Mọi FK còn lại là `NO ACTION` — cố ý, để không xóa nhầm dữ liệu tiền bạc.

**Phí sàn đã bỏ** (`app.checkout.fee` mặc định `0`): hệ thống không chia tiền, PayOS trả vào một tài khoản merchant duy nhất và `organizers` không có cột ngân hàng nào. Cột `fee_amount` giữ lại để bật lại được, đơn mới đều là `0`.

**Khóa lạc quan (`version`)** ở `orders` và `inventory`.

**Chuỗi bí mật không bao giờ lưu dạng gốc**: `refresh_tokens.token_hash` và `password_reset_tokens.token_hash` đều là sha256.

Schema do Flyway quản lý ở `src/main/resources/db/migration`; Hibernate chỉ `validate`, không tự sửa bảng.

---

## 7. State machine: Order

Đọc từ `domain/order/Order.java`. Mỗi mũi tên là một method; điều kiện chuyển nằm ngay trong method đó, sai trạng thái thì ném lỗi chứ không chuyển âm thầm.

```mermaid
stateDiagram-v2
    [*] --> PENDING_PAYMENT : create()

    PENDING_PAYMENT --> PAID : markPaid() khi webhook hoặc poll báo đủ tiền
    PENDING_PAYMENT --> EXPIRED : expire() khi quá expires_at
    PENDING_PAYMENT --> CANCELLED : cancel() khi khách bấm hủy

    EXPIRED --> MANUAL_REVIEW : markManualReview() khi tiền vào muộn
    CANCELLED --> MANUAL_REVIEW : markManualReview() khi tiền vào muộn

    PAID --> REFUND_PROCESSING : startRefund()
    PARTIALLY_REFUNDED --> REFUND_PROCESSING : startRefund()
    REFUND_FAILED --> REFUND_PROCESSING : startRefund()

    REFUND_PROCESSING --> REFUNDED : onRefundSucceeded() và không còn vé ACTIVE
    REFUND_PROCESSING --> PARTIALLY_REFUNDED : onRefundSucceeded() và còn vé
    REFUND_PROCESSING --> REFUND_FAILED : onRefundFailed()

    REFUNDED --> [*]
    MANUAL_REVIEW --> [*]
```

| Guard trong code | Ý nghĩa |
|---|---|
| `requirePending()` | `expire()` và `markPaid()` chỉ chạy từ `PENDING_PAYMENT` |
| `cancel()` | Khác `PENDING_PAYMENT` → 409 `ORDER_NOT_CANCELLABLE` |
| `isRefundable()` | Chỉ `PAID`, `PARTIALLY_REFUNDED`, `REFUND_FAILED` mới mở refund được |
| `markManualReview()` | Chỉ từ `EXPIRED` hoặc `CANCELLED` |
| `requireRefundProcessing()` | Kết quả refund chỉ nhận khi đang `REFUND_PROCESSING` |

`REFUNDED` là terminal: nó không nằm trong `isRefundable()` nên không mở thêm refund được nữa.

---

## 8. State machine: Payment

Một đơn có thể có **nhiều** payment (mỗi lần tạo lại link là một bản ghi); `PaymentServiceImpl` luôn lấy bản ghi mới nhất.

```mermaid
stateDiagram-v2
    [*] --> PENDING : pending() khi tạo link thành công

    PENDING --> PAID : đơn còn PENDING_PAYMENT và tiền vào đủ
    PENDING --> UNDERPAID : đơn còn PENDING_PAYMENT nhưng tiền vào thiếu
    PENDING --> FAILED : webhook báo thất bại
    PENDING --> PAID_LATE : tiền vào khi đơn đã EXPIRED hoặc CANCELLED
    PENDING --> EXPIRED : expire() khi đơn đóng mà chưa thu được đồng nào

    FAILED --> PAID : khách trả lại trên cùng link
    FAILED --> UNDERPAID : khách trả lại nhưng thiếu
    FAILED --> EXPIRED : expire()

    CREATED --> PENDING : chỉ có trong schema, code hiện tạo thẳng PENDING

    PAID --> [*]
    UNDERPAID --> [*]
    PAID_LATE --> [*]
    EXPIRED --> [*]
```

**Điều quan trọng, dễ hiểu nhầm:** `Payment.apply()` gán status **không kiểm tra gì cả**. Toàn bộ điều kiện chuyển nằm ở `PaymentServiceImpl.apply()` (bên gọi), theo đúng bảng này:

| Trạng thái đơn | Webhook | Kết quả |
|---|---|---|
| `PAID` | bất kỳ | `IGNORED`, không đụng payment |
| `PENDING_PAYMENT` | thất bại, payment `isAwaitingMoney()` | `markFailed()` |
| `PENDING_PAYMENT` | thành công, `amount < total` | `markUnderpaid()` |
| `PENDING_PAYMENT` | thành công, đủ tiền | `confirmPaid()` + `order.markPaid()` + cấp vé |
| `EXPIRED` / `CANCELLED` | thành công | `markLate()` + `order.markManualReview()` |
| còn lại | | `IGNORED` |

`isAwaitingMoney()` = `PENDING`, `CREATED`, `FAILED` — nghĩa là **chưa nhận đồng nào**. Webhook thất bại chỉ được ghi đè ở nhóm này, không bao giờ xóa dấu vết tiền đã vào (`UNDERPAID`, `PAID_LATE` giữ nguyên để đối soát).

---

## 9. Luồng tạo đơn và link thanh toán

`POST /api/v1/orders` — `CheckoutServiceImpl.checkout()`.

```mermaid
sequenceDiagram
    autonumber
    actor FE
    participant C as OrderController
    participant I as IdempotencyService
    participant S as CheckoutService
    participant DB as Postgres
    participant G as PayOS

    FE->>C: POST /orders + Idempotency-Key
    C->>I: execute(CHECKOUT, key, body)
    I->>DB: tìm idempotency_records theo (scope, key)

    alt key đã dùng, cùng body
        I-->>FE: trả nguyên response cũ, KHÔNG gọi provider
    else key đã dùng, khác body
        I-->>FE: 422 IDEMPOTENCY_KEY_REUSED
    else key mới
        I->>S: checkout()

        rect rgb(238, 244, 255)
            Note over S,DB: TX1 giữ vé
            S->>DB: đọc event, kiểm tra đang mở bán
            S->>DB: SELECT FOR UPDATE inventory theo tier id TĂNG DẦN
            Note right of DB: khóa theo thứ tự cố định<br/>để không deadlock chéo
            S->>DB: trừ kho, INSERT order PENDING_PAYMENT + order_items
        end

        Note over S,G: gọi provider NGOÀI transaction
        S->>G: createPaymentLink(orderCode, amount, items)

        alt provider trả link
            G-->>S: paymentLinkId + checkoutUrl
            S->>DB: ghi gateway_call_logs OUTBOUND
            rect rgb(238, 244, 255)
                Note over S,DB: TX2
                S->>DB: INSERT payment PENDING
            end
            S-->>FE: 201 + checkoutUrl
        else provider lỗi hoặc timeout
            G-->>S: exception
            S->>DB: ghi gateway_call_logs kèm error
            rect rgb(255, 238, 238)
                Note over S,DB: TX bù
                S->>DB: order.cancel() + trả vé về kho
            end
            S-->>FE: 502 PAYMENT_LINK_FAILED
        end
    end
```

Điểm cốt lõi: **không bao giờ gọi provider khi đang giữ transaction**. Giữ vé xong thì đóng TX1, gọi PayOS, rồi mở TX2 ghi payment. Đổi lại phải có nhánh bù (hủy đơn + trả vé) khi provider lỗi.

---

## 10. Luồng webhook thanh toán

`POST /webhooks/payos/payment` — `PaymentServiceImpl.handleWebhook()`.

```mermaid
sequenceDiagram
    autonumber
    participant G as PayOS
    participant W as WebhookController
    participant P as PaymentService
    participant A as PayOsPaymentGateway
    participant DB as Postgres

    G->>W: POST raw body + headers
    W->>P: handleWebhook(provider, rawBody, headers)
    P->>A: verifyAndParse(rawBody)
    Note right of A: HMAC-SHA256 trên data đã sort key

    alt chữ ký sai
        A-->>P: InvalidWebhookSignatureException
        P->>DB: lưu webhook_events REJECTED_SIGNATURE
        P-->>G: 401
    else chữ ký hợp lệ
        A-->>P: PaymentEvent
        P->>DB: INSERT webhook_events, UNIQUE (provider, event_id)

        alt trùng event_id
            DB-->>P: DataIntegrityViolationException
            P-->>G: 200 DUPLICATE
        else lần đầu
            rect rgb(238, 244, 255)
                Note over P,DB: một transaction
                P->>DB: SELECT FOR UPDATE order theo order_code
                P->>DB: lấy payment mới nhất của đơn

                alt không khớp đơn nào
                    P-->>G: 200 IGNORED
                else đơn đã PAID
                    P-->>G: 200 IGNORED
                else đơn PENDING và tiền vào đủ
                    P->>DB: payment PAID, order PAID, paid_at
                    P->>DB: INSERT tickets cho từng vé
                    P-->>G: 200 PROCESSED
                else đơn PENDING và tiền vào thiếu
                    P->>DB: payment UNDERPAID
                    P-->>G: 200 PROCESSED
                else đơn đã EXPIRED hoặc CANCELLED
                    P->>DB: payment PAID_LATE, order MANUAL_REVIEW
                    Note right of DB: kho đã trả rồi nên KHÔNG cấp vé
                    P-->>G: 200 PROCESSED
                end

                P->>DB: webhook_events.processing_result + gateway_call_logs INBOUND
            end
        end
    end
```

Chống webhook trùng bằng **ràng buộc DB** (`UNIQUE (provider, event_id)`) chứ không bằng kiểm tra trong code — hai webhook đến cùng lúc thì một cái chắc chắn thua ở tầng DB.

Trừ lỗi chữ ký (401), mọi trường hợp còn lại đều trả **200**. Provider chỉ cần biết "đã nhận", không cần biết nghiệp vụ xử lý ra sao.

---

## 11. Luồng hết hạn và đối soát

Đây là lý do một đơn có thể thành `PAID` mà **không** có webhook nào về.

```mermaid
sequenceDiagram
    autonumber
    participant J as OrderExpiryJob
    participant P as PaymentService
    participant G as PayOS
    participant DB as Postgres

    Note over J: mỗi 60 giây
    J->>DB: tìm order PENDING_PAYMENT quá expires_at
    loop mỗi đơn quá hạn
        J->>P: settleExpired(orderId)
        P->>G: getPaymentStatus(providerPaymentId)

        alt provider báo ĐÃ TRẢ
            G-->>P: PAID
            P->>P: dựng PaymentEvent giả lập rồi đi CHUNG đường với webhook
            P->>DB: payment PAID, order PAID, cấp vé
            Note right of DB: webhook rớt vẫn không mất đơn
        else chưa trả
            G-->>P: PENDING / CANCELLED / EXPIRED
            P->>DB: order EXPIRED + trả vé về kho
            P->>G: cancelPaymentLink() — lỗi chỉ log, không chặn
        end
    end
```

`PaymentReconcileJob` chạy mỗi 5 phút cũng theo đúng đường này nhưng cho đơn **sắp** hết hạn, để phát hiện sớm khi webhook rớt.

Cả hai job đều đi qua `record()` giống hệt webhook, nên kết quả của một đơn không phụ thuộc vào việc nó được xử lý bằng webhook hay bằng poll.
