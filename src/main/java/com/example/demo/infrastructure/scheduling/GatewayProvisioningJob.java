package com.example.demo.infrastructure.scheduling;

import com.example.demo.application.GatewayProvisioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class GatewayProvisioningJob {

    private static final Logger log = LoggerFactory.getLogger(GatewayProvisioningJob.class);

    private final GatewayProvisioningService provisioning;

    public GatewayProvisioningJob(GatewayProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @Scheduled(fixedDelayString = "${app.gateway.provisioning-interval:PT60S}", initialDelayString = "PT20S")
    public void run() {
        if (!provisioning.enabled()) return;
        MDC.put("trace_id", UUID.randomUUID().toString());
        try {
            int provisioned = provisioning.provisionMissing();
            if (provisioned > 0) log.info("GatewayProvisioningJob: cấp phát {} BTC", provisioned);
        } catch (RuntimeException ex) {
            log.error("GatewayProvisioningJob lỗi: {}", ex.toString(), ex);
        } finally {
            MDC.remove("trace_id");
        }
    }
}
