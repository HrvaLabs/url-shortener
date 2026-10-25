package cc.hrva.urlshortener.repository.stats;

import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SystemStatsRepository {

    private static final String POSTGRESQL = "PostgreSQL";
    private static final String DATABASE_SIZE = "select pg_database_size(current_database())";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DatabaseInfo databaseInfo() {
        final var withoutSize = Objects.requireNonNull(jdbcTemplate.getJdbcOperations().execute(
                (ConnectionCallback<DatabaseInfo>) connection -> {
                    final var metaData = connection.getMetaData();
                    final var version = metaData.getDatabaseMajorVersion() + "." + metaData.getDatabaseMinorVersion();
                    return new DatabaseInfo(metaData.getDatabaseProductName(), version, null);
                }));
        if (!POSTGRESQL.equals(withoutSize.product())) {
            return withoutSize;
        }
        final var sizeBytes = jdbcTemplate.queryForObject(DATABASE_SIZE, Map.of(), Long.class);
        return new DatabaseInfo(withoutSize.product(), withoutSize.version(), sizeBytes);
    }

    public record DatabaseInfo(String product, String version, Long sizeBytes) {}

}
