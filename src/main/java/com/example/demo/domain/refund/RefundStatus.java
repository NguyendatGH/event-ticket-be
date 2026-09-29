package com.example.demo.domain.refund;

/** State machine: spec-plan/refund-code-plan.md mục 2. SUCCEEDED và FAILED là terminal. */
public enum RefundStatus { REQUESTED, AWAITING_FUNDS, PROCESSING, SUCCEEDED, FAILED, MANUAL_REVIEW }
