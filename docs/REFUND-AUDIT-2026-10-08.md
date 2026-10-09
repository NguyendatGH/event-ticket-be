# Kiểm tra luồng hoàn tiền (refund)

Ngày kiểm: 2026-10-08. Ảnh chụp tại ngày kiểm; code đổi sau ngày này thì kết luận có thể không còn đúng.

Cách kiểm:

1. Đọc code ba repo: `be/` (`RefundService`, `RefundResultHandler`, `RefundJobs`, `WalletService`, `RefundNotifier`,
   các controller, adapter BankSim/PayOS), `fe/` (trang khách + trang BTC), `bankSimulate/` (`GatewayRuntimeService`).
2. Chạy E2E thật trên DB tạm (`payment_lab_repro` + `encore_repro`, gateway :28090, Encore :18080, đi qua BankSim),
   hai pha:
   - **Pha 1** (script E2E refund có từ trước): **20/20 PASS**.
   - **Pha 2** (mới): restart gateway với ví chi = 1,5 lần giá vé. **10/11 PASS**, 1 FAIL là lỗi thật (mục 3.1).
3. Test tự động BE cùng ngày: 149 test, 0 fail (trong đó `RefundFlowTests` 39 test).

DB tạm đã xóa sau khi kiểm. Không sửa dòng code nào.

## Cập nhật: đã sửa (cùng ngày 2026-10-08)

| Mục | Trạng thái | Sửa ở đâu | Kiểm bằng |
|---|---|---|---|
| 3.1 Kiểm ví tính trùng | **Đã sửa** | `WalletService.availableFor(refund)` cộng lại phần của chính refund đang ở REQUESTED; `RefundService.submit` dùng hàm này | Test `walletJustEnoughForThisRefundPaysOutWithoutQueueing` (đường khách tạo + đường BTC RETRY; đã xác nhận test FAIL với code cũ). E2E: ví 1,5 lần giá → `SUCCEEDED` ngay, không mail "ví thiếu" |
| 3.2 Mã lỗi BankSim | **Đã sửa** | `BankSimPaymentGateway.refundRejected`: đọc `code` trong body; `BANK_PROFILE_NOT_FOUND` → `INVALID_DESTINATION`, `INSUFFICIENT_PAYOUT_BALANCE` giữ nguyên, mã khác giữ nguyên, body không phải JSON → `BANK_SIM_REJECTED`. FE thêm nhãn `INVALID_DESTINATION` cho khách | 3 test trong `BankSimPaymentGatewayTest` (1 test qua HTTP thật). E2E: hoàn về BIDV → `FAILED / INVALID_DESTINATION`, vé `ACTIVE` ngay |
| 3.3 Thiếu mail | **Đã sửa** | BTC: mail "cần xử lý tay" khi khách xin hoàn về tài khoản lạ, khi `PROCESSING_TIMEOUT`, khi provider từ chối mã lạ, khi `ON_HOLD`/`REVERSED`. Khách: mail "đã hoàn" (`refund-succeeded.html`) và "chưa hoàn được" (`refund-failed.html`), gửi `afterCommit` từ `RefundResultHandler`; BTC hủy thì chỉ có mail hủy | 5 test trong `RefundFlowTests` (đúng một mail, không gửi lại khi job chạy lại / gửi lại cùng key) + 3 test `MailerRefundMailTest` (render template thật). E2E: thấy đủ mail trong log |
| 3.4 Log refund thiếu mã | **Đã sửa** | `RefundService.logScope/tagLog`: mọi lối vào refund (khách tạo, job, BTC chốt, webhook) gắn merNo/terNo của payment gốc, orderCode, và tradeNo = mã refund ở gateway khi đã có | E2E: `[MOCK][MerNo000010-TerNo000010-TradeNo000008-1791477191351268][refundId=…]` |
| 4 Gateway refund trong RAM | **Đã sửa** (phần lưu trữ + gắn giao dịch gốc) | Gateway: `RefundPayoutService` + migration `V13` dùng `refund_transactions`; chặn hoàn quá số đã thu, giao dịch chưa PAID, giao dịch của merchant khác. Encore gửi kèm `providerPaymentId` (`GatewayCredentialResolver.providerPaymentIdForRefundReference`). Vẫn còn: không trừ settlement merchant, không ghi sổ cái gateway, luôn thành công ngay | E2E: refund trước restart vẫn tra được, gửi lại cùng mã không chi lần hai, ví chi không reset; 403/409 đúng ở các ca chặn. Test `refundCarriesTheOriginalPayment` |

