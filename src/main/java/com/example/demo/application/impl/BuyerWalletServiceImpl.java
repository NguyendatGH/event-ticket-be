package com.example.demo.application.impl;

import com.example.demo.application.BuyerWalletService;
import com.example.demo.application.IdempotencyService;
import com.example.demo.application.dto.BuyerWalletResponse;
import com.example.demo.application.dto.BuyerWalletTopUpRequest;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.common.LogContext;
import com.example.demo.domain.order.Order;
import com.example.demo.domain.idempotency.IdempotencyScope;
import com.example.demo.domain.payment.PaymentProvider;
import com.example.demo.domain.refund.Refund;
import com.example.demo.domain.wallet.BuyerWallet;
import com.example.demo.domain.wallet.BuyerWalletTransaction;
import com.example.demo.domain.wallet.BuyerWalletTransactionType;
import com.example.demo.infrastructure.persistence.BuyerWalletRepository;
import com.example.demo.infrastructure.persistence.BuyerWalletTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class BuyerWalletServiceImpl implements BuyerWalletService {
    private static final Logger log = LoggerFactory.getLogger(BuyerWalletServiceImpl.class);

    private final BuyerWalletRepository wallets;
    private final BuyerWalletTransactionRepository transactions;
    private final IdempotencyService idempotency;
    private final TransactionTemplate tx;

    public BuyerWalletServiceImpl(BuyerWalletRepository wallets, BuyerWalletTransactionRepository transactions,
                                  IdempotencyService idempotency, TransactionTemplate tx) {
        this.wallets = wallets;
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.tx = tx;
    }

    @Override
    @Transactional
    public BuyerWalletResponse mine(UUID userId) {
        BuyerWallet wallet = wallets.findById(userId).orElseGet(() -> wallets.save(BuyerWallet.create(userId)));
        return BuyerWalletResponse.from(wallet, transactions.findTop30ByUserIdOrderByCreatedAtDesc(userId));
    }

    @Override
    public BuyerWalletResponse topUp(UUID userId, String idempotencyKey, BuyerWalletTopUpRequest request) {
        return idempotency.execute(IdempotencyScope.BUYER_WALLET_TOPUP, idempotencyKey,
                java.util.Map.of("userId", userId, "request", request), BuyerWalletResponse.class,
                () -> tx.execute(s -> topUpNow(userId, idempotencyKey, request)));
    }

    private BuyerWalletResponse topUpNow(UUID userId, String idempotencyKey, BuyerWalletTopUpRequest request) {
        try (LogContext.Scope ignored = LogContext.of("WALLET")) {
            BuyerWallet wallet = lockedWallet(userId);
            var existing = transactions.findByUserIdAndTypeAndIdempotencyKey(userId, BuyerWalletTransactionType.TOP_UP, idempotencyKey);
            if (existing.isPresent()) {
                BuyerWalletTransaction transaction = existing.get();
                log.info("Buyer wallet top-up replay ignored transactionId={} amount={} balanceAfter={}",
                        transaction.getId(), transaction.getAmount(), transaction.getBalanceAfter());
                return BuyerWalletResponse.from(wallet, transactions.findTop30ByUserIdOrderByCreatedAtDesc(userId));
            }
            wallet.topUp(request.amount());
            BuyerWalletTransaction transaction = transactions.save(BuyerWalletTransaction.of(userId, BuyerWalletTransactionType.TOP_UP,
                    request.amount(), wallet.getBalance(), "TOP_UP", null, blankToNull(request.note()), idempotencyKey));
            log.info("Buyer wallet top-up completed transactionId={} amount={} balanceAfter={}",
                    transaction.getId(), transaction.getAmount(), transaction.getBalanceAfter());
            return BuyerWalletResponse.from(wallet, transactions.findTop30ByUserIdOrderByCreatedAtDesc(userId));
        }
    }

    @Override
    public void charge(UUID userId, Order order) {
        try (LogContext.Scope ignored = LogContext.order("WALLET", order.getOrderCode())) {
            if (transactions.existsByTypeAndRefId(BuyerWalletTransactionType.ORDER_PAYMENT, order.getId())) {
                log.info("Buyer wallet order debit already recorded; skipping duplicate orderId={}", order.getId());
                return;
            }
            BuyerWallet wallet = lockedWallet(userId);
            if (wallet.getBalance() < order.getTotalAmount()) {
                log.warn("Buyer wallet payment rejected orderId={} required={} available={}",
                        order.getId(), order.getTotalAmount(), wallet.getBalance());
                throw DomainException.conflict("BUYER_WALLET_INSUFFICIENT_FUNDS",
                        "Ví Encore không đủ số dư để thanh toán đơn hàng");
            }
            wallet.spend(order.getTotalAmount());
            BuyerWalletTransaction transaction = transactions.save(BuyerWalletTransaction.of(userId, BuyerWalletTransactionType.ORDER_PAYMENT,
                    order.getTotalAmount(), wallet.getBalance(), "ORDER", order.getId(),
                    "Thanh toán đơn #" + order.getOrderCode()));
            log.info("Buyer wallet order debit completed transactionId={} amount={} balanceAfter={}",
                    transaction.getId(), transaction.getAmount(), transaction.getBalanceAfter());
        }
    }

    @Override
    public void recordRefund(Order order, Refund refund) {
        if (refund.getProvider() != PaymentProvider.WALLET
                || transactions.existsByTypeAndRefId(BuyerWalletTransactionType.REFUND_CREDIT, refund.getId())) return;
        try (LogContext.Scope ignored = LogContext.refund("WALLET", refund.getId())) {
            BuyerWallet wallet = lockedWallet(order.getUserId());
            wallet.creditRefund(refund.getAmount());
            BuyerWalletTransaction transaction = transactions.save(BuyerWalletTransaction.of(order.getUserId(), BuyerWalletTransactionType.REFUND_CREDIT,
                    refund.getAmount(), wallet.getBalance(), "REFUND", refund.getId(),
                    "Hoàn tiền refund " + refund.getId()));
            log.info("Buyer wallet refund credit completed transactionId={} amount={} balanceAfter={}",
                    transaction.getId(), transaction.getAmount(), transaction.getBalanceAfter());
        }
    }

    private BuyerWallet lockedWallet(UUID userId) {
        return wallets.findWithLockByUserId(userId).orElseGet(() -> wallets.saveAndFlush(BuyerWallet.create(userId)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
