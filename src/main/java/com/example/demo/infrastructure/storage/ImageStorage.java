package com.example.demo.infrastructure.storage;

public interface ImageStorage {

    String store(byte[] data, String folder, ImageType type);
}
