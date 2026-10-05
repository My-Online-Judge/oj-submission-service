package vn.thanhtuanle.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import vn.thanhtuanle.oj.common.security.OjJwtAuthenticationFilter;
import vn.thanhtuanle.oj.common.security.OjJwtDecoders;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole request path with real tokens in the shape identity-service issues, verified by
 * oj-common's lenient filter inside this service's real SecurityConfig chain. Only the key lookup
 * differs: the decoder is keyed with a test key directly, because MockMvc has no identity-service
 * for the JWKS URL to reach.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OjJwtWiringTest.TestKeyDecoder.class)
class OjJwtWiringTest {

    static final RSAKey KEY = generateKey();

    @TestConfiguration
    static class TestKeyDecoder {
        @Bean
        JwtDecoder testJwtDecoder() throws JOSEException {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(OjJwtDecoders.validator());
            return decoder;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("wiring-test").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(JWTClaimsSet claims) throws JOSEException {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    private static JWTClaimsSet.Builder claims(Instant expiresAt) {
        return new JWTClaimsSet.Builder()
                .subject("wiring-test")
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(expiresAt.minusSeconds(900)))
                .expirationTime(Date.from(expiresAt));
    }

    private static String tokenFor(String... authorities) throws JOSEException {
        return sign(claims(Instant.now().plusSeconds(600))
                .claim(OjJwtDecoders.UID_CLAIM, UUID.randomUUID().toString())
                .claim(OjJwtAuthenticationFilter.AUTHORITIES_CLAIM, List.of(authorities))
                .build());
    }

    /** Shaped like a pre-1a token: a roles claim and no uid. */
    private static String oldFormatToken(Instant expiresAt) throws JOSEException {
        return sign(claims(expiresAt).claim("roles", List.of("ADMIN")).build());
    }

    @Test
    void aTokenAuthorisesByItsAuthorities() throws Exception {
        mockMvc.perform(get("/api/v1/judge-servers").header("Authorization", "Bearer " + tokenFor("judgeserver:read")))
                .andExpect(status().isOk());
    }

    @Test
    void aTokenWithoutThePermissionIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/judge-servers").header("Authorization", "Bearer " + tokenFor("problem:create")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOldFormatTokenIsAnonymous_401OnProtected_200OnPublic() throws Exception {
        String old = oldFormatToken(Instant.now().plusSeconds(600));

        mockMvc.perform(get("/api/v1/judge-servers").header("Authorization", "Bearer " + old))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/languages").header("Authorization", "Bearer " + old))
                .andExpect(status().isOk());
    }

    @Test
    void anExpiredTokenLeftInTheCookieDoesNotBlockPublicEndpoints() throws Exception {
        // The cookie's age is fixed (1 day) while the token lifetime is configuration: it can ride expired.
        String expired = oldFormatToken(Instant.now().minusSeconds(3600));

        mockMvc.perform(get("/api/v1/languages").cookie(new Cookie("accessToken", expired)))
                .andExpect(status().isOk());
    }
}
