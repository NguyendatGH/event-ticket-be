package com.example.demo.domain.refund;

/** SUCCEEDED và FAILED là terminal. */
public enum RefundStatus { REQUESTED, AWAITING_FUNDS, PROCESSING, SUCCEEDED, FAILED, MANUAL_REVIEW }
