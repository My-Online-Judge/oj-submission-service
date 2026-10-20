package vn.thanhtuanle;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.thanhtuanle.common.enums.SubmissionResult;
import vn.thanhtuanle.entity.Language;
import vn.thanhtuanle.entity.Submission;
import vn.thanhtuanle.language.LanguageRepository;
import vn.thanhtuanle.oj.common.identity.PublicUser;
import vn.thanhtuanle.oj.common.identity.PublicUserClient;
import vn.thanhtuanle.oj.common.security.OjJwtDecoders;
import vn.thanhtuanle.submission.SubmissionRateLimiter;
import vn.thanhtuanle.submission.SubmissionRepository;
import vn.thanhtuanle.submission.SubmissionSseRegistry;
import vn.thanhtuanle.submission.dto.SubmissionResponseDto;
import vn.thanhtuanle.submission.problem.JudgeSpec;
import vn.thanhtuanle.submission.problem.ProblemCatalog;
import vn.thanhtuanle.support.PostgresTest;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * No HTTP path holds a pooled database connection while it waits on another service (sub-project 11's rule). Each
 * stubbed remote call records the pool's active connections when it is made. The requests go through MockMvc and the
 * whole filter and interceptor chain, so open-in-view takes part if it is ever switched back on: with it, the
 * request's EntityManager keeps the first connection it used until the response completes. On Postgres, because a
 * submit writes its outbox message. The queue-depth refresh runs once at startup only, so no scheduled read takes a
 * connection meanwhile.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "judge.queue-depth-refresh-ms=86400000")
class NoConnectionHeldAcrossRemoteCallsTest extends PostgresTest {

    @Autowired MockMvc mockMvc;
    @Autowired DataSource dataSource;
    @Autowired SubmissionRepository submissions;
    @Autowired LanguageRepository languages;
    @Autowired JdbcTemplate jdbc;
    @Autowired SubmissionSseRegistry sseRegistry;

    @MockitoBean ProblemCatalog catalog;
    @MockitoBean PublicUserClient publicUserClient;
    @MockitoBean SubmissionRateLimiter rateLimiter;

    private final UUID user = UUID.randomUUID();
    private final String slug = "probe-" + UUID.randomUUID();
    private final List<UUID> created = new ArrayList<>();
    /** The pool's active connections at each stubbed remote call, in call order. */
    private final List<Integer> activeAtCalls = new CopyOnWriteArrayList<>();
    private HikariPoolMXBean pool;
    private Language language;

    @BeforeEach
    void seed() throws SQLException {
        pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
        language = languages.save(Language.builder().name("C++").identifier("cpp-" + UUID.randomUUID()).build());
    }

    @AfterEach
    void clean() {
        created.forEach(id -> jdbc.update("DELETE FROM t_outbox WHERE message_key = ?", id.toString()));
        submissions.deleteAllById(created);
        languages.deleteById(language.getId());
    }

    private void probe() {
        activeAtCalls.add(pool.getActiveConnections());
    }

    private UUID add(int status) {
        UUID id = submissions.save(Submission.builder().sourceCode("int main(){}").status(status).time(0).memory(1L)
                .problemId(UUID.randomUUID()).problemSlug(slug).language(language).userId(user).build()).getId();
        created.add(id);
        return id;
    }

    private RequestPostProcessor signedIn(String... authorities) {
        SimpleGrantedAuthority[] granted = new SimpleGrantedAuthority[authorities.length];
        for (int i = 0; i < authorities.length; i++) {
            granted[i] = new SimpleGrantedAuthority(authorities[i]);
        }
        return jwt().jwt(t -> t.claim(OjJwtDecoders.UID_CLAIM, user.toString())).authorities(granted);
    }

    @Test
    void publicStatisticsHoldNoConnectionWhileAskingProblemServiceWhichSlugsAreVisible() throws Exception {
        add(SubmissionResult.ACCEPTED.getValue());
        when(catalog.visibleSlugs(any())).thenAnswer(inv -> {
            probe();
            return Set.of(slug);
        });

        mockMvc.perform(get("/api/v1/users/{id}/stats", user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.solvedProblems[0]").value(slug));

        assertThat(activeAtCalls).as("active connections during ListVisibleSlugs").containsExactly(0);
    }

    @Test
    void theAdminSearchHoldsNoConnectionWhileAskingIdentity() throws Exception {
        add(SubmissionResult.ACCEPTED.getValue());
        PublicUser alice = new PublicUser(user, "alice", "alice", null, null);
        when(publicUserClient.byUsername("alice")).thenAnswer(inv -> {
            probe();
            return Optional.of(alice);
        });
        when(publicUserClient.byIds(any())).thenAnswer(inv -> {
            probe();
            return Map.of(user, alice);
        });

        mockMvc.perform(get("/api/v1/submissions").param("username", "alice").param("problemSlug", slug)
                        .with(signedIn("submission:read_any")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].username").value("alice"));

        assertThat(activeAtCalls).as("active connections during the username lookup, then the page's profiles")
                .containsExactly(0, 0);
    }

    @Test
    void aSubmitHoldsNoConnectionDuringTheProblemCallOrTheCooldown() throws Exception {
        when(catalog.judgeSpec(slug, false, null)).thenAnswer(inv -> {
            probe();
            return new JudgeSpec(UUID.randomUUID(), slug, 1000, 256L, "abc123def456");
        });
        doAnswer(inv -> {
            probe();
            return null;
        }).when(rateLimiter).acquire(user);

        MvcResult result = mockMvc.perform(post("/api/v1/submissions").with(signedIn())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceCode\":\"int main(){}\",\"languageIdentifier\":\"" + language.getIdentifier()
                                + "\",\"problemSlug\":\"" + slug + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        created.add(UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.data.id")));

        assertThat(activeAtCalls).as("active connections during GetJudgeSpec, then the Redis cooldown")
                .containsExactly(0, 0);
    }

    /**
     * A verdict stream stays open up to 5 minutes. The submission is read and authorized in a short transaction before
     * the emitter is returned, and nothing is held while it waits for the verdict.
     */
    @Test
    void anOpenVerdictStreamHoldsNoConnection() throws Exception {
        UUID id = add(SubmissionResult.PENDING.getValue());

        MvcResult stream = mockMvc.perform(get("/api/v1/submissions/{id}/stream", id).with(signedIn()))
                .andExpect(request().asyncStarted())
                .andReturn();
        try {
            assertThat(sseRegistry.holds(id.toString())).as("the stream is open").isTrue();
            assertThat(pool.getActiveConnections()).as("active connections while the stream waits").isZero();
        } finally {
            sseRegistry.complete(id.toString(), SubmissionResponseDto.builder().build());
            mockMvc.perform(asyncDispatch(stream));
        }
    }
}
