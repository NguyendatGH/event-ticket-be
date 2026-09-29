package com.example.demo.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "JWT SECRET length must have at least 32 character")
        String secret,
        Duration accessTokenTtl) {
    public JwtProperties {
        if (accessTokenTtl == null) accessTokenTtl = Duration.ofMinutes(15);
    }

}
