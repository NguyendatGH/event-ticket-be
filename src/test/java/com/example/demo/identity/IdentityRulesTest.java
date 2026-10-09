package com.example.demo.identity;

import com.example.demo.domain.organizer.Organizer;
import com.example.demo.infrastructure.security.OpaqueTokens;
import com.example.demo.infrastructure.storage.CloudinaryImageStorage;
import com.example.demo.infrastructure.storage.ImageType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class IdentityRulesTest {

    @Test
    void slugMatchesSeedRule() {
        assertEquals("sunrise-live", Organizer.slugify("Sunrise Live"));
        assertEquals("saigon-jazz-club", Organizer.slugify("Saigon Jazz Club"));
        assertEquals("dem-nhac-ha-noi", Organizer.slugify("  Đêm nhạc Hà Nội!! "));
        assertEquals("cong-ty-tnhh-duong-pho-2026", Organizer.slugify("Công ty TNHH Đường Phố (2026)"));
        assertEquals("organizer", Organizer.slugify("!!!"));
        assertTrue(Organizer.slugify("a".repeat(300)).length() <= 180);
    }

    @Test
    void detectsImagesByMagicBytes() {
        assertEquals(Optional.of(ImageType.JPEG), ImageType.detect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}));
        assertEquals(Optional.of(ImageType.PNG), ImageType.detect(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0}));
        assertEquals(Optional.of(ImageType.GIF), ImageType.detect("GIF89a....".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(Optional.of(ImageType.WEBP), ImageType.detect("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(Optional.empty(), ImageType.detect("RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(Optional.empty(), ImageType.detect("<svg xmlns=...>".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Optional.empty(), ImageType.detect(new byte[0]));
    }

    @Test
    void cloudinarySignatureMatchesDocsExample() {
        assertEquals("bfd09f95f331f558cbd1320e67aa8d488770583e", CloudinaryImageStorage.sign(Map.of(
                "timestamp", "1315060510",
                "public_id", "sample_image",
                "eager", "w_400,h_300,c_pad|w_260,h_200,c_crop"), "abcd"));
    }

    @Test
    void opaqueTokensAre32BytesBase64UrlAndHashedAsSha256Hex() {
        String t = OpaqueTokens.generate();
        assertTrue(t.matches("[A-Za-z0-9_-]{43}"), t);
        assertNotEquals(t, OpaqueTokens.generate());
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", OpaqueTokens.sha256Hex("abc"));
    }
}
