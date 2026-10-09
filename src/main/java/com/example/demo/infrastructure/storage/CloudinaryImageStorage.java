package com.example.demo.infrastructure.storage;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

public class CloudinaryImageStorage implements ImageStorage {

    private final String apiKey;
    private final String apiSecret;
    private final String cloudName;
    private final RestClient http;

    public CloudinaryImageStorage(String cloudinaryUrl) {
        URI uri = URI.create(cloudinaryUrl);
        String[] credentials = uri.getRawUserInfo().split(":", 2);
        this.apiKey = credentials[0];
        this.apiSecret = credentials[1];
        this.cloudName = uri.getHost();
        this.http = RestClient.builder().baseUrl("https://api.cloudinary.com/v1_1/" + cloudName).build();
    }

    @Override
    public String store(byte[] data, String folder, ImageType type) {
        Map<String, String> signed = Map.of("folder", folder, "timestamp", String.valueOf(Instant.now().getEpochSecond()));

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        signed.forEach(body::add);
        body.add("api_key", apiKey);
        body.add("signature", sign(signed, apiSecret));
        body.add("file", new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return UUID.randomUUID() + "." + type.extension;
            }
        });

        Map<?, ?> res = http.post().uri("/image/upload").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body).retrieve().body(Map.class);
        if (res == null || !(res.get("secure_url") instanceof String url)) {
            throw new IllegalStateException("Cloudinary không trả secure_url");
        }
        return url;
    }

    public static String sign(Map<String, String> params, String apiSecret) {
        String toSign = new TreeMap<>(params).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&")) + apiSecret;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(toSign.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
