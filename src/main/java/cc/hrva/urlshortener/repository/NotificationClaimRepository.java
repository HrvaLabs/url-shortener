package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationClaimRepository {

    private static final String INSERT = """
            insert into notification_claim (user_id, claim_key, created_at) values (:userId, :claimKey, :createdAt)
            """;

    private static final String DELETE_OLD = "delete from notification_claim where created_at < :cutoff";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public boolean claim(final long userId, final String claimKey, final LocalDateTime now) {
        try {
            return jdbcTemplate.update(INSERT, Map.of("userId", userId, "claimKey", claimKey, "createdAt", now)) == 1;
        } catch (final DuplicateKeyException exception) {
            return false;
        }
    }

    public int deleteCreatedBefore(final LocalDateTime cutoff) {
        return jdbcTemplate.update(DELETE_OLD, Map.of("cutoff", cutoff));
    }

}
