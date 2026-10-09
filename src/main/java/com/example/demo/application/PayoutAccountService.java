package com.example.demo.application;

import com.example.demo.application.dto.AddPaymentChannelRequest;
import com.example.demo.application.dto.PayoutAccountResponse;
import com.example.demo.application.dto.SavePayoutAccountRequest;
import com.example.demo.application.dto.UpdatePaymentChannelRequest;

import java.util.UUID;

public interface PayoutAccountService {
    PayoutAccountResponse mine(UUID userId);

    PayoutAccountResponse save(UUID userId, SavePayoutAccountRequest request);

    PayoutAccountResponse addChannel(UUID userId, AddPaymentChannelRequest request);

    PayoutAccountResponse updateChannel(UUID userId, UUID channelId, UpdatePaymentChannelRequest request);

    PayoutAccountResponse removeChannel(UUID userId, UUID channelId);
}
