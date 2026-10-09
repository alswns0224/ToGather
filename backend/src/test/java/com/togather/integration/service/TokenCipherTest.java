package com.togather.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.togather.common.config.TogatherProperties;
import com.togather.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class TokenCipherTest {

    private TokenCipher cipher(String key) {
        return new TokenCipher(new TogatherProperties("http://localhost", key, null, null, null, null));
    }

    @Test
    void 암호화한_토큰을_복호화하면_원래_값이_나온다() {
        TokenCipher cipher = cipher("test-key");

        String encrypted = cipher.encrypt("access-123", "refresh-456");
        TokenCipher.Tokens tokens = cipher.decrypt(encrypted);

        assertThat(encrypted).doesNotContain("access-123");
        assertThat(tokens.accessToken()).isEqualTo("access-123");
        assertThat(tokens.refreshToken()).isEqualTo("refresh-456");
    }

    @Test
    void 리프레시_토큰이_없어도_된다() {
        TokenCipher cipher = cipher("test-key");

        TokenCipher.Tokens tokens = cipher.decrypt(cipher.encrypt("access-only", null));

        assertThat(tokens.accessToken()).isEqualTo("access-only");
        assertThat(tokens.refreshToken()).isNull();
    }

    @Test
    void 키가_다르면_복호화할_수_없다() {
        String encrypted = cipher("key-a").encrypt("access", "refresh");

        assertThatThrownBy(() -> cipher("key-b").decrypt(encrypted)).isInstanceOf(BusinessException.class);
    }

    @Test
    void 같은_값도_매번_다르게_암호화된다() {
        TokenCipher cipher = cipher("test-key");

        assertThat(cipher.encrypt("a", "b")).isNotEqualTo(cipher.encrypt("a", "b"));
    }
}
