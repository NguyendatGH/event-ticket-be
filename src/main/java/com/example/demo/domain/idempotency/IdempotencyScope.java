package com.example.demo.domain.idempotency;

public enum IdempotencyScope {
    CHECKOUT,
    REFUND,
    BUYER_WALLET_TOPUP,
    SELLER_WALLET_TOPUP
}
