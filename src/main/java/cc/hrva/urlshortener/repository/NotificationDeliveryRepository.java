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
    @Query("""
            update NotificationDelivery d
            set d.status = :sent, d.attempts = :attempts, d.finishedAt = :at, d.lastHttp = :http, d.lastError = null, d.claimedAt = null
            where d.id = :id and d.status = :sending
            """)
    int markSent(@Param("id") Long id, @Param("attempts") int attempts, @Param("at") LocalDateTime at, @Param("http") Integer http,
                 @Param("sent") DeliveryStatus sent, @Param("sending") DeliveryStatus sending);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update NotificationDelivery d
            set d.status = :pending, d.attempts = :attempts, d.nextAttemptAt = :next, d.lastHttp = :http, d.lastError = :error, d.claimedAt = null
            where d.id = :id and d.status = :sending
            """)
    int retryLater(@Param("id") Long id, @Param("attempts") int attempts, @Param("next") LocalDateTime next, @Param("http") Integer http,
                   @Param("error") String error, @Param("pending") DeliveryStatus pending, @Param("sending") DeliveryStatus sending);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update NotificationDelivery d
            set d.status = :gaveUp, d.attempts = :attempts, d.finishedAt = :at, d.lastHttp = :http, d.lastError = :error, d.claimedAt = null
            where d.id = :id and d.status = :sending
            """)
    int giveUp(@Param("id") Long id, @Param("attempts") int attempts, @Param("at") LocalDateTime at, @Param("http") Integer http,
               @Param("error") String error, @Param("gaveUp") DeliveryStatus gaveUp, @Param("sending") DeliveryStatus sending);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from NotificationDelivery d where d.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);

}
