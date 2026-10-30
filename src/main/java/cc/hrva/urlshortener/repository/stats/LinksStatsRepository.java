package cc.hrva.urlshortener.repository.stats;

import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.dto.stats.LinksStatsResponse.DeadDestinationCounts;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class LinksStatsRepository {

    private static final String START = "start";
    private static final String END = "end";
    private static final String NOW = "now";
    private static final String LIMIT = "limit";

    private static final String COUNT_CREATED = """
            select count(*) as total_links,
                   coalesce(sum(case when u.owner_id is null then 1 else 0 end), 0) as anonymous_links,
                   coalesce(sum(case when exists (select 1 from url_tags t where t.url_id = u.id and t.tag = 'ai') then 1 else 0 end), 0)
                       as ai_tagged_links
            from url u
            where u.create_date >= :start and u.create_date < :end
            """;

    private static final String COUNT_INVENTORY = """
            select count(*) as total_links,
                   coalesce(sum(case when active = true then 1 else 0 end), 0) as active_links,
                   coalesce(sum(case when expiration_date < :now then 1 else 0 end), 0) as expired_links,
                   coalesce(sum(case when password_hash is not null then 1 else 0 end), 0) as password_protected_links,
                   coalesce(sum(case when visit_limit > 0 then 1 else 0 end), 0) as visit_limited_links,
                   coalesce(sum(case when expiration_date is not null then 1 else 0 end), 0) as expiring_links
            from url
            """;

    private static final String COUNT_BLOCKED_BY_REASON = """
            select block_reason, count(*) as total_links from url
            where block_reason is not null
            group by block_reason
            order by count(*) desc, block_reason
            """;

    private static final String FIND_TOP_TAGS = """
            select tag, count(*) as total_links from url_tags
            group by tag
            order by count(*) desc, tag
            fetch first :limit rows only
            """;

    private static final String COUNT_DEAD_DESTINATIONS = """
            select count(*) as dead_links,
                   coalesce(sum(case when owner_id is null then 1 else 0 end), 0) as anonymous_dead_links
            from url
            where active = true and destination_dead_since is not null
            """;

    private static final String FIND_ACTIVE_DESTINATIONS = "select long_url from url where active = true";

    private static final String COUNT_CUSTOM_DOMAINS_BY_STATUS = "select status, count(*) as total_domains from custom_domain group by status";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public CreatedCounts countCreated(final LocalDateTime start, final LocalDateTime end) {
        return jdbcTemplate.queryForObject(COUNT_CREATED, Map.of(START, start, END, end), (row, rowNumber) -> new CreatedCounts(
                row.getLong("total_links"),
                row.getLong("anonymous_links"),
                row.getLong("ai_tagged_links")));
    }

    public InventoryCounts countInventory(final LocalDateTime now) {
        return jdbcTemplate.queryForObject(COUNT_INVENTORY, Map.of(NOW, now), (row, rowNumber) -> new InventoryCounts(
                row.getLong("total_links"),
                row.getLong("active_links"),
                row.getLong("expired_links"),
                row.getLong("password_protected_links"),
                row.getLong("visit_limited_links"),
                row.getLong("expiring_links")));
    }

    public List<CountEntry> countBlockedByReason() {
        return jdbcTemplate.query(COUNT_BLOCKED_BY_REASON, Map.of(), (row, rowNumber) ->
                new CountEntry(row.getString("block_reason"), row.getLong("total_links")));
    }

    public List<CountEntry> findTopTags(final int limit) {
        return jdbcTemplate.query(FIND_TOP_TAGS, Map.of(LIMIT, limit), (row, rowNumber) ->
                new CountEntry(row.getString("tag"), row.getLong("total_links")));
    }

    public List<CountEntry> findTopDomains(final int limit) {
        final var linksByHost = new HashMap<String, Long>();
        jdbcTemplate.query(FIND_ACTIVE_DESTINATIONS, Map.of(), (RowCallbackHandler) row ->
                DomainNames.hostOf(row.getString("long_url")).ifPresent(host -> linksByHost.merge(host, 1L, Long::sum)));
        return linksByHost.entrySet().stream()
                .map(entry -> new CountEntry(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(CountEntry::count).reversed().thenComparing(CountEntry::value))
                .limit(limit)
                .toList();
    }

    public DeadDestinationCounts countDeadDestinations() {
        return jdbcTemplate.queryForObject(COUNT_DEAD_DESTINATIONS, Map.of(), (row, rowNumber) -> new DeadDestinationCounts(
                row.getLong("dead_links"),
                row.getLong("anonymous_dead_links")));
    }

    public Map<CustomDomainStatus, Long> countCustomDomainsByStatus() {
        final var counts = new EnumMap<CustomDomainStatus, Long>(CustomDomainStatus.class);
        jdbcTemplate.query(COUNT_CUSTOM_DOMAINS_BY_STATUS, Map.of(), (RowCallbackHandler) row ->
                counts.put(CustomDomainStatus.valueOf(row.getString("status")), row.getLong("total_domains")));
        return counts;
    }

    public record CreatedCounts(long total, long anonymous, long aiTagged) {}

    public record InventoryCounts(long total, long active, long expired, long passwordProtected, long withVisitLimit, long withExpiry) {}

}
