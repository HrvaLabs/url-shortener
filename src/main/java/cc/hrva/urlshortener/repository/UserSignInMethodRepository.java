package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.UserSignInMethod;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface UserSignInMethodRepository extends JpaRepository<UserSignInMethod, Long> {

    List<UserSignInMethod> findByUserIdIn(Collection<Long> userIds);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(nativeQuery = true, value = """
            MERGE INTO user_sign_in_method t
            USING (VALUES (CAST(:userId AS BIGINT), CAST(:method AS VARCHAR(20)), CAST(:usedAt AS TIMESTAMP))) AS s(user_id, method, used_at)
            ON t.user_id = s.user_id AND t.method = s.method
            WHEN MATCHED THEN UPDATE SET last_used_at = GREATEST(t.last_used_at, s.used_at)
            WHEN NOT MATCHED THEN INSERT (user_id, method, first_used_at, last_used_at) VALUES (s.user_id, s.method, s.used_at, s.used_at)
            """)
    int recordUse(@Param("userId") Long userId, @Param("method") String method, @Param("usedAt") LocalDateTime usedAt);

}
