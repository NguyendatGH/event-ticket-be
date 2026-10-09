package com.example.demo.application.dto;

import java.util.List;

public record PayoutBankOption(String code, String name, String bankBin, List<String> paymentMethods, boolean threeDsSupported) {
}
