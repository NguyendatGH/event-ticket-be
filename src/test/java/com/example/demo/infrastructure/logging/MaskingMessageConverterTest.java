package com.example.demo.infrastructure.logging;

import org.junit.jupiter.api.Test;

import static com.example.demo.infrastructure.logging.MaskingMessageConverter.mask;
import static org.assertj.core.api.Assertions.assertThat;

class MaskingMessageConverterTest {

    @Test
    void masksSensitiveValues() {
        assertThat(mask("payer 0123456789012 paid")).isEqualTo("payer *********9012 paid");
        assertThat(mask("account=1234567890")).isEqualTo("account=******7890");
        assertThat(mask("order 48214 qty 12345")).as("dưới 10 chữ số giữ nguyên").isEqualTo("order 48214 qty 12345");
        assertThat(mask("user nguyenvana@example.com login")).isEqualTo("user ng***@example.com login");
        assertThat(mask("{\"apiKey\": \"abc-123\", \"password\":\"p@ss\"}")).isEqualTo("{\"apiKey\": \"***\", \"password\":\"***\"}");
        assertThat(mask("PAYOS_CHECKSUM_KEY=deadbeef client_id=42")).isEqualTo("PAYOS_CHECKSUM_KEY=*** client_id=***");
        assertThat(mask("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.abc.def")).isEqualTo("Authorization: Bearer ***");
        assertThat(mask("Started EventApplication in 3.5 seconds")).isEqualTo("Started EventApplication in 3.5 seconds");
        assertThat(mask(null)).isNull();
    }
}
