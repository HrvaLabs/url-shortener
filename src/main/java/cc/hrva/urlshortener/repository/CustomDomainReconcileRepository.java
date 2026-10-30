package cc.hrva.urlshortener.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
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

    private static final String WITH_LISTS = """
            select id, unknown_hostnames, missing_domain_ids
            from custom_domain_reconcile
            where unknown_hostnames is not null or missing_domain_ids is not null
            order by id
            for update
            """;

    private static final String REWRITE_LISTS =
            "update custom_domain_reconcile set unknown_hostnames = :unknownHostnames, missing_domain_ids = :missingDomainIds where id = :id";

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

    public int forgetHostnames(final Collection<String> hostnames) {
        if (hostnames.isEmpty()) {
            return 0;
        }
        final var forgotten = hostnames.stream().map(hostname -> hostname.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        var rewritten = 0;
        for (final var run : jdbcTemplate.queryForList(WITH_LISTS, Map.of())) {
            final var unknown = lines((String) run.get("unknown_hostnames"));
            final var missing = lines((String) run.get("missing_domain_ids"));
            final var keptUnknown = unknown.stream().filter(hostname -> !forgotten.contains(hostname.toLowerCase(Locale.ROOT))).toList();
            final var keptMissing = missing.stream()
                    .filter(line -> !forgotten.contains(StringUtils.substringAfter(line, ID_SEPARATOR).toLowerCase(Locale.ROOT)))
                    .toList();
            if (keptUnknown.size() == unknown.size() && keptMissing.size() == missing.size()) {
                continue;
            }
            final var parameters = new HashMap<String, Object>();
            parameters.put("id", run.get("id"));
            parameters.put("unknownHostnames", joined(keptUnknown));
            parameters.put("missingDomainIds", joined(keptMissing));
            rewritten += jdbcTemplate.update(REWRITE_LISTS, parameters);
        }
        return rewritten;
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
