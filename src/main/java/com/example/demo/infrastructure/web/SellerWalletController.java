package com.example.demo.infrastructure.web;

import com.example.demo.application.SellerWalletService;
import com.example.demo.application.dto.SellerWalletResponse;
import com.example.demo.application.dto.SellerWalletTopUpRequest;
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
@RequestMapping("/api/v1/organizer/wallet")
@Tag(name = "Organizer seller wallet", description = "Ví ảo của ban tổ chức để mô phỏng nạp tiền, doanh thu và refund")
public class SellerWalletController {

    private final SellerWalletService wallet;

    public SellerWalletController(SellerWalletService wallet) {
        this.wallet = wallet;
    }

    @GetMapping
    @Operation(summary = "Xem ví seller và lịch sử biến động")
    public SellerWalletResponse mine() {
        return wallet.mine(CurrentUser.require());
    }

    @PostMapping("/top-ups")
    @Operation(summary = "Nạp tiền mô phỏng vào ví seller")
    public SellerWalletResponse topUp(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                      @Valid @RequestBody SellerWalletTopUpRequest request) {
        return wallet.topUp(CurrentUser.require(), idempotencyKey, request);
    }
}
