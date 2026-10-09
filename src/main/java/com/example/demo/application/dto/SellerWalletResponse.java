package com.example.demo.application.dto;

import com.example.demo.domain.wallet.SellerWallet;
import com.example.demo.domain.wallet.SellerWalletTransaction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SellerWalletResponse(
        UUID organizerId,
        long balance,
        long totalTopUps,
        long totalSales,
        long totalRefunds,
        Instant updatedAt,
        List<Transaction> transactions
) {
    public static SellerWalletResponse from(SellerWallet wallet, List<SellerWalletTransaction> transactions) {
        return new SellerWalletResponse(wallet.getOrganizerId(), wallet.getBalance(), wallet.getTotalTopUps(),
                wallet.getTotalSales(), wallet.getTotalRefunds(), wallet.getUpdatedAt(),
                transactions.stream().map(Transaction::from).toList());
    }

    public record Transaction(UUID id, String type, long amount, long balanceAfter, String refType, UUID refId,
                              String note, Instant createdAt) {
        static Transaction from(SellerWalletTransaction tx) {
            return new Transaction(tx.getId(), tx.getType().name(), tx.getAmount(), tx.getBalanceAfter(),
                    tx.getRefType(), tx.getRefId(), tx.getNote(), tx.getCreatedAt());
        }
    }
}
