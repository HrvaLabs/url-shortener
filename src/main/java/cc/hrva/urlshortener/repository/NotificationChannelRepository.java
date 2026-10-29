package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.model.enums.NotificationType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, Long> {

    List<NotificationChannel> findByOwnerIdOrderByCreatedAtAscIdAsc(Long userId);

    Optional<NotificationChannel> findByIdAndOwnerId(Long id, Long userId);

    long countByOwnerId(Long userId);

    @Query("""
            select ch from NotificationChannel ch join fetch ch.owner
            where ch.enabled = true
              and ch.id in (select c.channelId from NotificationConsent c where c.type = :event and c.revokedAt is null)
              and ch.owner.id in :userIds
            order by ch.id
            """)
    List<NotificationChannel> findEnabledWithEvent(@Param("event") NotificationType event, @Param("userIds") Collection<Long> userIds);

    @Query("""
            select distinct ch.owner.id from NotificationChannel ch
            where ch.enabled = true
              and ch.id in (select c.channelId from NotificationConsent c where c.type = :event and c.revokedAt is null)
            """)
    List<Long> findOwnerIdsWithEnabledEvent(@Param("event") NotificationType event);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update NotificationChannel ch
            set ch.lastDeliveryAt = :at, ch.lastDeliveryStatus = :status, ch.lastDeliveryHttp = :http, ch.lastDeliveryError = :error
            where ch.id = :id
            """)
    int recordDelivery(@Param("id") long id, @Param("at") LocalDateTime at, @Param("status") String status, @Param("http") Integer http,
                       @Param("error") String error);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from NotificationChannel ch where ch.owner.id = :userId")
    int deleteByOwnerId(@Param("userId") Long userId);

}
