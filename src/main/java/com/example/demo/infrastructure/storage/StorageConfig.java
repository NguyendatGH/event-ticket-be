package com.example.demo.infrastructure.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.nio.file.Path;

@Configuration
public class StorageConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    private final Path localDir;

    public StorageConfig(@Value("${app.storage.local-dir:uploads}") String localDir) {
        this.localDir = Path.of(localDir).toAbsolutePath().normalize();
    }

    @Bean
    ImageStorage imageStorage(@Value("${app.storage.cloudinary-url:}") String cloudinaryUrl,
                              @Value("${app.storage.cloudinary-cloud-name:}") String cloudName,
                              @Value("${app.storage.cloudinary-api-key:}") String apiKey,
                              @Value("${app.storage.cloudinary-api-secret:}") String apiSecret,
                              @Value("${app.storage.public-base-url:}") String publicBaseUrl) {
        String url = cloudinaryUrl.isBlank() ? composeCloudinaryUrl(cloudName, apiKey, apiSecret) : cloudinaryUrl.trim();
        if (url.isBlank()) {
            log.info("Ảnh upload lưu ở thư mục {} (chưa cấu hình Cloudinary)", localDir);
            return new LocalImageStorage(localDir, publicBaseUrl);
        }
        log.info("Ảnh upload đẩy lên Cloudinary, cloud: {}", URI.create(url).getHost());
        return new CloudinaryImageStorage(url);
    }

    static String composeCloudinaryUrl(String cloudName, String apiKey, String apiSecret) {
        if (cloudName.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) return "";
        return "cloudinary://" + apiKey.trim() + ":" + apiSecret.trim() + "@" + cloudName.trim();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**").addResourceLocations("file:" + localDir + "/");
    }
}
