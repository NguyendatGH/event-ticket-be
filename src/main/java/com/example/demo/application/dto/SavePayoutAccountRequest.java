package com.example.demo.application.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SavePayoutAccountRequest(
        @Pattern(regexp = "^[0-9]{6}$", message = "Mã BIN ngân hàng gồm 6 chữ số") String bankBin,
        @Size(max = 120) String accountName,
        @Pattern(regexp = "^[0-9 ]{6,40}$", message = "Số tài khoản chỉ gồm chữ số") String accountNumber
) {}
