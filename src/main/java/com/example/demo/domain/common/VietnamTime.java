package com.example.demo.domain.common;

import java.time.ZoneId;

public final class VietnamTime {
    public static final String ZONE_ID="Asia/Ho_Chi_Minh";
    public static final ZoneId ZONE = ZoneId.of(ZONE_ID);

    private VietnamTime() {}
}
