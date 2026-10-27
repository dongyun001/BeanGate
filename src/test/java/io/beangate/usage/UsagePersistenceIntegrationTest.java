package io.beangate.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Verifies the real MySQL path: Flyway owns the schema, usage rows are batch-written off the request
 * thread, and a replayed batch (duplicate request_id) does not double count.
 */
@SpringBootTest
@ActiveProfiles("test")
class UsagePersistenceIntegrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("beangate")
            .withUsername("beangate")
            .withPassword("beangate");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @AfterAll
    static void stopContainer() {
        MYSQL.stop();
    }

    @Autowired
    private AsyncUsageRecorder recorder;
    @Autowired
    private UsageLogRepository repository;

    @Test
    void flywaySchemaIsUsableAndBatchWritesAreIdempotent() throws Exception {
        long apiKeyId = 4242L;
        UsageRecord record = new UsageRecord("req-" + UUID.randomUUID(), apiKeyId, "sk-gw-test", 7L,
                "provider", "public-model", "upstream-model", 10, 5, 15, 120, 30L, false, 200, null, false);

        recorder.record(record);
        recorder.flush();

        assertThat(repository.countForApiKey(apiKeyId)).isEqualTo(1);
        assertThat(repository.sumTotalTokensForApiKey(apiKeyId)).isEqualTo(15);

        // Same request_id replayed after a partial batch failure: must not create a second row.
        recorder.record(record);
        recorder.flush();

        assertThat(repository.countForApiKey(apiKeyId)).isEqualTo(1);
        assertThat(recorder.droppedCount()).isZero();
    }
}
