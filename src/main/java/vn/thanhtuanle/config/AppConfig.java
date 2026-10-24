package vn.thanhtuanle.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /** The wall clock, injectable so a test can pin it (a run's deadline, {@code RunService}). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
