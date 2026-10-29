package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.NotificationDelivery;
import cc.hrva.urlshortener.model.enums.DeliveryStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    List<NotificationDelivery> findTop20ByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(DeliveryStatus status, LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update NotificationDelivery d set d.status = :sending, d.claimedAt = :now
            where d.id = :id and d.status = :pending and d.nextAttemptAt <= :now
            """)
    int claim(@Param("id") Long id, @Param("now") LocalDateTime now,
              @Param("pending") DeliveryStatus pending, @Param("sending") DeliveryStatus sending);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update NotificationDelivery d set d.status = :pending, d.claimedAt = null
            where d.status = :sending and d.claimedAt < :cutoff
            """)
    int releaseStale(@Param("cutoff") LocalDateTime cutoff,
                     @Param("pending") DeliveryStatus pending, @Param("sending") DeliveryStatus sending);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from NotificationDelivery d where d.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);

}
