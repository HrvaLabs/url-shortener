package cc.hrva.urlshortener.repository.stats;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum HourlySeries {

    LINKS_BY_CHANNEL("url", "create_date", "coalesce(created_via, 'unknown')");

    private final String table;
    private final String timestampColumn;
    private final String keyExpression;

}
