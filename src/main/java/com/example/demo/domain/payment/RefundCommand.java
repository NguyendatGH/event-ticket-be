package com.example.demo.domain.payment;

import java.util.Map;

/**
 * Lệnh hoàn tiền gửi provider. {@code referenceId} = key idempotency của lần thử (RF-&lt;refundId&gt;-&lt;attempt&gt;);
 * provider phải coi hai lệnh cùng referenceId là MỘT lệnh, đó là thứ chặn chi tiền hai lần.
 */
public record RefundCommand(String referenceId, long amount, String description, String toBin, String toAccountNumber) {

    /** Bản ghi audit: che số tài khoản, chỉ giữ 4 số cuối. */
    public Map<String, Object> masked() {
        String acc = toAccountNumber == null ? null
                : "*".repeat(Math.max(0, toAccountNumber.length() - 4))
                        + toAccountNumber.substring(Math.max(0, toAccountNumber.length() - 4));
        return Map.of("referenceId", referenceId, "amount", amount, "description", String.valueOf(description),
                "toBin", String.valueOf(toBin), "toAccountNumber", String.valueOf(acc));
    }
}
