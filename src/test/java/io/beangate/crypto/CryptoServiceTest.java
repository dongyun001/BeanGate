package io.beangate.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.beangate.config.BeanGateProperties;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CryptoServiceTest {

    private static final CryptoService CRYPTO = new CryptoService(new BeanGateProperties(
            new BeanGateProperties.Security("unit-test-pepper", null, "admin"),
            new BeanGateProperties.Upstream(1000, 1000, 1000, 1000), new BeanGateProperties.Usage(1000, 100, 50)));

    @Test
    void hmacIsDeterministicAndKeyed() {
        String a = CRYPTO.hmacSha256("sk-gw-secret");
        String b = CRYPTO.hmacSha256("sk-gw-secret");

        assertThat(a).isEqualTo(b).hasSize(64);
        assertThat(CRYPTO.hmacSha256("sk-gw-secret2")).isNotEqualTo(a);
    }

    @Test
    void hmacWithDifferentPepperProducesDifferentHash() {
        CryptoService other = new CryptoService(new BeanGateProperties(
                new BeanGateProperties.Security("another-pepper", null, "admin"),
                new BeanGateProperties.Upstream(1000, 1000, 1000, 1000), new BeanGateProperties.Usage(1000, 100, 50)));

        assertThat(other.hmacSha256("sk-gw-secret")).isNotEqualTo(CRYPTO.hmacSha256("sk-gw-secret"));
    }

    @Test
    void aesGcmRoundTripAndRandomIv() {
        String first = CRYPTO.encrypt("sk-upstream-credential");
        String second = CRYPTO.encrypt("sk-upstream-credential");

        assertThat(first).isNotEqualTo(second);
        assertThat(CRYPTO.decrypt(first)).isEqualTo("sk-upstream-credential");
        assertThat(CRYPTO.decrypt(second)).isEqualTo("sk-upstream-credential");
    }

    @Test
    void aesGcmRejectsTamperedCiphertext() {
        byte[] raw = Base64.getDecoder().decode(CRYPTO.encrypt("secret"));
        raw[raw.length - 1] ^= 0x01;

        assertThatThrownBy(() -> CRYPTO.decrypt(Base64.getEncoder().encodeToString(raw)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void generatedSecretsAreUrlSafeAndUnique() {
        String one = CryptoService.newSecret();
        String two = CryptoService.newSecret();

        assertThat(one).hasSize(43).isNotEqualTo(two).matches("[A-Za-z0-9_-]+");
    }
}