Sau khi sửa: BE 162 test, 0 fail, 1 skip; FE 211/211, lint 0 lỗi; E2E: 3 bản sửa đầu 13/13, lưu refund + log 18/18, script refund cũ 20/20.

Phần dưới đây giữ nguyên kết quả kiểm **trước khi sửa**.

## 1. Kết luận

**Luồng chính đã hoàn thiện và chạy đúng từ đầu tới cuối.** Còn:

- **1 lỗi thật**: kiểm ví chi tính trùng chính refund đang gửi → refund bị xếp hàng "ví thiếu tiền" oan, BTC nhận mail
  oan (mục 3.1).
- **1 chỗ phân loại lỗi sai**: mọi lỗi 4xx từ BankSim thành một mã chung, nên lỗi lẽ ra chốt thất bại ngay lại thành
  "chờ BTC xử lý" (mục 3.2).
- **Thiếu thông báo**: khách không nhận mail khi được hoàn/bị từ chối; BTC không nhận mail cho ca phổ biến nhất cần duyệt
  (mục 3.3).
- **Phía gateway mô phỏng còn sơ sài**: refund nằm trong RAM, không gắn với giao dịch gốc, luôn thành công (mục 4).

## 2. Đã có và đã chạy đúng

### 2.1 Phía khách

| Việc | Ở đâu | Kiểm |
|---|---|---|
| Xin hoàn **theo từng vé**, kèm email liên hệ, lý do | `POST /api/v1/orders/{id}/refunds` → 202; FE `RefundDialog` | E2E |
| Chống gửi trùng bằng `Idempotency-Key` | `IdempotencyService` | code |
| Chính sách: đơn phải hoàn được, vé phải `ACTIVE`, còn trong hạn hủy (`refund_deadline_hours` trước sự kiện), trừ `app.refund.fee-percent`, phí dịch vụ không hoàn | `RefundPolicy` | E2E (hoàn lại vé đã hoàn → 409 `TICKET_NOT_REFUNDABLE`) |
| QR: tự hoàn về đúng tài khoản người trả mà gateway báo | `RefundService.open` (so theo **số tài khoản**) | E2E (đích = `970436:0123456789012`) |
| Thẻ: gateway không biết tài khoản người trả → bắt khách chọn | 409 `PAYER_ACCOUNT_UNKNOWN` | E2E |
| Theo dõi trạng thái | FE `RefundSection` poll 3s; trang đơn poll 5s | code |

### 2.2 Phía BTC

| Việc | Ở đâu | Kiểm |
|---|---|---|
| Hộp thư refund, badge đếm việc cần làm (`MANUAL_REVIEW` + `AWAITING_FUNDS`) | `/organizer/refunds`, `RefundsInboxLink`, `useOrganizerRefunds` poll 5s | E2E (thấy yêu cầu chờ duyệt, thấy hàng chờ) |
| Duyệt tài khoản lạ → gửi lệnh chi (`RETRY`) | `POST /organizer/refunds/{id}/resolve` | E2E (QR và thẻ → SUCCEEDED) |
| Chuyển khoản tay: QR VietQR + số tài khoản + nội dung, rồi đánh dấu đã chuyển (`SUCCEEDED`) hoặc từ chối (`FAILED`) | `RefundInstructionDialog`, `GET …/instruction` | code |
| Hủy yêu cầu (vé về lại khách): bắt buộc ghi lý do, chỉ ở `MANUAL_REVIEW`/`AWAITING_FUNDS`, cấm khi lệnh đã ở provider | `CancelRefundDialog`, `requireCancellable` | E2E (không lý do → 400; hủy → `FAILED`/`CANCELLED_BY_ORGANIZER`, vé `ACTIVE`, đơn `REFUND_FAILED`; hủy lần 2 → 409) |
| Chỉ thấy/chốt refund của sự kiện mình | `resolveOwned`, `instructionOwned` (404 nếu không phải của mình) | code |

