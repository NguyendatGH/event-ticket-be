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
import com.example.demo.domain.payment.PaymentChannel;
import com.example.demo.infrastructure.gateway.banksim.BankSimAdminClient;
import com.example.demo.infrastructure.gateway.banksim.BankSimAdminClient.SelectableBank;
import com.example.demo.infrastructure.gateway.banksim.BankSimAdminClient.SettlementAccount;
import com.example.demo.infrastructure.gateway.banksim.BankSimAdminClient.TerminalSetup;
import com.example.demo.infrastructure.persistence.OrganizerBankAccountRepository;
import com.example.demo.infrastructure.persistence.OrganizerGatewayBindingRepository;
import com.example.demo.infrastructure.persistence.PaymentChannelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PayoutAccountServiceImpl implements PayoutAccountService {
    private static final Logger log = LoggerFactory.getLogger(PayoutAccountServiceImpl.class);

    private final OrganizerAccess access;
    private final OrganizerBankAccountRepository accounts;
    private final OrganizerGatewayBindingRepository bindings;
    private final PaymentChannelRepository channels;
    private final GatewayProvisioningService provisioning;
    private final PaymentGatewayRegistry gateways;
    private final BankSimAdminClient admin;
    private final TransactionTemplate tx;
    private final MerchantGateway configuredDefault;

    public PayoutAccountServiceImpl(OrganizerAccess access, OrganizerBankAccountRepository accounts,
                                    OrganizerGatewayBindingRepository bindings, PaymentChannelRepository channels,
                                    GatewayProvisioningService provisioning, PaymentGatewayRegistry gateways,
                                    BankSimAdminClient admin, TransactionTemplate tx,
                                    @Value("${app.payment.default-gateway:PAYOS}") MerchantGateway configuredDefault) {
        this.access = access;
        this.accounts = accounts;
        this.bindings = bindings;
        this.channels = channels;
        this.provisioning = provisioning;
        this.gateways = gateways;
        this.admin = admin;
        this.tx = tx;
        this.configuredDefault = configuredDefault;
    }

    @Override
    public PayoutAccountResponse mine(UUID userId) {
        UUID organizerId = access.currentOrganizer(userId).getId();
        return response(organizerId, provisioning.enabled() ? load(organizerId) : null);
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
        saveDefaultAccount(organizerId, bankName, bankBin, account);
        log.info("[PAYOUT-ACCOUNT] organizer={} lưu tài khoản nhận tiền {} ({}) {}", organizerId, bankName, bankBin,
                masked(account.number()));
        return response(organizerId, null);
    }

    @Override
    public PayoutAccountResponse addChannel(UUID userId, AddPaymentChannelRequest request) {
        UUID organizerId = access.currentOrganizer(userId).getId();
        Loaded loaded = requireChannels(organizerId);
        Account account = requireAccount(request.accountName(), request.accountNumber());
        List<String> methods = methods(request.paymentMethods());
        SelectableBank bank = loaded.bank(request.bankCode())
                .orElseThrow(() -> DomainException.badRequest("UNKNOWN_BANK", "Ngân hàng này không có trong danh sách của cổng thanh toán"));
        requireSupported(bank, methods);
        if (loaded.active().stream().anyMatch(c -> c.getBankCode().equals(bank.code())))
            throw DomainException.conflict("BANK_ALREADY_A_CHANNEL",
                    bank.name() + " đã là một kênh nhận tiền của bạn. Sửa kênh đó thay vì thêm mới.");
        requireMethodsFree(methods, loaded, null);
        OrganizerGatewayBinding binding = loaded.requireBinding();
        SettlementAccount settlement = new SettlementAccount(bank.bankBin(), account.number(), account.name());

        PaymentChannel reopened = channels.findByOrganizerIdAndBankCode(organizerId, bank.code()).orElse(null);
        String terminalId;
        if (reopened != null) {
            terminalId = reopened.getGatewayTerminalId();
            admin.configureChannel(terminalId, bank.code(), methods, threeDsFor(loaded, terminalId, methods), settlement);
        } else if (loaded.active().isEmpty() && !channels.existsByGatewayTerminalId(binding.getGatewayTerminalId())) {
            terminalId = binding.getGatewayTerminalId();
            admin.configureChannel(terminalId, bank.code(), methods, threeDsFor(loaded, terminalId, methods), settlement);
        } else {
            terminalId = admin.openChannel(binding.getGatewayMerchantNo(), "Kênh " + bank.name(), bank.code(), methods,
                    methods.contains("CARD") ? "OPTIONAL" : null, settlement).terminalId();
        }
        tx.executeWithoutResult(s -> {
            if (reopened != null) {
                PaymentChannel row = channels.findById(reopened.getId()).orElseThrow();
                row.reopen(account.name(), account.number());
                channels.saveAndFlush(row);
            } else {
                channels.saveAndFlush(PaymentChannel.open(organizerId, terminalId, bank.code(), bank.bankBin(), bank.name(),
                        account.name(), account.number()));
            }
        });
        log.info("[PAYOUT-ACCOUNT] organizer={} mở kênh {} terminal={} methods={} tài khoản {}{}", organizerId, bank.code(),
                terminalId, methods, masked(account.number()), reopened != null ? " (mở lại kênh cũ)" : "");
        afterChannelsChanged(organizerId);
        return mine(userId);
    }

    @Override
    public PayoutAccountResponse updateChannel(UUID userId, UUID channelId, UpdatePaymentChannelRequest request) {
        UUID organizerId = access.currentOrganizer(userId).getId();
        Loaded loaded = requireChannels(organizerId);
        PaymentChannel channel = loaded.activeChannel(channelId);
        List<String> methods = methods(request.paymentMethods());
        SelectableBank bank = loaded.bank(channel.getBankCode())
                .orElseThrow(() -> DomainException.conflict("BANK_NO_LONGER_AVAILABLE",
                        channel.getBankName() + " không còn trên cổng thanh toán. Thêm kênh ở ngân hàng khác rồi xóa kênh này."));
        requireSupported(bank, methods);
        requireMethodsFree(methods, loaded, channel.getId());
        boolean typed = notBlank(request.accountName()) || notBlank(request.accountNumber());
        Account account = typed ? requireAccount(request.accountName(), request.accountNumber()) : null;
        SettlementAccount settlement = account == null ? null
                : new SettlementAccount(channel.getBankBin(), account.number(), account.name());
        String terminalId = channel.getGatewayTerminalId();
        admin.configureChannel(terminalId, bank.code(), methods, threeDsFor(loaded, terminalId, methods), settlement);
        if (account != null) {
            tx.executeWithoutResult(s -> {
                PaymentChannel row = channels.findById(channel.getId()).orElseThrow();
                row.changeAccount(account.name(), account.number());
                channels.saveAndFlush(row);
            });
        }
        log.info("[PAYOUT-ACCOUNT] organizer={} sửa kênh {} terminal={} methods={} {}", organizerId, bank.code(), terminalId,
                methods, account == null ? "giữ tài khoản" : "tài khoản mới " + masked(account.number()));
        afterChannelsChanged(organizerId);
        return mine(userId);
    }

    @Override
    public PayoutAccountResponse removeChannel(UUID userId, UUID channelId) {
        UUID organizerId = access.currentOrganizer(userId).getId();
        Loaded loaded = requireChannels(organizerId);
        PaymentChannel channel = loaded.activeChannel(channelId);
        if (loaded.active().size() == 1)
            throw DomainException.conflict("LAST_PAYMENT_CHANNEL",
                    "Phải còn ít nhất một kênh nhận tiền. Thêm kênh khác trước rồi mới xóa kênh này.");
        tx.executeWithoutResult(s -> {
            PaymentChannel row = channels.findById(channel.getId()).orElseThrow();
            row.remove();
            channels.saveAndFlush(row);
        });
        log.info("[PAYOUT-ACCOUNT] organizer={} xóa kênh {} (terminal {} giữ nguyên cho đơn cũ)", organizerId,
                channel.getBankCode(), channel.getGatewayTerminalId());
        afterChannelsChanged(organizerId);
        return mine(userId);
    }


    private record Loaded(OrganizerGatewayBinding binding, List<SelectableBank> banks, Map<String, TerminalSetup> terminals,
                          List<PaymentChannel> active) {

        Optional<SelectableBank> bank(String code) {
            String wanted = code == null ? "" : code.trim();
            return banks.stream().filter(b -> b.code().equals(wanted)).findFirst();
        }

        PaymentChannel activeChannel(UUID id) {
            return active.stream().filter(c -> c.getId().equals(id)).findFirst()
                    .orElseThrow(() -> DomainException.notFound("PAYMENT_CHANNEL_NOT_FOUND", "Không tìm thấy kênh nhận tiền này"));
        }

        OrganizerGatewayBinding requireBinding() {
            if (binding == null)
                throw DomainException.conflict("GATEWAY_MERCHANT_NOT_READY",
                        "Tài khoản bán hàng của bạn trên cổng thanh toán đang được khởi tạo. Thử lại sau ít phút.");
            return binding;
        }

        List<String> configured(String terminalId) {
            TerminalSetup t = terminals.get(terminalId);
            return t == null || t.paymentMethods() == null ? List.of() : t.paymentMethods();
        }

        List<String> routable(String terminalId) {
            TerminalSetup t = terminals.get(terminalId);
            return t == null || t.routableMethods() == null ? List.of() : t.routableMethods();
        }
    }

    private Loaded requireChannels(UUID organizerId) {
        if (!provisioning.enabled())
            throw DomainException.conflict("PAYMENT_CHANNELS_UNSUPPORTED", "Cổng thanh toán hiện tại không có kênh nhận tiền");
        return load(organizerId);
    }

    private Loaded load(UUID organizerId) {
        OrganizerGatewayBinding binding = bindings.findByOrganizerIdAndProvider(organizerId, GatewayProvisioningServiceImpl.BANKSIM)
                .filter(OrganizerGatewayBinding::isUsable).orElse(null);
        List<SelectableBank> banks = admin.selectableBanks();
        Map<String, TerminalSetup> terminals = binding == null ? Map.of()
                : admin.terminalSetups(binding.getGatewayMerchantNo()).stream()
                .collect(Collectors.toMap(TerminalSetup::terminalId, Function.identity(), (a, b) -> a));
        if (binding != null) adoptSingleBankChoice(organizerId, binding, terminals, banks);
        return new Loaded(binding, banks, terminals,
                channels.findByOrganizerIdAndStatusOrderByOpenedAtAsc(organizerId, PaymentChannel.Status.ACTIVE));
    }

    private void adoptSingleBankChoice(UUID organizerId, OrganizerGatewayBinding binding, Map<String, TerminalSetup> terminals,
                                       List<SelectableBank> banks) {
        if (channels.existsByOrganizerId(organizerId)) return;
        TerminalSetup terminal = terminals.get(binding.getGatewayTerminalId());
        if (terminal == null || terminal.acquirerCode() == null) return;
        SelectableBank bank = banks.stream().filter(b -> b.code().equals(terminal.acquirerCode())).findFirst().orElse(null);
        OrganizerBankAccount account = accounts.findFirstByOrganizerIdAndIsDefaultTrue(organizerId).orElse(null);
        if (bank == null || account == null || !bank.bankBin().equals(account.getBankBin())) return;
        tx.executeWithoutResult(s -> channels.saveAndFlush(PaymentChannel.open(organizerId, terminal.terminalId(), bank.code(),
                bank.bankBin(), bank.name(), account.getAccountName(), account.getAccountNumber())));
        log.info("[PAYOUT-ACCOUNT] organizer={} ghi nhận lựa chọn ngân hàng cũ ({} trên terminal {}) thành kênh đầu tiên",
                organizerId, bank.code(), terminal.terminalId());
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

    private static List<String> methods(List<String> raw) {
        List<String> methods = raw == null ? List.of() : raw.stream()
                .filter(Objects::nonNull).map(m -> m.trim().toUpperCase(Locale.ROOT)).filter(m -> !m.isEmpty()).distinct().toList();
        if (methods.isEmpty())
            throw DomainException.badRequest("PAYMENT_METHODS_REQUIRED", "Chọn ít nhất một phương thức thanh toán");
        return methods;
    }

    private static void requireSupported(SelectableBank bank, List<String> methods) {
        List<String> unsupported = methods.stream().filter(m -> !bank.paymentMethods().contains(m)).toList();
        if (!unsupported.isEmpty())
            throw DomainException.badRequest("PAYMENT_METHOD_NOT_SUPPORTED_BY_BANK",
                    bank.name() + " không hỗ trợ " + String.join(", ", unsupported));
    }

    private static void requireMethodsFree(List<String> methods, Loaded loaded, UUID exceptChannel) {
        for (PaymentChannel other : loaded.active()) {
            if (other.getId().equals(exceptChannel)) continue;
            List<String> taken = methods.stream().filter(loaded.configured(other.getGatewayTerminalId())::contains).toList();
            if (!taken.isEmpty())
                throw DomainException.conflict("PAYMENT_METHOD_IN_OTHER_CHANNEL",
                        String.join(", ", taken) + " đang nhận qua kênh " + other.getBankName()
                                + ". Bỏ phương thức đó ở kênh kia trước.");
        }
    }

    private static String threeDsFor(Loaded loaded, String terminalId, List<String> methods) {
        if (!methods.contains("CARD")) return null;
        TerminalSetup current = loaded.terminals().get(terminalId);
        return current == null || current.threeDsPolicy() == null ? "OPTIONAL" : current.threeDsPolicy();
    }


    private void afterChannelsChanged(UUID organizerId) {
        channels.findByOrganizerIdAndStatusOrderByOpenedAtAsc(organizerId, PaymentChannel.Status.ACTIVE).stream()
                .findFirst().ifPresent(primary -> mirrorPrimary(organizerId, primary));
        forgetCachedMethods(organizerId);
    }

    private void mirrorPrimary(UUID organizerId, PaymentChannel primary) {
        OrganizerBankAccount current = accounts.findFirstByOrganizerIdAndIsDefaultTrue(organizerId).orElse(null);
        boolean same = current != null && current.getGatewaySyncedAt() != null
                && primary.getBankBin().equals(current.getBankBin())
                && primary.getAccountNumber().equals(current.getAccountNumber())
                && primary.getAccountName().equals(current.getAccountName());
        if (same) return;
        Account account = new Account(primary.getAccountName(), primary.getAccountNumber());
        saveDefaultAccount(organizerId, primary.getBankName(), primary.getBankBin(), account);
        try {
            provisioning.syncSettlement(organizerId, new SettlementAccount(primary.getBankBin(), account.number(), account.name()));
        } catch (RuntimeException ex) {
            log.warn("[PAYOUT-ACCOUNT] organizer={} chưa đẩy được tài khoản kênh chính lên gateway ({}) -> job sẽ thử lại",
                    organizerId, ex.toString());
        }
    }

    private void saveDefaultAccount(UUID organizerId, String bankName, String bankBin, Account account) {
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
    }

    private void forgetCachedMethods(UUID organizerId) {
        try {
            gateways.forMerchant(MerchantGateway.BANKSIM).forgetPaymentMethods(organizerId);
        } catch (RuntimeException ex) {
            log.debug("[PAYOUT-ACCOUNT] không xóa được cache phương thức: {}", ex.toString());
        }
    }


    private PayoutAccountResponse response(UUID organizerId, Loaded loaded) {
        boolean accepting = acceptingPayments(organizerId);
        OrganizerBankAccount account = accounts.findFirstByOrganizerIdAndIsDefaultTrue(organizerId).orElse(null);
        List<PayoutBankOption> banks = null;
        List<PaymentChannelResponse> channelViews = null;
        List<String> customerMethods = null;
        if (loaded != null) {
            banks = loaded.banks().stream()
                    .map(b -> new PayoutBankOption(b.code(), b.name(), b.bankBin(), b.paymentMethods(), b.threeDsSupported()))
                    .toList();
            List<PaymentChannel> active = loaded.active();
            channelViews = new ArrayList<>();
            for (int i = 0; i < active.size(); i++) {
                PaymentChannel c = active.get(i);
                channelViews.add(new PaymentChannelResponse(c.getId(), c.getBankCode(), c.getBankName(), c.getBankBin(),
                        loaded.configured(c.getGatewayTerminalId()), loaded.routable(c.getGatewayTerminalId()),
                        c.getAccountName(), masked(c.getAccountNumber()), i == 0));
            }
            Set<String> union = new LinkedHashSet<>();
            if (!active.isEmpty()) active.forEach(c -> union.addAll(loaded.routable(c.getGatewayTerminalId())));
            else if (loaded.binding() != null) union.addAll(loaded.routable(loaded.binding().getGatewayTerminalId()));
            customerMethods = List.copyOf(union);
        }
        if (account == null)
            return new PayoutAccountResponse(null, null, null, null, null, accepting, false, customerMethods, banks, channelViews);
        boolean synced = platformGateway() != MerchantGateway.BANKSIM || account.getGatewaySyncedAt() != null;
        return new PayoutAccountResponse(account.getBankBin(), account.getBankName(), account.getAccountName(),
                masked(account.getAccountNumber()), account.getUpdatedAt(), accepting, synced, customerMethods, banks, channelViews);
    }

    private boolean acceptingPayments(UUID organizerId) {
        if (platformGateway() != MerchantGateway.BANKSIM) return true;
        return bindings.findByOrganizerIdAndProvider(organizerId, GatewayProvisioningServiceImpl.BANKSIM)
                .map(OrganizerGatewayBinding::isUsable).orElse(false);
    }

    private MerchantGateway platformGateway() {
        return gateways.platformGateway(configuredDefault);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String masked(String number) {
        return "*".repeat(Math.max(0, number.length() - 4)) + number.substring(Math.max(0, number.length() - 4));
    }
}
