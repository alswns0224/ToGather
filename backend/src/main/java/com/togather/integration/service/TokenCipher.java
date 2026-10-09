package com.togather.integration.service;

import com.togather.common.config.TogatherProperties;
import com.togather.common.exception.BusinessException;
import com.togather.common.exception.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 외부 서비스 토큰 암호화 (AES-256-GCM). 키는 TOGATHER_TOKEN_KEY.
 * 저장 형식: base64(iv 12바이트 + 암호문)
 */
@Component
@RequiredArgsConstructor
public class TokenCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TogatherProperties properties;

    /** Access Token과 Refresh Token을 함께 암호화한다. */
    public String encrypt(String accessToken, String refreshToken) {
        String plain = nullToEmpty(accessToken) + "\n" + nullToEmpty(refreshToken);
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw new BusinessException(ErrorCode.TOKEN_KEY_NOT_CONFIGURED);
        }
    }

    public Tokens decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return new Tokens(null, null);
        }
        try {
            byte[] data = Base64.getDecoder().decode(encrypted);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, data, 0, IV_LENGTH));
            String plain = new String(cipher.doFinal(data, IV_LENGTH, data.length - IV_LENGTH), StandardCharsets.UTF_8);
            int split = plain.indexOf('\n');
            return new Tokens(emptyToNull(plain.substring(0, split)), emptyToNull(plain.substring(split + 1)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 키가 바뀌었거나 데이터가 손상됨 → 재인증이 필요하다.
            throw new BusinessException(ErrorCode.REAUTH_REQUIRED);
        }
    }

    public record Tokens(String accessToken, String refreshToken) {

        @Override
        public String toString() {
            return "Tokens[hidden]";
        }
    }

    /** base64로 16/24/32바이트면 그대로 쓰고, 아니면 문자열의 SHA-256을 키로 쓴다. */
    private SecretKeySpec key() throws GeneralSecurityException {
        String raw = properties.tokenKey();
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.TOKEN_KEY_NOT_CONFIGURED);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(raw.strip());
        } catch (IllegalArgumentException e) {
            bytes = new byte[0];
        }
        if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
            bytes = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        }
        return new SecretKeySpec(bytes, "AES");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