Admin **không** chốt refund (cố ý: tiền nằm ở tài khoản BTC, chỉ BTC chuyển tay được). Admin xem được ảnh chụp ví chi
(`GET /api/v1/admin/refunds/wallet`).

### 2.3 Chạy nền và an toàn tiền

| Việc | Ở đâu | Kiểm |
|---|---|---|
| Gọi provider **ngoài** transaction, 3 nhịp TX ngắn | `RefundService.submit` | code |
| Ví thiếu → `AWAITING_FUNDS`, job gửi tiếp theo thứ tự vào hàng (FIFO nghiêm), quá 24h → `MANUAL_REVIEW` | `drainQueue`, `RefundQueueJob` 60s | E2E (pha 2: hàng chờ được chi sau 58s) |
| Timeout lúc gửi → tra lại theo idempotency key, **không gửi lệnh thứ hai** | `adoptByReference`, `RefundRecoveryJob` 60s | code + test |
| Lệnh đang bay quá 30 phút → `MANUAL_REVIEW` (không phải `FAILED`, tránh chi hai lần) | `pollProcessing`, `RefundPollJob` 10s | code + test |
| Một cửa chốt kết quả: khóa đơn trước, refund đã chốt thì bỏ qua (kho không cộng hai lần) | `RefundResultHandler.apply` | code + test |
| Thành công: vé `REFUNDED`, cộng lại kho (khóa tier tăng dần), ghi sổ, cập nhật ví người bán/người mua, đơn `PARTIALLY_REFUNDED`/`REFUNDED` | `RefundResultHandler` | E2E |
| Trần hoàn tiền theo sổ `ledger_entries`; vượt → rollback | `assertNotOverRefunded` | code + test |
| Ghi refund dưới **đúng merchant** đã thu giao dịch gốc | `GatewayCredentialResolver.forRefundReference` | E2E |
| Mail không bao giờ làm sập luồng tiền, chống spam mail | `sendMailQuietly`, `tx.execute` trả `boolean` | code |

## 3. Lỗi và thiếu sót bên Encore

### 3.1 [Lỗi] Kiểm ví chi tính trùng chính refund đang gửi

`WalletService.available(provider)` = số dư ví − tổng refund `REQUESTED` + `PROCESSING`. Khi `RefundService.submit`
kiểm ví, refund đang gửi **cũng đang ở `REQUESTED`**, nên số tiền của nó bị trừ một lần trong `available`, rồi lại đem
so `amount > available`. Ví phải có gần **gấp đôi** số tiền mới qua.

Tái hiện (pha 2): ví chi 3.750.000đ, hoàn 1 vé 2.500.000đ.

```
[REFUND][------1791475495398507][refundId=366f3a8c-…] Refund 366f3a8c-… cần 2500000 > ví còn 1250000: AWAITING_FUNDS
[REFUND][------1791475495398507][refundId=366f3a8c-…] Đã gửi mail AWAITING_FUNDS cho BTC or***@example.com về refund 366f3a8c-…
```

1.250.000 = 3.750.000 − 2.500.000 (chính nó). Hậu quả:

- Refund nằm hàng chờ tới lượt `RefundQueueJob` kế tiếp (đo được 58 giây). `drainQueue` không bị lỗi này vì lúc đó
  refund ở `AWAITING_FUNDS`, không nằm trong "đang cam kết".
- **BTC nhận mail "ví thiếu tiền" oan.**
- Cùng lỗi ở đường `RETRY` (BTC duyệt: `retry()` đưa về `REQUESTED` rồi `submit`) và `recoverStuck`.

