package com.example.demo.infrastructure.web;

import com.example.demo.application.PayoutAccountService;
import com.example.demo.application.dto.AddPaymentChannelRequest;
import com.example.demo.application.dto.PayoutAccountResponse;
import com.example.demo.application.dto.SavePayoutAccountRequest;
import com.example.demo.application.dto.UpdatePaymentChannelRequest;
import com.example.demo.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/organizer/payout-account")
@Tag(name = "Organizer payout account", description = "Tài khoản ngân hàng BTC nhận doanh thu")
public class PayoutAccountController {
    private final PayoutAccountService service;

    public PayoutAccountController(PayoutAccountService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Tài khoản nhận tiền hiện tại của BTC")
    public PayoutAccountResponse mine() {
        return service.mine(CurrentUser.require());
    }

    @PutMapping
    @Operation(summary = "Lưu tài khoản nhận tiền (cổng không có kênh, vd PayOS)",
            description = "Cổng BankSim dùng kênh nhận tiền: gọi endpoint này trả 409 USE_PAYMENT_CHANNELS.")
    public PayoutAccountResponse save(@Valid @RequestBody SavePayoutAccountRequest request) {
        return service.save(CurrentUser.require(), request);
    }

    @PostMapping("/channels")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Thêm kênh nhận tiền", description = "Một ngân hàng của cổng + phương thức + tài khoản tại ngân hàng đó.")
    public PayoutAccountResponse addChannel(@Valid @RequestBody AddPaymentChannelRequest request) {
        return service.addChannel(CurrentUser.require(), request);
    }

    @PutMapping("/channels/{channelId}")
    @Operation(summary = "Sửa kênh nhận tiền", description = "Đổi phương thức; để trống tài khoản = giữ tài khoản cũ.")
    public PayoutAccountResponse updateChannel(@PathVariable UUID channelId, @Valid @RequestBody UpdatePaymentChannelRequest request) {
        return service.updateChannel(CurrentUser.require(), channelId, request);
    }

    @DeleteMapping("/channels/{channelId}")
    @Operation(summary = "Xóa kênh nhận tiền", description = "Không xóa được kênh cuối cùng (409 LAST_PAYMENT_CHANNEL).")
    public PayoutAccountResponse removeChannel(@PathVariable UUID channelId) {
        return service.removeChannel(CurrentUser.require(), channelId);
    }
}
