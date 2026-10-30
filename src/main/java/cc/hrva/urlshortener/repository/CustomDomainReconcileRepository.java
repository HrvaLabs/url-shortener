package cc.hrva.urlshortener.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CustomDomainReconcileRepository {

    private static final String INSERT = """
            insert into custom_domain_reconcile (ran_at, cloudflare_total, ours_in_cloudflare, unknown_hostnames, missing_domain_ids, error)
            values (:ranAt, :cloudflareTotal, :oursInCloudflare, :unknownHostnames, :missingDomainIds, :error)
            """;

    private static final String NEWEST = """
            select ran_at, cloudflare_total, ours_in_cloudflare, unknown_hostnames, missing_domain_ids, error
            from custom_domain_reconcile
            order by ran_at desc, id desc
            fetch first 1 rows only
            """;

    private static final String NEWEST_COMPLETED = """
            select ran_at, cloudflare_total, ours_in_cloudflare, unknown_hostnames, missing_domain_ids, error
            from custom_domain_reconcile
            where error is null
            order by ran_at desc, id desc
            fetch first 1 rows only
            """;

    private static final String DELETE_RAN_BEFORE = "delete from custom_domain_reconcile where ran_at < :cutoff";

    private static final String LINE_SEPARATOR = "\n";
    private static final String ID_SEPARATOR = ":";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public void insert(final ReconcileRun run) {
        final var parameters = new HashMap<String, Object>();
        parameters.put("ranAt", run.ranAt());
        parameters.put("cloudflareTotal", run.cloudflareTotal());
        parameters.put("oursInCloudflare", run.oursInCloudflare());
        parameters.put("unknownHostnames", joined(run.unknownHostnames()));
        parameters.put("missingDomainIds", joined(run.missingDomains().stream().map(missing -> missing.id() + ID_SEPARATOR + missing.hostname()).toList()));
        parameters.put("error", run.error());
        jdbcTemplate.update(INSERT, parameters);
    }

    public Optional<ReconcileRun> newest() {
        return jdbcTemplate.query(NEWEST, Map.of(), CustomDomainReconcileRepository::run).stream().findFirst();
    }

    public Optional<ReconcileRun> newestCompleted() {
        return jdbcTemplate.query(NEWEST_COMPLETED, Map.of(), CustomDomainReconcileRepository::run).stream().findFirst();
    }

    public int deleteRanBefore(final LocalDateTime cutoff) {
        return jdbcTemplate.update(DELETE_RAN_BEFORE, Map.of("cutoff", cutoff));
    }

    private static ReconcileRun run(final ResultSet resultSet, final int row) throws SQLException {
        return new ReconcileRun(
                resultSet.getObject("ran_at", LocalDateTime.class),
                resultSet.getObject("cloudflare_total", Integer.class),
                resultSet.getObject("ours_in_cloudflare", Integer.class),
                lines(resultSet.getString("unknown_hostnames")),
                lines(resultSet.getString("missing_domain_ids")).stream().map(CustomDomainReconcileRepository::missingDomain).toList(),
                resultSet.getString("error"));
    }

    private static String joined(final List<String> values) {
        return values.isEmpty() ? null : String.join(LINE_SEPARATOR, values);
    }

    private static List<String> lines(final String column) {
        if (StringUtils.isBlank(column)) {
            return List.of();
        }
        return column.lines().map(String::strip).filter(StringUtils::isNotEmpty).toList();
    }

    private static MissingDomain missingDomain(final String line) {
        return new MissingDomain(Long.parseLong(StringUtils.substringBefore(line, ID_SEPARATOR)), StringUtils.substringAfter(line, ID_SEPARATOR));
    }

    public record ReconcileRun(LocalDateTime ranAt, Integer cloudflareTotal, Integer oursInCloudflare, List<String> unknownHostnames,
                               List<MissingDomain> missingDomains, String error) {

        public ReconcileRun {
            unknownHostnames = unknownHostnames == null ? List.of() : List.copyOf(unknownHostnames);
            missingDomains = missingDomains == null ? List.of() : List.copyOf(missingDomains);
        }

    }

    public record MissingDomain(long id, String hostname) {
    }

}
