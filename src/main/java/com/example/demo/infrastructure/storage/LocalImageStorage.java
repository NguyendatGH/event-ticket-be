package com.example.demo.infrastructure.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public class LocalImageStorage implements ImageStorage {

    private final Path root;
    private final String publicBaseUrl;

    public LocalImageStorage(Path root, String publicBaseUrl) {
        this.root = root;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public String store(byte[] data, String folder, ImageType type) {
        String name = UUID.randomUUID() + "." + type.extension;
        try {
            Path dir = Files.createDirectories(root.resolve(folder));
            Files.write(dir.resolve(name), data);
        } catch (IOException e) {
            throw new UncheckedIOException("Không lưu được file upload", e);
        }
        return publicBaseUrl + "/uploads/" + folder + "/" + name;
    }
}
