package com.example.demo.domain.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VietQrTest {

    @Test
    void buildsEmvcoPayloadEndingWithCrc() {
        String qr = VietQr.build("970436", "1234567890", 200000, "HOAN VE 123");

        assertThat(qr).startsWith("000201")
                .contains("0006970436").contains("A000000727")
                .contains("QRIBFTTA").contains("5303704")
                .contains("5406200000")
                .contains("5802VN");
        assertThat(qr).endsWith(VietQr.crc16(qr.substring(0, qr.length() - 4)));
    }

    @Test
    void omitsAmountWhenNotPositive() {
        assertThat(VietQr.build("970436", "1234567890", 0, "X")).doesNotContain("5406");
    }

    @Test
    void sanitizeContentStripsDiacriticsAndCaps() {
        assertThat(VietQr.sanitizeContent("Hoàn vé #123")).isEqualTo("Ho n v 123");
        assertThat(VietQr.sanitizeContent("A".repeat(40))).hasSize(VietQr.MAX_CONTENT);
        assertThat(VietQr.sanitizeContent(null)).isEmpty();
    }

    @Test
    void needsBinAndAccount() {
        assertThatThrownBy(() -> VietQr.build("", "123", 1000, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VietQr.build("970436", " ", 1000, "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
