package vn.thanhtuanle.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test profile keeps actuator on the main port (MockMvc reaches it there), so this reads the shipped profiles
 * directly: in dev and prod actuator listens on 8081 — Prometheus scrapes it inside oj-net — and the API port
 * 8000, which the gateway and the sandboxes' heartbeat reach, serves no actuator at all.
 */
class ManagementPortConfigTest {

    @ParameterizedTest
    @ValueSource(strings = {"application-dev.yml", "application-prod.yml"})
    void actuatorListensOn8081(String profileFile) throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load(profileFile, new ClassPathResource(profileFile));

        assertThat(String.valueOf(sources.get(0).getProperty("management.server.port"))).isEqualTo("8081");
    }
}
