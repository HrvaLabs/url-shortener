package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.Campaign;
import cc.hrva.urlshortener.model.enums.CampaignStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    Optional<Campaign> findByShapeKey(String shapeKey);

    List<Campaign> findByLastSeenAtAfterOrderByLastSeenAtDesc(LocalDateTime lastSeenAfter);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("update Campaign c set c.status = :status, c.lastSeenAt = :now, c.alertedAt = :now, c.activeUntil = :activeUntil "
            + "where c.id = :id and c.activeUntil <= :now and c.status <> cc.hrva.urlshortener.model.enums.CampaignStatus.ALLOWED")
    int reopen(@Param("id") long id, @Param("status") CampaignStatus status, @Param("now") LocalDateTime now,
            @Param("activeUntil") LocalDateTime activeUntil);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("update Campaign c set c.lastSeenAt = :now, c.activeUntil = :activeUntil where c.id = :id")
    int extendHold(@Param("id") long id, @Param("now") LocalDateTime now, @Param("activeUntil") LocalDateTime activeUntil);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("update Campaign c set c.lastSeenAt = :now where c.id = :id")
    int markSeen(@Param("id") long id, @Param("now") LocalDateTime now);

}
