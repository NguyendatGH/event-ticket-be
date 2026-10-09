package com.example.demo.infrastructure.web;

import com.example.demo.application.BuyerWalletService;
import com.example.demo.application.dto.BuyerWalletResponse;
import com.example.demo.application.dto.BuyerWalletTopUpRequest;
import com.example.demo.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/wallet")
@Tag(name = "Buyer wallet", description = "Ví ảo của người mua để mô phỏng nạp tiền, mua vé và hoàn tiền")
public class BuyerWalletController {

    private final BuyerWalletService wallet;

    public BuyerWalletController(BuyerWalletService wallet) {
        this.wallet = wallet;
    }

    @GetMapping
    @Operation(summary = "Xem ví buyer và lịch sử biến động")
    public BuyerWalletResponse mine() {
        return wallet.mine(CurrentUser.require());
    }

    @PostMapping("/top-ups")
    @Operation(summary = "Nạp tiền mô phỏng vào ví buyer")
    public BuyerWalletResponse topUp(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @Valid @RequestBody BuyerWalletTopUpRequest request) {
        return wallet.topUp(CurrentUser.require(), idempotencyKey, request);
    }
}