Với ví 100 triệu của BankSim thì không lộ ra. Với ví PayOS thật, số dư thường nhỏ, nên sẽ gặp.

Hướng sửa: trừ chính refund ra khỏi `committed` khi kiểm (vd `available(provider) + r.getAmount()` nếu `r` đang
`REQUESTED`, hoặc thêm tham số loại trừ id), kèm test ở `RefundFlowTests` với số dư nằm giữa `amount` và `2 × amount`.

### 3.2 [Phân loại sai] Mọi lỗi 4xx của BankSim thành `BANK_SIM_REJECTED`

`BankSimPaymentGateway.submitRefund` bắt mọi 4xx thành `GatewayRejectedException("BANK_SIM_REJECTED", …)`, bỏ mất mã thật
của gateway (`BANK_PROFILE_NOT_FOUND`, `INSUFFICIENT_PAYOUT_BALANCE`, `REFUND_REFERENCE_CONFLICT`…). `RefundService.onRejected`
phân loại theo mã:

| Mã | Xử lý mong đợi | Với BankSim hiện tại |
|---|---|---|
| `INSUFFICIENT_PAYOUT_BALANCE` | `AWAITING_FUNDS` (vào hàng chờ) | `MANUAL_REVIEW` |
| `INVALID_DESTINATION` (ngân hàng không chi tới được) | `FAILED` ngay, vé về khách | `MANUAL_REVIEW` |
| mã khác | `MANUAL_REVIEW` | `MANUAL_REVIEW` |

E2E (pha 1, hoàn về BIDV `970418`): refund thành `MANUAL_REVIEW / BANK_SIM_REJECTED`, vé kẹt ở `REFUND_PENDING` tới khi
BTC xử lý. FE đã chặn trước các BIN gateway không chi tới được (cờ `supported` ở `/api/v1/config`), nên trên giao diện
khó gặp; gọi API thẳng thì gặp.

Hướng sửa: đọc `code` trong body lỗi của gateway và dịch sang mã chuẩn (`BANK_PROFILE_NOT_FOUND` → `INVALID_DESTINATION`,
`INSUFFICIENT_PAYOUT_BALANCE` giữ nguyên).

### 3.3 [Thiếu] Thông báo

Chỉ có 3 loại mail refund (`resources/mail/refund-*.html`):

| Mail | Gửi cho | Khi nào |
|---|---|---|
| `refund-awaiting-funds` | BTC | Lần đầu vào hàng chờ ví (và khi provider báo thiếu tiền) |
| `refund-needs-review` | BTC | `PAYOUT_DISABLED`, `PAYOUT_UNAVAILABLE`, hàng chờ quá 24h |
| `refund-cancelled` | Khách | BTC hủy yêu cầu |

Chưa có:

- **BTC không được báo khi có yêu cầu cần duyệt tài khoản** (`DESTINATION_REVIEW`). Đây là ca phổ biến nhất: **mọi
  refund thẻ** và mọi refund về tài khoản khác. `create` → `open` đặt `MANUAL_REVIEW` → `submit` thoát sớm, không gửi
  mail. BTC chỉ biết khi tự mở trang (có badge).
- BTC không được báo cho các ca `MANUAL_REVIEW` khác: `PROCESSING_TIMEOUT`, `ON_HOLD`/`REVERSED`, provider từ chối mã lạ.
- **Khách không nhận mail khi hoàn xong (`SUCCEEDED`) hay bị từ chối (`FAILED`)**. Khách chỉ thấy nếu mở trang đơn.

### 3.4 [Nhỏ] Log refund thiếu mã giao dịch

Trong luồng refund, prefix log là `[REFUND][------<orderCode>]`: chưa có merNo/terNo, và tradeNo chỉ có sau khi gateway
cấp `providerRefundId`. Muốn lần một refund từ Encore sang gateway phải đi qua `refundId` → `provider_refund_id`.

## 4. Phía gateway mô phỏng (`bankSimulate/`)

