package cc.hrva.urlshortener.repository.stats;

import cc.hrva.urlshortener.dto.stats.CountEntry;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.ApiKeyCounts;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.BioPageCounts;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse.McpCounts;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    public record AccountCounts(long total, long active, long emailVerified, long twoFactorEnabled) {}

}
