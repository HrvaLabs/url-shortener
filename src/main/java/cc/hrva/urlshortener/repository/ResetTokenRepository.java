package cc.hrva.urlshortener.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import cc.hrva.urlshortener.model.ResetToken;
import cc.hrva.urlshortener.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ResetTokenRepository extends JpaRepository<ResetToken, Long> {

    List<ResetToken> findByActiveTrue();
    List<ResetToken> findByUserAndActiveTrue(User user);
    Optional<ResetToken> findByUserAndTokenHashAndActiveTrue(User user, String tokenHash);
    List<ResetToken> findByExpirationDateIsLessThanEqualAndActiveTrue(LocalDateTime expirationDate);
    List<ResetToken> findByExpirationDateIsLessThanEqualAndActiveFalse(LocalDateTime expirationDate);
    void deleteByUser(User user);

    @Modifying(flushAutomatically = true)
    @Query("update ResetToken t set t.active = false where t.id = :id and t.active = true and t.expirationDate > :now")
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

}
