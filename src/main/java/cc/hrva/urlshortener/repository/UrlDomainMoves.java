package cc.hrva.urlshortener.repository;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UrlDomainMoves {

    private static final String LINKS_OF = "select id, short_url, active from url where domain_id = :domainId order by id";

    private static final String MOVE_TO_PRIMARY = "update url set domain_id = null, short_url = :alias where id = :id and domain_id = :fromDomainId";

    private static final String DEACTIVATE_ALL = "update url set active = false where domain_id = :domainId and active = true";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public List<DomainLink> linksOf(final long domainId) {
        return jdbcTemplate.query(LINKS_OF, Map.of("domainId", domainId),
                (resultSet, row) -> new DomainLink(resultSet.getLong("id"), resultSet.getString("short_url"), resultSet.getBoolean("active")));
    }

    public boolean moveToPrimary(final long urlId, final long fromDomainId, final String alias) {
        return jdbcTemplate.update(MOVE_TO_PRIMARY, Map.of("id", urlId, "fromDomainId", fromDomainId, "alias", alias)) == 1;
    }

    public int deactivateAll(final long domainId) {
        return jdbcTemplate.update(DEACTIVATE_ALL, Map.of("domainId", domainId));
    }

    public record DomainLink(long id, String shortUrl, boolean active) {
    }

}
