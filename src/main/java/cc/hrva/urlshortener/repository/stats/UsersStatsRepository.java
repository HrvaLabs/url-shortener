package cc.hrva.urlshortener.repository.stats;

import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.ApiKeyCounts;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.BioPageCounts;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.McpCounts;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.NotificationCounts;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.model.enums.NotificationType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UsersStatsRepository {

    private static final String SINCE = "since";
    private static final String TODAY = "today";
    private static final String NOW = "now";

    private static final String COUNT_ACCOUNTS = """
            select count(*) as total_accounts,
                   coalesce(sum(case when active = true then 1 else 0 end), 0) as active_accounts,
                   coalesce(sum(case when email_verified = true then 1 else 0 end), 0) as email_verified_accounts,
                   coalesce(sum(case when two_factor_enabled = true then 1 else 0 end), 0) as two_factor_accounts
            from user_account
            """;

    private static final String COUNT_ADMINS = """
            select count(distinct ua.user_id) from user_account_authorities ua
            join authorities a on a.id = ua.authorities_id
            where a.name = 'ROLE_ADMIN'
            """;

    private static final String COUNT_BY_PROVIDER = """
            select coalesce(auth_provider, 'local') as provider, count(*) as total_accounts from user_account
            group by coalesce(auth_provider, 'local')
            order by count(*) desc, coalesce(auth_provider, 'local')
            """;

    private static final String COUNT_SIGNED_IN_SINCE = "select count(*) from user_account where last_login >= :since";

    private static final String COUNT_API_KEYS = """
            select coalesce(sum(case when active = true and (expiration_date is null or expiration_date > :today) then 1 else 0 end), 0)
                       as active_keys,
                   coalesce(sum(case when expiration_date <= :today then 1 else 0 end), 0) as expired_keys,
                   coalesce(sum(case when active = false and (expiration_date is null or expiration_date > :today) then 1 else 0 end), 0)
                       as revoked_keys,
                   coalesce(sum(api_calls_used), 0) as calls_used
            from api_key
            """;

    private static final String COUNT_CONNECTED_USERS = """
            select count(distinct principal_name) from oauth2_authorization
            where access_token_expires_at > :now or refresh_token_expires_at > :now
            """;

    private static final String COUNT_REGISTERED_CLIENTS = "select count(*) from oauth2_registered_client";

    private static final String COUNT_BIO_PAGES = """
            select count(*) as total_pages, coalesce(sum(case when hidden_at is not null then 1 else 0 end), 0) as hidden_pages
            from bio_page
            """;

    private static final String COUNT_ACTIVE_CONSENTS_BY_TYPE =
            "select type, count(*) as total from notification_consent where revoked_at is null group by type";

    private static final String COUNT_CHANNELS_BY_TYPE = "select type, count(*) as total from notification_channel group by type";

    private static final String COUNT_AUTO_DISABLED_CHANNELS =
            "select count(*) from notification_channel where enabled = false and auto_disabled_at is not null";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AccountCounts countAccounts() {
        return jdbcTemplate.queryForObject(COUNT_ACCOUNTS, Map.of(), (row, rowNumber) -> new AccountCounts(
                row.getLong("total_accounts"),
                row.getLong("active_accounts"),
                row.getLong("email_verified_accounts"),
                row.getLong("two_factor_accounts")));
    }

    public long countAdmins() {
        return jdbcTemplate.queryForObject(COUNT_ADMINS, Map.of(), Long.class);
    }

    public List<CountEntry> countByProvider() {
        return jdbcTemplate.query(COUNT_BY_PROVIDER, Map.of(), (row, rowNumber) ->
                new CountEntry(row.getString("provider"), row.getLong("total_accounts")));
    }

    public long countSignedInSince(final LocalDateTime since) {
        return jdbcTemplate.queryForObject(COUNT_SIGNED_IN_SINCE, Map.of(SINCE, since), Long.class);
    }

    public ApiKeyCounts countApiKeys(final LocalDate today) {
        return jdbcTemplate.queryForObject(COUNT_API_KEYS, Map.of(TODAY, today), (row, rowNumber) -> new ApiKeyCounts(
                row.getLong("active_keys"),
                row.getLong("expired_keys"),
                row.getLong("revoked_keys"),
                row.getLong("calls_used")));
    }

    public McpCounts countMcp(final LocalDateTime now) {
        return new McpCounts(
                jdbcTemplate.queryForObject(COUNT_CONNECTED_USERS, Map.of(NOW, now), Long.class),
                jdbcTemplate.queryForObject(COUNT_REGISTERED_CLIENTS, Map.of(), Long.class));
    }

    public BioPageCounts countBioPages() {
        return jdbcTemplate.queryForObject(COUNT_BIO_PAGES, Map.of(), (row, rowNumber) -> new BioPageCounts(
                row.getLong("total_pages"),
                row.getLong("hidden_pages")));
    }

    public NotificationCounts countNotifications() {
        final var consents = countByType(COUNT_ACTIVE_CONSENTS_BY_TYPE);
        final var channels = countByType(COUNT_CHANNELS_BY_TYPE);
        return new NotificationCounts(
                Arrays.stream(NotificationType.values()).map(type -> new CountEntry(type.name(), consents.getOrDefault(type.name(), 0L))).toList(),
                Arrays.stream(ChannelType.values()).map(type -> new CountEntry(type.name(), channels.getOrDefault(type.name(), 0L))).toList(),
                jdbcTemplate.queryForObject(COUNT_AUTO_DISABLED_CHANNELS, Map.of(), Long.class));
    }

    private Map<String, Long> countByType(final String sql) {
        return jdbcTemplate.query(sql, Map.of(), (row, rowNumber) -> new CountEntry(row.getString("type"), row.getLong("total"))).stream()
                .collect(Collectors.toMap(CountEntry::value, CountEntry::count));
    }

    public record AccountCounts(long total, long active, long emailVerified, long twoFactorEnabled) {}

}
