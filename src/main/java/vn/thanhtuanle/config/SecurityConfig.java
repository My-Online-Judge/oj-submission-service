package vn.thanhtuanle.config;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import vn.thanhtuanle.oj.common.security.OjJwtAuthenticationFilter;
import vn.thanhtuanle.oj.common.web.security.OjAuthenticationEntryPoint;
import vn.thanhtuanle.oj.common.web.security.OjAccessDeniedHandler;

@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final OjJwtAuthenticationFilter jwtAuthFilter;
    private final OjAuthenticationEntryPoint authenticationEntryPoint;
    private final OjAccessDeniedHandler accessDeniedHandler;
    private static final String[] WHITE_LIST = {
            "/h2-console/**",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/api/v1/languages",
            // judge_server heartbeat: authenticated by X-Judge-Server-Token, not JWT
            "/api/judge_server_heartbeat",
            "/api/judge_server_heartbeat/",
            // Observability endpoints for Prometheus scrape + ops (T5-11). Metric/health data
            // only; other actuator endpoints (env, beans, …) stay authenticated.
            "/actuator/prometheus",
            "/actuator/metrics",
            "/actuator/metrics/**",
            "/actuator/health",
            "/actuator/health/**",
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CORS is answered by the api-gateway (oj-api-gateway), the only public entry point.
                // Emitting it here as well would give the browser a duplicate
                // Access-Control-Allow-Origin, which it rejects.
                .cors(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::disable))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // An SSE verdict is completed by an ASYNC dispatch of a request already authorized; the JWT
                        // filter does not run on it, and authorizing it again fails on a committed response. An ERROR
                        // dispatch renders the error of a request that was checked as well.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(WHITE_LIST).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
