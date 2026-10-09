package com.example.demo.application.impl;

import com.example.demo.application.OrganizerAccess;
import com.example.demo.application.IdempotencyService;
import com.example.demo.application.SellerWalletService;
import com.example.demo.application.dto.SellerWalletResponse;
import com.example.demo.application.dto.SellerWalletTopUpRequest;
import com.example.demo.domain.event.Event;
import com.example.demo.domain.idempotency.IdempotencyScope;
import com.example.demo.domain.order.Order;
import com.example.demo.domain.refund.Refund;
import com.example.demo.domain.wallet.SellerWallet;
import com.example.demo.domain.wallet.SellerWalletTransaction;
import com.example.demo.domain.wallet.SellerWalletTransactionType;
import com.example.demo.infrastructure.persistence.EventRepository;
import com.example.demo.infrastructure.persistence.SellerWalletRepository;
import com.example.demo.infrastructure.persistence.SellerWalletTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class SellerWalletServiceImpl implements SellerWalletService {

    private final OrganizerAccess organizerAccess;
    private final EventRepository events;
    private final SellerWalletRepository wallets;
    private final SellerWalletTransactionRepository transactions;
    private final IdempotencyService idempotency;
    private final TransactionTemplate tx;

    public SellerWalletServiceImpl(OrganizerAccess organizerAccess, EventRepository events,
                                   SellerWalletRepository wallets, SellerWalletTransactionRepository transactions,
                                   IdempotencyService idempotency, TransactionTemplate tx) {
        this.organizerAccess = organizerAccess;
        this.events = events;
        this.wallets = wallets;
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.tx = tx;
    }

    @Override
    @Transactional
    public SellerWalletResponse mine(UUID userId) {
        UUID organizerId = organizerAccess.currentOrganizer(userId).getId();
        SellerWallet wallet = wallets.findById(organizerId)
                .orElseGet(() -> wallets.save(SellerWallet.create(organizerId)));
        return SellerWalletResponse.from(wallet,
                transactions.findTop30ByOrganizerIdOrderByCreatedAtDesc(organizerId));
    }

    @Override
    public SellerWalletResponse topUp(UUID userId, String idempotencyKey, SellerWalletTopUpRequest request) {
        return idempotency.execute(IdempotencyScope.SELLER_WALLET_TOPUP, idempotencyKey,
                java.util.Map.of("userId", userId, "request", request), SellerWalletResponse.class,
                () -> tx.execute(s -> topUpNow(userId, idempotencyKey, request)));
    }

    private SellerWalletResponse topUpNow(UUID userId, String idempotencyKey, SellerWalletTopUpRequest request) {
        UUID organizerId = organizerAccess.currentOrganizer(userId).getId();
        SellerWallet wallet = lockedWallet(organizerId);
        if (transactions.findByOrganizerIdAndTypeAndIdempotencyKey(organizerId, SellerWalletTransactionType.TOP_UP, idempotencyKey).isPresent()) {
            return SellerWalletResponse.from(wallet, transactions.findTop30ByOrganizerIdOrderByCreatedAtDesc(organizerId));
        }
        wallet.topUp(request.amount());
        transactions.save(SellerWalletTransaction.of(organizerId, SellerWalletTransactionType.TOP_UP,
                request.amount(), wallet.getBalance(), "TOP_UP", null,
                blankToNull(request.note()), idempotencyKey));
        return SellerWalletResponse.from(wallet,
                transactions.findTop30ByOrganizerIdOrderByCreatedAtDesc(organizerId));
    }

    @Override
    public void recordPayment(Order order) {
        Event event = events.findById(order.getEventId()).orElse(null);
        if (event == null || event.getOrganizerId() == null) return;
        long net = order.getPaidAmount() - Math.min(order.getFeeAmount(), order.getPaidAmount());
        apply(event.getOrganizerId(), SellerWalletTransactionType.PAYMENT_EARNED, net,
                "ORDER", order.getId(), "Doanh thu đơn #" + order.getOrderCode());
    }

    @Override
    public void recordRefund(Order order, Refund refund) {
        Event event = events.findById(order.getEventId()).orElse(null);
        if (event == null || event.getOrganizerId() == null) return;
        apply(event.getOrganizerId(), SellerWalletTransactionType.REFUND_DEBIT, refund.getAmount(),
                "REFUND", refund.getId(), "Hoàn tiền refund " + refund.getId());
    }

    private void apply(UUID organizerId, SellerWalletTransactionType type, long amount,
                       String refType, UUID refId, String note) {
        if (amount <= 0 || transactions.existsByTypeAndRefId(type, refId)) return;
        SellerWallet wallet = lockedWallet(organizerId);
        if (type == SellerWalletTransactionType.REFUND_DEBIT) wallet.debitRefund(amount);
        else wallet.creditSale(amount);
        transactions.save(SellerWalletTransaction.of(organizerId, type, amount, wallet.getBalance(), refType, refId, note));
    }

    private SellerWallet lockedWallet(UUID organizerId) {
        return wallets.findWithLockByOrganizerId(organizerId).orElseGet(() -> wallets.saveAndFlush(SellerWallet.create(organizerId)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
