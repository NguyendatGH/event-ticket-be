package com.example.demo.application.dto;

import com.example.demo.domain.wallet.BuyerWallet;
import com.example.demo.domain.wallet.BuyerWalletTransaction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BuyerWalletResponse(
        UUID userId,
        long balance,
        long totalTopUps,
        long totalSpent,
        long totalRefunds,
        Instant updatedAt,
        List<Transaction> transactions
) {
    public static BuyerWalletResponse from(BuyerWallet wallet, List<BuyerWalletTransaction> transactions) {
        return new BuyerWalletResponse(wallet.getUserId(), wallet.getBalance(), wallet.getTotalTopUps(),
                wallet.getTotalSpent(), wallet.getTotalRefunds(), wallet.getUpdatedAt(),
                transactions.stream().map(Transaction::from).toList());
    }

    public record Transaction(UUID id, String type, long amount, long balanceAfter, String refType, UUID refId,
                              String note, Instant createdAt) {
        static Transaction from(BuyerWalletTransaction tx) {
            return new Transaction(tx.getId(), tx.getType().name(), tx.getAmount(), tx.getBalanceAfter(),
                    tx.getRefType(), tx.getRefId(), tx.getNote(), tx.getCreatedAt());
        }
    }
}
