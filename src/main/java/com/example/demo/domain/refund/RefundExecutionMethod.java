package com.example.demo.domain.refund;

/** Nói thật refund chạy bằng gì. PayOS và mock đều là PAYOUT (một lệnh chi mới, không đảo giao dịch gốc). */
public enum RefundExecutionMethod { PAYOUT, NATIVE_REFUND, MANUAL_TRANSFER }
