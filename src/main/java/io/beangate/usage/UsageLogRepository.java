package io.beangate.usage;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Batch writer for usage rows.
 *
 * <p>A plain {@code INSERT} is used instead of a dialect-specific upsert; duplicate
 * {@code request_id}s are handled by falling back to row-by-row inserts, which works on both MySQL
 * and H2 without branching on the dialect.
 */
@Repository
public class UsageLogRepository {

    private static final String INSERT_SQL = """
            INSERT INTO usage_log (request_id, api_key_id, key_prefix, provider_id, provider_name,
                public_model, upstream_model, prompt_tokens, completion_tokens, total_tokens,
                latency_ms, first_token_ms, is_stream, http_status, error_code, estimated, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public UsageLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertBatch(List<UsageRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                bind(ps, records.get(i), now);
            }

            @Override
            public int getBatchSize() {
                return records.size();
            }
        });
    }

    /** Used after a duplicate-key failure: inserts one at a time so the rest of the batch still lands. */
    public void insertOneByOneIgnoringDuplicates(List<UsageRecord> records, DuplicateHandler duplicates) {
        for (UsageRecord record : records) {
            try {
                insertBatch(List.of(record));
            } catch (org.springframework.dao.DuplicateKeyException duplicate) {
                duplicates.onDuplicate(record);
            }
        }
    }

    public long count() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM usage_log", Long.class);
        return count == null ? 0L : count;
    }

    public Long countForApiKey(Long apiKeyId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM usage_log WHERE api_key_id = ?", Long.class, apiKeyId);
    }

    public Long sumTotalTokensForApiKey(Long apiKeyId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_tokens), 0) FROM usage_log WHERE api_key_id = ?", Long.class, apiKeyId);
    }

    private static void bind(PreparedStatement ps, UsageRecord r, Timestamp now) throws SQLException {
        ps.setString(1, r.requestId());
        setNullableLong(ps, 2, r.apiKeyId());
        ps.setString(3, r.keyPrefix());
        setNullableLong(ps, 4, r.providerId());
        ps.setString(5, r.providerName());
        ps.setString(6, r.publicModel());
        ps.setString(7, r.upstreamModel());
        setNullableInt(ps, 8, r.promptTokens());
        setNullableInt(ps, 9, r.completionTokens());
        setNullableInt(ps, 10, r.totalTokens());
        ps.setLong(11, r.latencyMs());
        setNullableLong(ps, 12, r.firstTokenMs());
        ps.setBoolean(13, r.stream());
        ps.setInt(14, r.httpStatus());
        ps.setString(15, r.errorCode());
        ps.setBoolean(16, r.estimated());
        ps.setTimestamp(17, now);
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    @FunctionalInterface
    public interface DuplicateHandler {
        void onDuplicate(UsageRecord record);
    }
}
