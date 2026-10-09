package com.example.demo.infrastructure.gateway.banksim;

import com.example.demo.domain.common.DomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class GatewaySecretCipher {

    private final String masterKey;

    public GatewaySecretCipher(@Value("${app.bank-simulate.credential-key:}") String masterKey) {
        this.masterKey = masterKey == null ? "" : masterKey;
    }

    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không mã hoá được secret gateway", e);
        }
    }

    public String decrypt(String ciphertext) {
        requireKey();
        try {
            String[] parts = ciphertext.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new DomainException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "GATEWAY_CREDENTIAL_UNREADABLE", "Không đọc được credential gateway của ban tổ chức");
        }
    }

    private void requireKey() {
        if (masterKey.isBlank())
            throw new DomainException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "GATEWAY_CREDENTIAL_KEY_MISSING", "Chưa cấu hình BANK_SIMULATE_CREDENTIAL_KEY");
    }

    private SecretKeySpec key() throws GeneralSecurityException {
        return new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                .digest(masterKey.getBytes(StandardCharsets.UTF_8)), "AES");
    }
}
