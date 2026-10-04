package vn.thanhtuanle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * submission-db is built by Flyway alone: V1 (the four live tables) + V2 (the languages). The context starting at
 * all proves Hibernate's ddl-auto=validate accepts them for every entity; the assertions pin what the monolith's
 * migration tests used to guard (a required problem_slug, an error_message that holds a compiler's full output).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SubmissionSchemaTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void submissionDb(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    // Seeded by V2.
    private static final String C_LANGUAGE_ID = "8eb51c84-2d03-4f86-92c3-1f31a671ff12";

    @Autowired JdbcTemplate jdbc;

    private void insertSubmission(String slugOrNull, String errorMessage) {
        jdbc.update("INSERT INTO t_submissions (id, status, \"time\", created_at, updated_at, language_id, problem_id, "
                        + "problem_slug, user_id, error_message) VALUES (?, -2, 0, now(), now(), ?, ?, ?, ?, ?)",
                UUID.randomUUID(), UUID.fromString(C_LANGUAGE_ID), UUID.randomUUID(), slugOrNull, UUID.randomUUID(),
                errorMessage);
    }

    @Test
    void submissionDbHoldsTheFourSubmissionTablesAndTheLanguages() {
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).containsExactly("1", "2");
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                + "ORDER BY table_name", String.class))
                .containsExactly("flyway_schema_history", "t_judge_servers", "t_languages", "t_outbox", "t_submissions");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM t_languages", Integer.class)).isEqualTo(8);
    }

    @Test
    void aSubmissionNamesItsProblemAndKeepsAFullCompilerOutput() {
        insertSubmission("two-sum", "x".repeat(5_000));

        assertThat(jdbc.queryForObject("SELECT length(error_message) FROM t_submissions WHERE problem_slug = 'two-sum'",
                Integer.class)).isEqualTo(5_000);
        assertThatThrownBy(() -> insertSubmission(null, null))
                .hasMessageContaining("problem_slug");
    }
}
