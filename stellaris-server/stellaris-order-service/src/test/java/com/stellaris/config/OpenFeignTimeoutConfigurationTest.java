package com.stellaris.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class OpenFeignTimeoutConfigurationTest {

    @Test
    void paymentClientTimeoutIsNestedUnderOpenFeignClientConfig() throws Exception {
        var properties = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .get(0);

        assertThat(properties.getProperty(
                "spring.cloud.openfeign.client.config.default.read-timeout")).isEqualTo(3000);
        assertThat(properties.getProperty(
                "spring.cloud.openfeign.client.config.stellaris-pay-service.read-timeout")).isEqualTo(20000);
        assertThat(properties.getProperty("spring.cloud.openfeign.okhttp.enabled")).isEqualTo(true);
        assertThat(properties.getProperty("stellaris-pay-service.read-timeout")).isNull();
    }
}