Refund ở gateway là phần mô phỏng mỏng nhất. Code: `GatewayRuntimeService.submitRefund` + `BankService`.

| Điểm | Hiện tại | Hệ quả |
|---|---|---|
| Lưu ở đâu | **RAM** (`refunds`, `refundByReference`); bảng `refund_transactions` có nhưng không dùng | Restart là quên hết. E2E pha 2: refund chi trước restart → `GET /refunds/{id}` trả **404 `REFUND_NOT_FOUND`**; ví chi reset về số ban đầu |
| Gắn với giao dịch gốc | **Không**: request chỉ có `referenceId`, `amount`, `toBin`, `toAccountNumber` | Gateway không kiểm được "hoàn ≤ số đã thu", không biết refund thuộc payment nào. Encore tự kiểm trần theo sổ của mình |
| Kết quả | **Luôn `SUCCEEDED`** ngay (`ConfiguredBankProcessor`), trừ BIN lạ → 409 hoặc ví thiếu → 409 | Không mô phỏng được `PROCESSING` / `FAILED` / `ON_HOLD`, nên nhánh poll, timeout 30 phút, `ON_HOLD` của Encore chỉ được kiểm qua test double, chưa từng chạy với BankSim |
| Tiền lấy từ đâu | "Ví chi" giả dùng chung (`gateway.payout-balance`, mặc định 100 triệu) | Không trừ số dư settlement của merchant, không ghi `sandbox_ledger_entries` (CHECK chỉ cho `CAPTURE`/`SETTLEMENT`) |
| Webhook refund | Không có | Đúng thiết kế: Encore lấy kết quả bằng poll (giống PayOS) |

Rủi ro chỉ có trên mô phỏng: Encore gửi lệnh, gateway chi xong nhưng response bị mất, **rồi gateway restart**. Lúc
`RefundRecoveryJob` tra theo key thì gateway đã quên, nên Encore gửi lại cùng key và gateway chi lần hai. Phía Encore
đã làm đúng; lỗ hổng nằm ở gateway không lưu lệnh chi.

## 5. Phía PayOS (chi tiền thật)

- Code đủ: `PayOsPayoutClient` (credential riêng `PAYOS_PAYOUT_*`, dựng lazy), map trạng thái lệnh chi
  (`COMPLETED` → `SUCCEEDED`, `REJECTED`/`FAILED` → `FAILED`, `PARTIAL_COMPLETED` → `ON_HOLD`, các trạng thái đang xử lý →
  `PROCESSING`), tra theo reference, đọc số dư.
- PayOS không có webhook cho lệnh chi: `verifyAndParseRefund` ném `REFUND_WEBHOOK_UNSUPPORTED`, `RefundPollJob` là đường
  duy nhất.
- **Chưa kiểm lại trong đợt này.** Theo ghi chú ngày 2026-09-29: muốn chi thật còn phải whitelist IP và nạp ví trên
  dashboard PayOS. Đó là việc cấu hình, không phải code. `PayOsLiveSmokeTest` chỉ chạy khi `PAYOS_LIVE=true`.

## 6. Đề xuất sửa (chưa làm)

| # | Việc | Mức |
|---|---|---|
| 1 | Sửa kiểm ví tính trùng (3.1) + test số dư nằm giữa `amount` và `2 × amount` | **Lỗi**, nên sửa trước |
| 2 | Dịch mã lỗi 4xx của BankSim sang mã chuẩn (3.2) | Phân loại sai |
| 3 | Mail cho BTC khi có `DESTINATION_REVIEW` và các ca `MANUAL_REVIEW` khác; mail cho khách khi `SUCCEEDED`/`FAILED` (3.3). Mọi mail theo đúng mẫu chống spam: chỉ gửi khi trạng thái thật sự vừa đổi, gửi sau commit | Thiếu tính năng |
| 4 | Gateway lưu refund vào DB (`refund_transactions` có sẵn), gắn với payment gốc, kiểm trần, ghi sổ; thêm cách mô phỏng `PROCESSING`/`FAILED` | Mô phỏng |
| 5 | Gắn merNo/terNo vào log refund (3.4) | Nhỏ |

