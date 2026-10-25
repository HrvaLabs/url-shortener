package cc.hrva.urlshortener.repository.stats;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum HourlySeries {

    LINKS_BY_CHANNEL("url", "create_date", "coalesce(created_via, 'unknown')"),
    SIGNUPS_BY_PROVIDER("user_account", "create_date", "coalesce(auth_provider, 'local')"),
    REVIEWS_BY_SOURCE("link_review", "flagged_at", "case when system_flagged then 'SYSTEM' else 'VISITOR' end"),
    BLOCKS_BY_REASON("url", "blocked_at", "coalesce(block_reason, 'unknown')"),
    EMAILS_BY_STATUS("email_log", "created_at", "status");

    private final String table;
    private final String timestampColumn;
    private final String keyExpression;

}
