package com.example.demo.infrastructure.storage;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

public enum ImageType {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    GIF("image/gif", "gif");

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    public final String contentType;
    public final String extension;

    ImageType(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public static Optional<ImageType> detect(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return Optional.of(JPEG);
        if (b.length >= 8 && Arrays.equals(b, 0, 8, PNG_SIGNATURE, 0, 8)) return Optional.of(PNG);
        if (b.length >= 6 && (ascii(b, 0, 6).equals("GIF87a") || ascii(b, 0, 6).equals("GIF89a"))) return Optional.of(GIF);
        if (b.length >= 12 && ascii(b, 0, 4).equals("RIFF") && ascii(b, 8, 4).equals("WEBP")) return Optional.of(WEBP);
        return Optional.empty();
    }

    private static String ascii(byte[] b, int offset, int length) {
        return new String(b, offset, length, StandardCharsets.ISO_8859_1);
    }
}
