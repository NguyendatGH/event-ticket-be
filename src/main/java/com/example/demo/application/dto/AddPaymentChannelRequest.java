package com.example.demo.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record AddPaymentChannelRequest(
        @NotBlank @Size(max = 64) String bankCode,
        @NotEmpty List<@NotBlank String> paymentMethods,
        @NotBlank @Size(max = 120) String accountName,
        @NotBlank @Pattern(regexp = "^[0-9 ]{6,40}$", message = "Số tài khoản chỉ gồm chữ số") String accountNumber
) {}