## Phụ lục: kết quả E2E

### Pha 1 (20/20)

```
== 1. QR: hoàn về đúng tài khoản đã trả -> gateway chi tự động
PASS đơn QR 2 vé -> PAID
PASS xin hoàn 1 vé, không gửi destination -> 202
PASS refund -> SUCCEEDED (gateway đã chi)
PASS vé đã hoàn -> REFUNDED, vé còn lại vẫn ACTIVE
PASS đơn -> PARTIALLY_REFUNDED
PASS đích chi = tài khoản người trả mà gateway báo (970436:0123456789012)
PASS hoàn lại vé đã hoàn -> 409 TICKET_NOT_REFUNDABLE
== 2. QR: hoàn về tài khoản KHÁC -> chờ BTC duyệt -> BTC cho chi
PASS tài khoản lạ -> MANUAL_REVIEW (DESTINATION_REVIEW), chưa chi
PASS BTC thấy yêu cầu chờ duyệt
PASS BTC duyệt (RETRY = gửi lệnh chi)
PASS refund -> SUCCEEDED
PASS đơn -> REFUNDED
== 3. Thẻ: gateway không cho biết tài khoản người trả
PASS đơn thẻ -> PAID
PASS không gửi destination -> 409 PAYER_ACCOUNT_UNKNOWN (bắt khách chọn tài khoản)
PASS gửi destination -> MANUAL_REVIEW chờ BTC duyệt
PASS BTC duyệt -> SUCCEEDED
== 4. Hoàn về ngân hàng gateway KHÔNG chi tới được
PASS gateway từ chối BIDV -> refund không SUCCEEDED, có mã lỗi
   -> trạng thái: MANUAL_REVIEW / BANK_SIM_REJECTED / vé REFUND_PENDING
== 5. Sổ sách
PASS Encore ghi sổ cho 3 refund thành công
PASS gateway ghi refund dưới ĐÚNG merchant của đơn (MerNo000010), không phải binding đầu tiên (MerNo000001)
```

### Pha 2 (10/11): gateway restart, ví chi 3.750.000đ, giá vé 2.500.000đ

```
== A. Ví đủ cho MỘT vé (1,5 lần giá) mà refund có bị xếp hàng oan không
PASS đơn QR 1 vé -> PAID
   ngay sau khi gửi: AWAITING_FUNDS / INSUFFICIENT_PAYOUT_BALANCE
FAIL ví đủ tiền -> refund KHÔNG được vào AWAITING_FUNDS
FINDING refund A thành SUCCEEDED sau 58s (status cuối: SUCCEEDED)
== B. Ví còn 0,5 lần giá: refund vé thứ hai phải vào hàng chờ
PASS ví thiếu -> AWAITING_FUNDS / INSUFFICIENT_PAYOUT_BALANCE
PASS vé đang chờ hoàn -> REFUND_PENDING
PASS BTC thấy refund trong hàng chờ
== C. BTC hủy yêu cầu đang chờ ví
PASS hủy không ghi lý do -> 400 REFUND_CANCEL_NOTE_REQUIRED
PASS hủy có lý do -> FAILED / CANCELLED_BY_ORGANIZER
PASS vé về ACTIVE (khách giữ vé)
PASS đơn về REFUND_FAILED (hoàn lại được sau)
PASS hủy lần hai -> 409 REFUND_NOT_CANCELLABLE
== D. Gateway restart có quên refund không
PASS refund TradeNo000002 chi TRƯỚC restart -> gateway trả 404 REFUND_NOT_FOUND
FINDING ví chi gateway sau restart = 1250000đ: chỉ trừ refund A, mọi refund trước restart bị quên
FINDING refund cũ đó bên Encore vẫn là SUCCEEDED: Encore không hỏi lại refund đã chốt, nên không bị ảnh hưởng
```
