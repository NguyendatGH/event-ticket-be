package com.example.demo.domain.payment;

import java.util.Map;

public record RefundCommand(String referenceId, long amount, String description, String toBin, String toAccountNumber) {

    public Map<String, Object> masked() {
        String acc = toAccountNumber == null ? null
                : "*".repeat(Math.max(0, toAccountNumber.length() - 4))
                        + toAccountNumber.substring(Math.max(0, toAccountNumber.length() - 4));
        return Map.of("referenceId", referenceId, "amount", amount, "description", String.valueOf(description),
                "toBin", String.valueOf(toBin), "toAccountNumber", String.valueOf(acc));
    }
}
