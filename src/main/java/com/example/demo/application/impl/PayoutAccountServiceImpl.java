package com.example.demo.application.impl;

import com.example.demo.application.GatewayProvisioningService;
import com.example.demo.application.OrganizerAccess;
import com.example.demo.application.PaymentGatewayRegistry;
import com.example.demo.application.PayoutAccountService;
import com.example.demo.application.dto.AddPaymentChannelRequest;
import com.example.demo.application.dto.PaymentChannelResponse;
import com.example.demo.application.dto.PayoutAccountResponse;
import com.example.demo.application.dto.PayoutBankOption;
import com.example.demo.application.dto.SavePayoutAccountRequest;
import com.example.demo.application.dto.UpdatePaymentChannelRequest;
import com.example.demo.domain.common.DomainException;
import com.example.demo.domain.gateway.OrganizerGatewayBinding;
import com.example.demo.domain.payment.BankBins;
import com.example.demo.domain.payment.MerchantGateway;
import com.example.demo.domain.payment.OrganizerBankAccount;
import com.example.demo.infrastructure.gateway.banksim.BankSimMerchantClient;
import com.example.demo.infrastructure.gateway.banksim.BankSimMerchantClient.ChannelView;
import com.example.demo.infrastructure.gateway.banksim.BankSimMerchantClient.Channels;
import com.example.demo.infrastructure.gateway.banksim.GatewayCredentialResolver;
import com.example.demo.infrastructure.persistence.OrganizerBankAccountRepository;
import com.example.demo.infrastructure.persistence.OrganizerGatewayBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class PayoutAccountServiceImpl implements PayoutAccountService {
    private static final Logger log = LoggerFactory.getLogger(PayoutAccountServiceImpl.class);

    private final OrganizerAccess access;
    private final OrganizerBankAccountRepository accounts;
    private final OrganizerGatewayBindingRepository bindings;
    private final GatewayProvisioningService provisioning;
    private final GatewayCredentialResolver credentials;
    private final BankSimMerchantClient merchantApi;
    private final PaymentGatewayRegistry gateways;
    private final TransactionTemplate tx;
    private final MerchantGateway configuredDefault;

    public PayoutAccountServiceImpl(OrganizerAccess access, OrganizerBankAccountRepository accounts,
                                    OrganizerGatewayBindingRepository bindings, GatewayProvisioningService provisioning,
                                    GatewayCredentialResolver credentials, BankSimMerchantClient merchantApi,
                                    PaymentGatewayRegistry gateways, TransactionTemplate tx,
                                    @Value("${app.payment.default-gateway:PAYOS}") MerchantGateway configuredDefault) {
        this.access = access;
        this.accounts = accounts;
        this.bindings = bindings;
        this.provisioning = provisioning;
        this.credentials = credentials;
        this.merchantApi = merchantApi;
        this.gateways = gateways;
        this.tx = tx;
        this.configuredDefault = configuredDefault;
    }

    @Override
    public PayoutAccountResponse mine(UUID userId) {
        UUID organizerId = access.currentOrganizer(userId).getId();
        return response(organizerId, provisioning.enabled() ? loadChannels(organizerId) : null);
    }

    @Override
    public PayoutAccountResponse save(UUID userId, SavePayoutAccountRequest request) {
        UUID organizerId = access.currentOrganizer(userId).getId();
        if (provisioning.enabled())
            throw DomainException.conflict("USE_PAYMENT_CHANNELS",
                    "Cổng thanh toán này dùng kênh nhận tiền: hãy thêm hoặc sửa kênh thay vì lưu một tài khoản");
        Account account = requireAccount(request.accountName(), request.accountNumber());
        String bankBin = catalogBin(request.bankBin());
        String bankName = BankBins.nameOf(bankBin);
        tx.executeWithoutResult(s -> {
            OrganizerBankAccount current = accounts.findFirstByOrganizerIdAndIsDefaultTrue(organizerId).orElse(null);
            if (current == null) {
                accounts.saveAndFlush(OrganizerBankAccount.create(organizerId, bankName, bankBin, account.name(),
                        account.number(), true));
            } else {
                current.updateDetails(bankName, bankBin, account.name(), account.number());
                accounts.saveAndFlush(current);
            }
        });
        log.info("[PAYOUT-ACCOUNT] organizer={} lưu tài khoản nhận tiền {} ({}) {}", organizerId, bankName, bankBin,
                masked(account.number()));
        return response(organizerId, null);
    }

    @Override
    public PayoutAccountResponse addChannel(UUID userId, AddPaymentChannelRequest request) {
        UUID organizerId = access.currentOrganizer(userId).getId();
        GatewayCredentialResolver.GatewayMerchantContext ctx = requireMerchant(organizerId);
        Channels view = merchantApi.openChannel(ctx, new BankSimMerchantClient.OpenChannel(
                request.bankCode(), request.paymentMethods(), request.accountName(), request.accountNumber()));
        log.info("[PAYOUT-ACCOUNT] organizer={} mở kênh {} methods={}", organizerId, request.bankCode(), request.paymentMethods());
        forgetCachedMethods(organizerId);
        return response(organizerId, view);
    }

    @Override
    public PayoutAccountResponse updateChannel(UUID userId, UUID channelId, UpdatePaymentChannelRequest request) {
        UUID organizerId = access.currentOrganizer(userId).getId();
        GatewayCredentialResolver.GatewayMerchantContext ctx = requireMerchant(organizerId);
        Channels view = merchantApi.updateChannel(ctx, channelId.toString(), new BankSimMerchantClient.UpdateChannel(
                request.paymentMethods(), request.accountName(), request.accountNumber()));
        log.info("[PAYOUT-ACCOUNT] organizer={} sửa kênh {} methods={}", organizerId, channelId, request.paymentMethods());
        forgetCachedMethods(organizerId);
        return response(organizerId, view);
    }

    @Override
    public PayoutAccountResponse removeChannel(UUID userId, UUID channelId) {
        UUID organizerId = access.currentOrganizer(userId).getId();
        GatewayCredentialResolver.GatewayMerchantContext ctx = requireMerchant(organizerId);
        Channels view = merchantApi.removeChannel(ctx, channelId.toString());
        log.info("[PAYOUT-ACCOUNT] organizer={} xóa kênh {} (đơn cũ vẫn tra cứu và hoàn tiền được ở gateway)", organizerId, channelId);
        forgetCachedMethods(organizerId);
        return response(organizerId, view);
    }


    private GatewayCredentialResolver.GatewayMerchantContext requireMerchant(UUID organizerId) {
        if (!provisioning.enabled())
            throw DomainException.conflict("PAYMENT_CHANNELS_UNSUPPORTED", "Cổng thanh toán hiện tại không có kênh nhận tiền");
        try {
            return credentials.forOrganizer(organizerId);
        } catch (DomainException notReady) {
            throw DomainException.conflict("GATEWAY_MERCHANT_NOT_READY",
                    "Tài khoản bán hàng của bạn trên cổng thanh toán đang được khởi tạo. Thử lại sau ít phút.");
        }
    }

    private Channels loadChannels(UUID organizerId) {
        GatewayCredentialResolver.GatewayMerchantContext ctx;
        try {
            ctx = credentials.forOrganizer(organizerId);
        } catch (DomainException notReady) {
            return new Channels(List.of(), List.of(), List.of());
        }
        return merchantApi.channels(ctx);
    }

    private void forgetCachedMethods(UUID organizerId) {
        try {
            gateways.forMerchant(MerchantGateway.BANKSIM).forgetPaymentMethods(organizerId);
        } catch (RuntimeException ex) {
            log.debug("[PAYOUT-ACCOUNT] không xóa được cache phương thức: {}", ex.toString());
        }
    }


    private PayoutAccountResponse response(UUID organizerId, Channels view) {
        boolean accepting = acceptingPayments(organizerId);
        if (view == null) return accountOnlyResponse(organizerId, accepting);

        List<PayoutBankOption> banks = view.banksOrEmpty().stream()
                .sorted(Comparator.comparing(BankSimMerchantClient.BankOption::name))
                .map(b -> new PayoutBankOption(b.code(), b.name(), b.bankBin(), orEmpty(b.paymentMethods()), b.threeDsSupported()))
                .toList();
        List<PaymentChannelResponse> channels = view.channelsOrEmpty().stream()
                .map(c -> new PaymentChannelResponse(UUID.fromString(c.id()), c.bankCode(), c.bankName(), c.bankBin(),
                        orEmpty(c.paymentMethods()), orEmpty(c.routableMethods()), c.accountName(), c.accountNumberMasked(),
                        c.primary()))
                .toList();
        List<String> customerMethods = view.customerMethods() == null ? List.of() : List.copyOf(view.customerMethods());
        ChannelView primary = view.channelsOrEmpty().stream().filter(ChannelView::primary).findFirst().orElse(null);
        if (primary == null)
            return new PayoutAccountResponse(null, null, null, null, null, accepting, true, customerMethods, banks, channels);
        return new PayoutAccountResponse(primary.bankBin(), primary.bankName(), primary.accountName(),
                primary.accountNumberMasked(), primary.openedAt(), accepting, true, customerMethods, banks, channels);
    }

    private PayoutAccountResponse accountOnlyResponse(UUID organizerId, boolean accepting) {
        OrganizerBankAccount account = accounts.findFirstByOrganizerIdAndIsDefaultTrue(organizerId).orElse(null);
        if (account == null)
            return new PayoutAccountResponse(null, null, null, null, null, accepting, false, null, null, null);
        return new PayoutAccountResponse(account.getBankBin(), account.getBankName(), account.getAccountName(),
                masked(account.getAccountNumber()), account.getUpdatedAt(), accepting, true, null, null, null);
    }

    private boolean acceptingPayments(UUID organizerId) {
        if (platformGateway() != MerchantGateway.BANKSIM) return true;
        return bindings.findByOrganizerIdAndProvider(organizerId, GatewayProvisioningServiceImpl.BANKSIM)
                .map(OrganizerGatewayBinding::isUsable).orElse(false);
    }

    private MerchantGateway platformGateway() {
        return gateways.platformGateway(configuredDefault);
    }


    private record Account(String name, String number) {}

    private static Account requireAccount(String rawName, String rawNumber) {
        if (!notBlank(rawName) || !notBlank(rawNumber))
            throw DomainException.badRequest("ACCOUNT_REQUIRED", "Nhập tên chủ tài khoản và số tài khoản");
        String number = rawNumber.replaceAll("\\s+", "");
        if (!number.matches("[0-9]{6,30}"))
            throw DomainException.badRequest("INVALID_ACCOUNT_NUMBER", "Số tài khoản phải có 6–30 chữ số");
        return new Account(rawName.trim(), number);
    }

    private static String catalogBin(String rawBin) {
        if (rawBin == null || rawBin.isBlank())
            throw DomainException.badRequest("BANK_BIN_REQUIRED", "Chọn ngân hàng nhận tiền");
        String bankBin = rawBin.trim();
        if (!BankBins.exists(bankBin))
            throw DomainException.badRequest("UNKNOWN_BANK_BIN",
                    "Mã BIN không thuộc ngân hàng nào trong danh mục. Hãy chọn ngân hàng từ danh sách.");
        return bankBin;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String masked(String number) {
        return "*".repeat(Math.max(0, number.length() - 4)) + number.substring(Math.max(0, number.length() - 4));
    }
}
